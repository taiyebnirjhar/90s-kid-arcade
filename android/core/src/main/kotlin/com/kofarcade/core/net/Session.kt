package com.kofarcade.core.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

// Rooms: one phone per player, on the same Wi-Fi or hotspot. A room has a
// seat per player the game takes (Cadillacs and Dinosaurs: three). Anyone
// may join a running room while a seat is free (the owner says yes), and
// anyone may leave: their seat empties and the game goes on for the others.
// If the owner leaves, the next player takes the room over.
//
// Whoever sits where changes only at a "seating": the owner stops the game,
// sends everyone its state and the new seats, and all carry on from that
// state as the next game (epoch). Between seatings the seats are fixed, so
// the rollback netcode stays as simple as for a fixed match.

class Refused(reason: String) : IOException(reason)

/** What the game screen hears from its room. Called on network threads. */
interface RoomListener {
    /** [player]'s inputs for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(player: Int, start: Int, masks: IntArray)

    /** The games differed at [frame]: the room puts everyone back in sync from the owner's game (a seating). */
    fun onDesync(frame: Int)

    /** This phone is out of the room (it lost everyone): the game can't go on together. */
    fun onClosed(reason: String)

    /** [player] opened (true) or closed (false) their menu: the game waits for everyone. */
    fun onPartnerPaused(player: Int, paused: Boolean) {}

    /** Owner: [name] asks to join. Call [answer] (any thread) with true to let them in. */
    fun onJoinRequest(name: String, answer: (Boolean) -> Unit) = answer(false)

    /** Owner: stop the game and return its state, to seat everyone from. [onSeating] follows. */
    fun captureState(): ByteArray

    /**
     * Game [epoch] starts: the game restarts its rollback from frame 0 with
     * these seats ([activeMask]: bit p = seat p taken), from [state] (null:
     * the game as it stands, on the owner). Runs before any of the new game's
     * inputs are handed out; return once loaded.
     */
    fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?)

    /** Something to tell the player: "Rafi joined", "You're the host now". */
    fun onNews(message: String) {}
}

/** Another phone in the game: its seat, name, and where its inputs come from. */
class Peer(val player: Int, val name: String, val address: InetSocketAddress)

/** A TCP connection to another phone of the room ([player]: its seat, -1 before it has one). */
internal class Line(val socket: Socket, @Volatile var player: Int, @Volatile var name: String) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun close() = runCatching { socket.close() }
}

/** A room's code from its session number: four letters and digits that can't be misread. */
fun roomCode(session: Int): String {
    val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    var n = session.toLong() and 0xFFFFFFFFL
    val size = alphabet.length.toLong()
    return buildString { repeat(4) { append(alphabet[(n % size).toInt()]); n /= size } }
}

/**
 * One phone's place in a room. Make one with [Room.host] or [Room.join],
 * then [start] it once the game can load a state.
 */
class Room private constructor(
    private val name: String,
    private val set: String,
    private val title: String,
    private val rom: Long,
    private val bios: Long,
    /** Seats: how many play this game. */
    val maxPlayers: Int,
    /** This phone's seat (0 = player 1). It keeps it for as long as it's in the room. */
    val localPlayer: Int,
    private val session: Int,
    private val server: ServerSocket,
    private val udp: DatagramSocket,
    owner: Int,
    private val startDelay: Int,
    private val beaconPort: Int,
) : Closeable {
    /** The room's code, shown to tell rooms apart. */
    val code: String = roomCode(session)

    /** The seat of the phone that owns the room: it seats newcomers and runs every change. */
    @Volatile var owner: Int = owner
        private set

    val isOwner: Boolean get() = owner == localPlayer

    /** Who sits where now, this phone included. */
    @Volatile var seats: Map<Int, Seat> = emptyMap()
        private set

    /** The state the session started from, for a rematch (the owner sends it to newcomers). */
    @Volatile var startState: ByteArray? = null

    /** This game's inputs; replaced at each seating. */
    @Volatile var lockstep = Lockstep(startDelay, localPlayer, maxOf(2, maxPlayers), emptyList())
        private set

    /** Which game of the room this is: +1 at each seating. */
    @Volatile var epoch = 0
        private set

    /** This phone's input delay now: it adapts to the connection ([DelayTuner]). */
    val delay: Int get() = lockstep.delay
    private val tuner = DelayTuner()

    /** Everyone else, as of the last seating. */
    val peers: List<Peer>
        get() = seats.values.filter { it.player != localPlayer }
            .map { Peer(it.player, it.name, InetSocketAddress(it.host, it.udpPort)) }

    /** Seats taken, as a bit mask (bit p = seat p). */
    val activeMask: Int get() = seats.keys.fold(0) { m, p -> m or (1 shl p) }

    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var listener: RoomListener? = null
    private val lastHeard = LongArray(4)
    @Volatile private var lastSent = 0L
    private val mySums = HashMap<Int, Long>()
    private val theirSums = HashMap<Int, MutableList<Long>>()
    @Volatile private var latestSum: Pair<Int, Long>? = null
    private val advantage = IntArray(4)
    private val theirAdvantage = IntArray(4)
    private val theirDelay = IntArray(4) { startDelay }
    private val paused = BooleanArray(4)
    private val threads = ArrayList<Thread>()

    // The owner's connections, one per other player (and newcomers before they're seated).
    private val lines = HashMap<Int, Line>()
    // Accepted newcomers waiting to be seated (once their game is ready to load the state).
    private class Arriving(val line: Line, val hello: Hello, @Volatile var ready: Boolean = false)
    private val arriving = HashMap<Int, Arriving>()
    // A player's connection to the owner.
    @Volatile private var ownerLine: Line? = null
    // Seatings, joins and takeovers happen one at a time, here.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "net-room").apply { isDaemon = true } }
    @Volatile private var beacon: BeaconSender? = null
    @Volatile private var lastRematch = 0L
    @Volatile private var lastResync = 0L
    private val migrating = AtomicBoolean(false)
    private val ownerLock = Any()
    @Volatile private var takenOver = CountDownLatch(1)

    val isHost: Boolean get() = isOwner

    fun nameOf(player: Int): String = seats[player]?.name ?: "Player ${player + 1}"

    /** Everyone else's names, for messages: "Pixel 7" or "Pixel 7 & Galaxy S21". */
    val peerName: String get() = peers.joinToString(" & ") { it.name }

    /** The partner furthest ahead of our inputs (they guess the most): what the delay tuner watches. */
    val partnerAdvantage: Int get() = peers.maxOfOrNull { theirAdvantage[it.player] } ?: 0

    /** Someone else has their menu open. */
    val partnerPaused: Boolean get() = peers.any { paused[it.player] }

    /** Who has their menu open, for "X paused the game". */
    val pausedName: String? get() = peers.firstOrNull { paused[it.player] }?.name

    /** Notes how far this phone ([emulated]: its next frame) runs ahead of each partner's inputs. */
    fun updateAdvantage(emulated: Int) {
        val ls = lockstep
        for (p in ls.partners) advantage[p] = emulated - ls.known(p)
    }

    /**
     * How many frames this phone's game runs ahead of the slowest partner's
     * (time sync waits when it's 2 or more). Each advantage counts frames ahead
     * of the other's inputs, which arrive earlier by that phone's input delay;
     * adding it back compares the games themselves, whatever the delays.
     */
    val lead: Int
        get() = lockstep.partners.maxOfOrNull { p ->
            (advantage[p] + theirDelay[p]) - (theirAdvantage[p] + delay)
        } ?: 0

    /**
     * Starts playing: the owner seats itself (and opens the room to others);
     * a newcomer tells the owner it's ready to be seated.
     */
    fun start(listener: RoomListener) {
        this.listener = listener
        started = true
        thread("net-udp") { receiveLoop() }
        thread("net-send") { sendLoop() }
        thread("net-accept") { acceptLoop() }
        if (isOwner) {
            worker.execute { seat(news = null) }
            startBeacon()
        } else {
            val line = ownerLine ?: return
            thread("net-tcp-owner") { memberLoop(line) }
            runCatching { line.output.message(Msg.READY, byteArrayOf(localPlayer.toByte())) }
        }
    }

    /** Sends our unacknowledged inputs now (after sampling new ones). */
    fun flush() {
        // Android kills an app that touches the network on its main thread,
        // which is where the game calls this: the send thread does it, now.
        synchronized(sendSignal) {
            sendNow = true
            sendSignal.notifyAll()
        }
    }

    private val sendSignal = Object()
    private var sendNow = false

    // Only ever on the send thread: each partner gets what it's missing.
    private fun send() {
        // The game and its number together: a seating between reading one and
        // the other would label the old game's inputs as the new one's.
        val (ls, game, sum) = synchronized(deliverLock) { Triple(lockstep, epoch, latestSum) }
        val now = seats
        for (p in ls.partners) {
            val seat = now[p] ?: continue
            val (start, masks) = ls.outgoing(p, InputPacket.MAX_MASKS)
            val packet = InputPacket(
                session, ls.known(p), start, masks, sum?.first ?: -1, sum?.second ?: 0,
                advantage[p], game and 0xFF, ls.delay, localPlayer,
            )
            val bytes = packet.encode()
            // A Wi-Fi hiccup can fail one send: the inputs go again with the
            // next packet, and a partner gone for good shows as silence.
            try {
                udp.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(seat.host, seat.udpPort)))
            } catch (e: Exception) {
                if (closed) return
            }
        }
        lastSent = System.nanoTime()
    }

    /** Our game's state checksum after [frame], compared with the partners'. */
    fun reportSum(frame: Int, sum: Long) = synchronized(deliverLock) {
        latestSum = frame to sum
        check(frame, sum, from = null)
    }

    private fun check(frame: Int, sum: Long, from: Int?) {
        val differs = synchronized(mySums) {
            if (from == null) mySums[frame] = sum else theirSums.getOrPut(frame) { ArrayList() } += sum
            mySums.keys.removeAll { it < frame - 1200 }
            theirSums.keys.removeAll { it < frame - 1200 }
            val mine = mySums[frame]
            mine != null && theirSums[frame].orEmpty().any { it != mine }
        }
        if (differs) {
            listener?.onDesync(frame)
            // Everyone takes the owner's game as it stands: back in step
            // without restarting anything. (On a thread: this can be the game's.)
            runCatching { worker.execute { if (isOwner) resync() else control(Msg.RESYNC) } }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        udp.soTimeout = 500
        while (!closed) {
            try {
                udp.receive(packet)
            } catch (e: SocketTimeoutException) {
                checkQuiet()
                continue
            } catch (e: IOException) {
                if (closed) return
                // One failure isn't the end; silence is.
                checkQuiet()
                Thread.sleep(20)
                continue
            }
            val p = InputPacket.decode(buf, packet.length) ?: continue
            if (p.session != session || p.from !in seats || p.from == localPlayer) continue
            lastHeard[p.from] = System.nanoTime()
            val current = synchronized(deliverLock) {
                // From the previous game (still in flight) or the next one
                // (that phone was seated first): not this game's inputs.
                if (p.epoch != (epoch and 0xFF) || p.from !in lockstep.partners) return@synchronized false
                theirAdvantage[p.from] = p.advantage
                theirDelay[p.from] = p.delay
                lockstep.receive(p.from, p.start, p.masks, p.ack)
                if (p.sumFrame >= 0) check(p.sumFrame, p.sum, from = p.from)
                true
            }
            if (!current) continue
            deliver(p.from)
            checkQuiet()
        }
    }

    // A partner silent for long is gone: the owner unseats them; a player
    // whose owner went quiet looks for the next one.
    private fun checkQuiet() {
        val now = System.nanoTime()
        val gone = lockstep.partners.filter { now - lastHeard[it] > QUIET_NS }
        if (gone.isEmpty()) return
        if (isOwner) {
            for (p in gone) {
                lastHeard[p] = now
                worker.execute { unseat(p, "${nameOf(p)} lost the connection") }
            }
        } else if (owner in gone) {
            lastHeard[owner] = now
            ownerLine?.let { ownerGone(it, "lost the connection") }
        }
    }

    /** New local inputs from [sample], for game [epoch]: frames from [start]. */
    class Sample(val epoch: Int, val start: Int, val masks: IntArray)

    /**
     * Samples our input for every frame up to [emulated] + delay and sends
     * it. Under the seating lock: the inputs are all of one game, either the
     * one before a seating or the one after.
     */
    fun sample(emulated: Int, input: () -> Int): Sample {
        val (game, new) = synchronized(deliverLock) {
            val ls = lockstep
            val new = ls.sampleUpTo(emulated, input)
            ls.delay = tuner.update(ls.delay, partnerAdvantage)
            epoch to new
        }
        if (new.second.isNotEmpty()) flush()
        return Sample(game, new.first, new.second)
    }

    // ----- Pause, rematch, leaving -----

    /** Tells everyone our menu opened or closed. */
    fun sendPause(paused: Boolean) = control(if (paused) Msg.PAUSE else Msg.RESUME)

    /** Restarts the game for everyone from the state the room started from (a network call: off the main thread). */
    fun requestRematch() {
        if (isOwner) worker.execute { rematch() } else control(Msg.REMATCH)
    }

    private fun control(type: Byte) {
        val payload = byteArrayOf(localPlayer.toByte())
        if (isOwner) {
            synchronized(lines) { lines.values.toList() }.forEach { runCatching { it.output.message(type, payload) } }
        } else {
            ownerLine?.let { runCatching { it.output.message(type, payload) } }
        }
    }

    /** Leaves the room: the others carry on. Safe on the main thread (the goodbyes go on a thread of their own). */
    override fun close() {
        if (closed) return
        closed = true
        beacon?.close()
        worker.shutdownNow()
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
        val bye = byteArrayOf(localPlayer.toByte())
        val all = synchronized(lines) { lines.values.toList() } + listOfNotNull(ownerLine)
        Thread({
            for (line in all) {
                runCatching { line.output.message(Msg.BYE, bye) }
                line.close()
            }
            runCatching { server.close() }
            runCatching { udp.close() }
        }, "net-close").apply {
            isDaemon = true
            start()
        }
    }

    // ----- The owner's side -----

    private fun startBeacon() {
        if (beaconPort < 0 || beacon != null) return
        beacon = runCatching {
            BeaconSender(beaconPort) {
                Beacon(PROTOCOL, name, set, title, rom, server.localPort, players = seats.size.coerceAtLeast(1), maxPlayers = maxPlayers, code = code)
            }
        }.getOrNull()
    }

    // Everyone the owner talks to: seated players and newcomers.
    private fun ownerLines(): List<Line> = synchronized(lines) { lines.values.toList() }

    /** Messages from one player to the owner (on the owner). */
    private fun ownerLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                val from = line.player
                when (t) {
                    Msg.READY -> {
                        synchronized(lines) { arriving[from]?.ready = true }
                        worker.execute { seat(news = "${line.name} joined as player ${from + 1}") }
                    }
                    Msg.BYE -> {
                        worker.execute { unseat(from, "${line.name} left") }
                        return
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        paused[from] = t == Msg.PAUSE
                        for (other in ownerLines()) if (other !== line) runCatching { other.output.message(t, payload) }
                        listener?.onPartnerPaused(from, t == Msg.PAUSE)
                    }
                    Msg.REMATCH -> worker.execute { rematch() }
                    Msg.RESYNC -> worker.execute { resync() }
                }
            }
        } catch (e: IOException) {
            if (!closed) worker.execute { unseat(line.player, "${line.name} left") }
        }
    }

    /** Seats everyone: newcomers in, [gone] out; everyone carries on from the owner's game (on the worker). */
    private fun seat(news: String?, gone: Int? = null, fromStart: Boolean = false) {
        if (closed || !isOwner) return
        val ln = listener ?: return
        val newcomers = synchronized(lines) {
            arriving.filterValues { it.ready }.also { ready -> ready.keys.forEach(arriving::remove) }
        }
        val next = seats.toMutableMap()
        if (gone != null) next.remove(gone)
        next[localPlayer] = Seat(localPlayer, name, "", udp.localPort, server.localPort)
        for ((p, a) in newcomers) {
            next[p] = Seat(p, a.line.name, a.line.socket.inetAddress.hostAddress, a.hello.udpPort, a.hello.tcpPort)
        }
        // Players whose connection is gone aren't seated.
        val live = ownerLines().associateBy { it.player }
        next.keys.retainAll { it == localPlayer || it in live }
        val state = if (fromStart) startState else null
        val sent = state ?: runCatching { ln.captureState() }.getOrElse { return }
        val nextEpoch = epoch + 1
        for (p in next.keys) {
            if (p == localPlayer) continue
            val line = live[p] ?: continue
            // Each player sees the owner at the address it reaches it on.
            val seatsFor = next.values.map {
                if (it.player == localPlayer) it.copy(host = line.socket.localAddress.hostAddress) else it
            }
            try {
                line.output.message(Msg.SEATING, encode(Seating(nextEpoch, localPlayer, seatsFor)))
                line.output.message(Msg.STATE, sent)
            } catch (e: IOException) {
                line.close()
            }
        }
        apply(Seating(nextEpoch, localPlayer, next.values.toList()), if (fromStart) state else null)
        if (news != null) ln.onNews(news)
    }

    private fun unseat(player: Int, news: String) {
        if (player == localPlayer || closed) return
        val line = synchronized(lines) {
            arriving.remove(player)
            lines.remove(player)
        }
        line?.close()
        if (player in seats) seat(news, gone = player) else if (line != null) listener?.onNews(news)
    }

    private fun resync() {
        // Both sides notice the same difference: one seating fixes it.
        val now = System.nanoTime()
        if (now - lastResync < 3_000_000_000L) return
        lastResync = now
        seat("Back in sync")
    }

    private fun rematch() {
        // Two players tapping Rematch at once mean one rematch.
        val now = System.nanoTime()
        if (now - lastRematch < 2_000_000_000L || startState == null) return
        lastRematch = now
        seat("Rematch", fromStart = true)
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            thread("net-hello") { greet(socket) }
        }
    }

    /** A new connection: someone joining (or asking what's here), or a new owner taking over. */
    private fun greet(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 120_000
            val line = Line(socket, -1, "")
            val (t, payload) = line.input.message()
            when (t) {
                Msg.TAKEOVER -> takeover(line, decode(payload))
                Msg.HELLO -> welcome(line, decode(payload))
                else -> socket.close()
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun welcome(line: Line, hello: Hello) {
        val out = line.output
        fun refuse(reason: String) {
            out.message(Msg.WELCOME, encode(Welcome(false, reason)))
            line.close()
        }
        if (hello.set == PROBE) {
            out.message(Msg.WELCOME, encode(Welcome(false, PROBE, name = name, set = set, title = title, rom = rom, maxPlayers = maxPlayers, code = code)))
            line.close()
            return
        }
        if (!isOwner) return refuse("This phone isn't running the room any more. Look for it again.")
        when {
            hello.protocol != PROTOCOL -> return refuse("Different app versions. Update every phone.")
            hello.set != set -> return refuse("The room is playing a different game.")
            hello.rom != rom -> return refuse("Your copy of the game differs from the host's. Download it from the library on every phone.")
            hello.bios != bios -> return refuse("Your Neo Geo BIOS differs from the host's. Download it from the library on every phone.")
            freeSeat() == null -> return refuse("The room is full.")
        }
        // Ask on the owner's screen; the newcomer waits.
        val answer = CountDownLatch(1)
        var yes = false
        listener?.onJoinRequest(hello.name) { yes = it; answer.countDown() } ?: return refuse("The room isn't ready yet.")
        if (!answer.await(120, TimeUnit.SECONDS) || !yes) return refuse("The host said no.")
        val seat = synchronized(lines) {
            freeSeat()?.also { p ->
                line.player = p
                line.name = hello.name
                lines[p] = line
                arriving[p] = Arriving(line, hello)
            }
        } ?: return refuse("The room is full.")
        line.socket.soTimeout = 0
        out.message(Msg.WELCOME, encode(Welcome(true, name = name, delay = startDelay, session = session, player = seat, maxPlayers = maxPlayers, owner = localPlayer, code = code)))
        out.message(Msg.START_STATE, startState ?: ByteArray(0))
        thread("net-tcp-${seat}") { ownerLoop(line) }
    }

    private fun freeSeat(): Int? = synchronized(lines) {
        (0 until maxPlayers).firstOrNull { it != localPlayer && it !in seats && it !in lines }
    }

    // ----- A player's side -----

    /** Messages from the owner (on a player). */
    private fun memberLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                val from = payload.firstOrNull()?.toInt() ?: owner
                when (t) {
                    Msg.SEATING -> {
                        val seating = decode<Seating>(payload)
                        val (t2, state) = line.input.message()
                        if (t2 != Msg.STATE) throw IOException("Expected the game state")
                        apply(seating, state)
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        paused[from] = t == Msg.PAUSE
                        listener?.onPartnerPaused(from, t == Msg.PAUSE)
                    }
                    Msg.BYE -> {
                        ownerGone(line)
                        return
                    }
                }
            }
        } catch (e: IOException) {
            ownerGone(line)
        }
    }

    /** Game [Seating.epoch] starts with these seats, from [state] (null: the game as it stands). */
    private fun apply(seating: Seating, state: ByteArray?) {
        synchronized(deliverLock) {
            val ln = listener
            val next = seating.seats.associateBy { it.player }
            seats = next
            owner = seating.owner
            epoch = seating.epoch
            val partners = next.keys.filter { it != localPlayer }.sorted()
            ln?.onSeating(epoch, next.keys.fold(0) { m, p -> m or (1 shl p) }, state)
            lockstep = Lockstep(lockstep.delay, localPlayer, maxOf(2, maxPlayers), partners)
            latestSum = null
            synchronized(mySums) {
                mySums.clear()
                theirSums.clear()
            }
            advantage.fill(0)
            theirAdvantage.fill(0)
            paused.fill(false)
            val now = System.nanoTime()
            for (p in partners) lastHeard[p] = now
        }
    }

    /** The owner's [line] ended: the player in the lowest seat takes the room over. */
    private fun ownerGone(line: Line, why: String = "left") {
        val old: Int
        val rest: List<Int>
        synchronized(ownerLock) {
            // A new owner may have called first: then the old line ending is old news.
            if (closed || line !== ownerLine || !migrating.compareAndSet(false, true)) return
            old = owner
            line.close()
            ownerLine = null
            rest = seats.keys.filter { it != old }.sorted()
            if (rest.firstOrNull().let { it != null && it != localPlayer }) takenOver = CountDownLatch(1)
        }
        val news = "${nameOf(old)} $why"
        val next = rest.firstOrNull()
        if (next == null || next == localPlayer) {
            worker.execute {
                becomeOwner(old, rest.filter { it != localPlayer }, news)
                migrating.set(false)
            }
        } else {
            // Wait for the next owner to call.
            thread("net-wait-owner") {
                if (!takenOver.await(TAKEOVER_MS, TimeUnit.MILLISECONDS) && !closed) {
                    migrating.set(false)
                    listener?.onClosed("$news, and nobody took the room over")
                }
            }
        }
    }

    /** This phone owns the room now: it calls the others and seats everyone without [old]. */
    private fun becomeOwner(old: Int, others: List<Int>, news: String) {
        owner = localPlayer
        for (p in others) {
            val seat = seats[p] ?: continue
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(seat.host, seat.tcpPort), 5_000)
                socket.tcpNoDelay = true
                val line = Line(socket, p, seat.name)
                line.output.message(Msg.TAKEOVER, encode(Takeover(session, localPlayer)))
                synchronized(lines) { lines[p] = line }
                thread("net-tcp-$p") { ownerLoop(line) }
            } catch (e: IOException) {
                // Unreachable: not seated in the next game.
            }
        }
        startBeacon()
        seat("$news. You're the host now", gone = old)
    }

    /** A new owner calls this phone. */
    private fun takeover(line: Line, takeover: Takeover) {
        if (takeover.session != session) {
            line.close()
            return
        }
        line.player = takeover.owner
        line.name = nameOf(takeover.owner)
        line.socket.soTimeout = 0
        synchronized(ownerLock) {
            ownerLine = line
            owner = takeover.owner
            migrating.set(false)
            takenOver.countDown()
        }
        listener?.onNews("${line.name} is the host now")
        memberLoop(line)
    }

    // ----- Plumbing -----

    private val deliverLock = Any()

    // One lock keeps each partner's inputs reaching the emulator in order.
    private fun deliver(from: Int) {
        synchronized(deliverLock) {
            val (start, masks) = lockstep.takeRemote(from)
            if (masks.isNotEmpty()) listener?.onRemote(from, start, masks)
        }
    }

    // Resends until acknowledged, and keeps the line alive when nothing changes.
    private fun sendLoop() {
        while (!closed) {
            val now = synchronized(sendSignal) {
                if (!sendNow) {
                    try {
                        sendSignal.wait(8)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                sendNow.also { sendNow = false }
            }
            val ls = lockstep
            val pending = ls.partners.any { ls.outgoing(it, 1).second.isNotEmpty() }
            val idle = System.nanoTime() - lastSent
            if (now || (pending && idle > RESEND_NS) || idle > KEEPALIVE_NS) send()
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        val safe = {
            try {
                body()
            } catch (e: InterruptedException) {
                // Closing.
            } catch (e: Throwable) {
                if (!closed) listener?.onNews("Connection error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val t = Thread(safe, name).apply {
            isDaemon = true
            // Inputs go out and come in the moment they're ready, even while
            // the phone is busy drawing the game.
            priority = Thread.MAX_PRIORITY
        }
        synchronized(threads) { threads += t }
        t.start()
    }

    companion object {
        private const val RESEND_NS = 12_000_000L
        private const val KEEPALIVE_NS = 100_000_000L
        private const val QUIET_NS = 5_000_000_000L
        private const val TAKEOVER_MS = 8_000L

        private fun openServer(port: Int): ServerSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            // Taken (another app, or a test): any free port; beacons say which.
            ServerSocket(0)
        }

        /**
         * Opens a room for [set] with [maxPlayers] seats, this phone in seat
         * 0. Others find it by its beacon ([beaconPort] -1: none, for tests).
         */
        fun host(
            name: String, set: String, title: String, rom: Long, bios: Long, maxPlayers: Int,
            delay: Int = 1, port: Int = TCP_PORT, beaconPort: Int = BEACON_PORT,
        ): Room = Room(
            name, set, title, rom, bios, maxPlayers, localPlayer = 0, session = Random.nextInt(),
            server = openServer(port), udp = DatagramSocket(0), owner = 0, startDelay = delay, beaconPort = beaconPort,
        )

        /**
         * Asks to join the room at [host]:[port]; blocks until the owner says
         * yes (then the room's start state is in [Room.startState]) or no
         * ([Refused], with the reason).
         */
        fun join(
            host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long,
            listenPort: Int = TCP_PORT, beaconPort: Int = BEACON_PORT,
        ): Room {
            val udp = DatagramSocket(0)
            // Where a new owner would call this phone, should the owner leave.
            val server = openServer(listenPort)
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.tcpNoDelay = true
                val line = Line(socket, 0, "Host")
                line.output.message(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, udp.localPort, server.localPort)))
                // The owner may take a while to tap Accept.
                socket.soTimeout = 0
                val (t, payload) = line.input.message()
                if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
                val w = decode<Welcome>(payload)
                if (!w.accepted) throw Refused(w.reason)
                line.player = w.owner
                line.name = w.name
                val start = line.input.message()
                if (start.first != Msg.START_STATE) throw IOException("Expected the start state")
                val room = Room(
                    name, set, "", rom, bios, w.maxPlayers, localPlayer = w.player, session = w.session,
                    server = server, udp = udp, owner = w.owner, startDelay = w.delay, beaconPort = beaconPort,
                )
                room.ownerLine = line
                room.startState = start.second.takeIf { it.isNotEmpty() }
                // Until the first seating: who's who, for messages.
                room.seats = mapOf(w.owner to Seat(w.owner, w.name, host.hostAddress, 0, port))
                return room
            } catch (e: IOException) {
                runCatching { socket.close() }
                runCatching { server.close() }
                udp.close()
                throw e
            }
        }
    }
}

/** Asks the room at [host]:[port] which game it's running, as a [Beacon]. */
fun probe(host: InetAddress, port: Int = TCP_PORT): Beacon {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 5_000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.message(Msg.HELLO, encode(Hello(PROTOCOL, "", PROBE, 0, 0, 0)))
        val (t, payload) = input.message()
        if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
        val w = decode<Welcome>(payload)
        return Beacon(PROTOCOL, w.name, w.set, w.title, w.rom, port, maxPlayers = w.maxPlayers, code = w.code)
    }
}

/** Announces a hosted game on the local network (Wi-Fi or hotspot) once a second. */
class BeaconSender(private val port: Int = BEACON_PORT, private val beacon: () -> Beacon) : Closeable {
    constructor(beacon: Beacon, port: Int = BEACON_PORT) : this(port, { beacon })

    @Volatile private var running = true
    private val socket = DatagramSocket().apply { broadcast = true }
    private val thread = Thread({
        while (running) {
            // Fresh each time: the count of players in changes as they join.
            val bytes = encode(beacon())
            for (address in broadcastAddresses()) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, port)) }
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }, "net-beacon").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        running = false
        thread.interrupt()
        socket.close()
    }
}

/** Hears hosts' beacons. [onHost] gets each beacon and where it came from. */
class BeaconListener(port: Int = BEACON_PORT, private val onHost: (Beacon, InetAddress) -> Unit) : Closeable {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(port))
    }
    private val thread = Thread({
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (!socket.isClosed) {
            try {
                socket.receive(packet)
                val beacon = runCatching { decode<Beacon>(buf.copyOf(packet.length)) }.getOrNull() ?: continue
                if (beacon.protocol == PROTOCOL) onHost(beacon, packet.address)
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
        }
    }, "net-listen").apply {
        isDaemon = true
        start()
    }

    override fun close() = socket.close()
}

/** Where to send beacons: each network's broadcast address, plus the all-networks one. */
fun broadcastAddresses(): List<InetAddress> {
    val out = LinkedHashSet<InetAddress>()
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            nif.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
        }
    }
    out += InetAddress.getByName("255.255.255.255")
    return out.toList()
}

/** This phone's addresses on local networks, to show for joining by address. */
fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
}.getOrDefault(emptyList())
