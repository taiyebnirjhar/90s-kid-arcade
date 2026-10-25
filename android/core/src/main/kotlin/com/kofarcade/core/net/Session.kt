package com.kofarcade.core.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random

// Rooms: phones on the same Wi-Fi or hotspot playing one game. The room has
// a seat per player the game takes (Cadillacs and Dinosaurs: three), and up
// to MAX_MEMBERS phones in all: the rest watch. Anyone with the room's key
// comes in and watches; a watcher takes a free seat with COIN or START (the
// first to ask gets it). Anyone may leave: their seat empties and the game
// goes on. If the owner (the phone that runs the room) leaves, the next one
// takes the room over.
//
// Who is in and who sits where changes only at a "seating": the owner stops
// the game, sends everyone its state and the new seats, and all carry on
// from that state as the next game (epoch). Between seatings nothing changes,
// so the rollback netcode stays as simple as for a fixed match. A seating
// also puts phones whose games went out of step back in step.

class Refused(reason: String) : IOException(reason)

/** Phones in a room at most: players and watchers. */
const val MAX_MEMBERS = 8

/** What the game screen hears from its room. Called on network threads. */
interface RoomListener {
    /** [player]'s inputs (a seat) for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(player: Int, start: Int, masks: IntArray)

    /** The games differed at [frame]: the room puts everyone back in step from the owner's game (a seating). */
    fun onDesync(frame: Int)

    /** This phone is out of the room (it lost everyone): the game can't go on together. */
    fun onClosed(reason: String)

    /** A player opened (true) or closed (false) their menu: the game waits for them. */
    fun onPartnerPaused(player: Int, paused: Boolean) {}

    /** Owner: stop the game and return its state, to seat everyone from. [onSeating] follows. */
    fun captureState(): ByteArray

    /**
     * Game [epoch] starts: the game restarts its rollback from frame 0 with
     * these seats ([activeMask]: bit p = seat p taken; [Room.localPlayer]:
     * this phone's, -1 if it watches), from [state] (null: the game as it
     * stands, on the owner). Runs before any of the new game's inputs are
     * handed out; return once loaded.
     */
    fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?)

    /** Something to tell the player: "Rafi joined", "You're the host now". */
    fun onNews(message: String) {}
}

/** A TCP connection to another phone of the room ([id]: its member id, -1 before it has one). */
internal class Line(val socket: Socket, @Volatile var id: Int, @Volatile var name: String) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun close() = runCatching { socket.close() }
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
    /** This phone's member id: it keeps it for as long as it's in the room. */
    val localId: Int,
    private val session: Int,
    private val server: ServerSocket,
    private val udp: DatagramSocket,
    owner: Int,
    private val startDelay: Int,
    private val beaconPort: Int,
) : Closeable {
    /** The member id of the phone that runs the room: it seats everyone and runs every change. */
    @Volatile var owner: Int = owner
        private set

    val isOwner: Boolean get() = owner == localId

    /** Everyone in the room, this phone included, by member id. */
    @Volatile var members: Map<Int, Member> = emptyMap()
        private set

    /** The players, by seat (0 = player 1). */
    val seats: Map<Int, Member> get() = members.values.filter { it.seat >= 0 }.associateBy { it.seat }

    /** This phone's seat (0 = player 1); -1 while it watches. */
    val localPlayer: Int get() = members[localId]?.seat ?: -1

    /** This phone watches rather than plays. */
    val watching: Boolean get() = localPlayer < 0

    /** Phones watching. */
    val watcherCount: Int get() = members.values.count { it.seat < 0 }

    /** A seat nobody's in. */
    val hasFreeSeat: Boolean get() = seats.size < maxPlayers

    /** What friends type to join ("K7Q-2M"); empty if the owner's address can't make one. */
    @Volatile var key: String = ""
        private set

    /** The state the room started from, for a restart (the owner sends it to newcomers). */
    @Volatile var startState: ByteArray? = null

    /** This game's inputs; replaced at each seating. */
    @Volatile var lockstep = Lockstep(startDelay, -1, players(), emptyList())
        private set

    /** Which game of the room this is: +1 at each seating. */
    @Volatile var epoch = 0
        private set

    /** This phone's input delay now: it adapts to the connection ([DelayTuner]). */
    val delay: Int get() = lockstep.delay
    private val tuner = DelayTuner()

    /** Seats taken, as a bit mask (bit p = seat p). */
    val activeMask: Int get() = seats.keys.fold(0) { m, p -> m or (1 shl p) }

    @Volatile private var closed = false
    @Volatile private var listener: RoomListener? = null
    private val lastHeard = LongArray(MAX_IDS)
    @Volatile private var lastSent = 0L
    private val mySums = HashMap<Int, Long>()
    private val theirSums = HashMap<Int, MutableList<Long>>()
    @Volatile private var latestSum: Pair<Int, Long>? = null
    private val advantage = IntArray(MAX_IDS)
    private val theirAdvantage = IntArray(MAX_IDS)
    private val theirDelay = IntArray(MAX_IDS) { startDelay }
    private val paused = BooleanArray(MAX_IDS)
    private val threads = ArrayList<Thread>()

    // The owner's connections, one per other phone (newcomers too, before they're seated).
    private val lines = HashMap<Int, Line>()
    // Newcomers waiting to be seated (once their game is ready to load the state).
    private class Arriving(val line: Line, val hello: Hello, @Volatile var ready: Boolean = false)
    private val arriving = HashMap<Int, Arriving>()
    // A member's connection to the owner.
    @Volatile private var ownerLine: Line? = null
    // Seatings, joins and takeovers happen one at a time, here.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "net-room").apply { isDaemon = true } }
    @Volatile private var beacon: BeaconSender? = null
    @Volatile private var lastRematch = 0L
    @Volatile private var lastResync = 0L
    private val migrating = AtomicBoolean(false)
    private val ownerLock = Any()
    @Volatile private var takenOver = CountDownLatch(1)

    private fun players() = maxPlayers.coerceIn(2, 4)

    fun nameOf(id: Int): String = members[id]?.name ?: "Player"

    // The other players.
    private val partners: List<Member> get() = members.values.filter { it.seat >= 0 && it.id != localId }

    /** The other players' names, for messages: "Pixel 7" or "Pixel 7 & Galaxy S21". */
    val peerName: String get() = partners.joinToString(" & ") { it.name }.ifEmpty { "the others" }

    /** The partner furthest ahead of our inputs (they guess the most): what the delay tuner watches. */
    private val partnerAdvantage: Int get() = lockstep.partners.maxOfOrNull { theirAdvantage[it] } ?: 0

    /** Another player has their menu open. */
    val partnerPaused: Boolean get() = partners.any { paused[it.id] }

    /** Who has their menu open, for "X paused the game". */
    val pausedName: String? get() = partners.firstOrNull { paused[it.id] }?.name

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
        get() = if (!lockstep.playing) 0 else lockstep.partners.maxOfOrNull { p ->
            (advantage[p] + theirDelay[p]) - (theirAdvantage[p] + delay)
        } ?: 0

    /** How the connection is doing, for the pause menu: this game, and each other phone's inputs. */
    fun status(): String {
        val ls = lockstep
        val now = System.nanoTime()
        val me = if (watching) "watching" else "P${localPlayer + 1}"
        val others = members.values.filter { it.id != localId }.sortedBy { it.id }.joinToString(" · ") { m ->
            val who = if (m.seat >= 0) "P${m.seat + 1}" else "watcher"
            val heard = lastHeard[m.id].takeIf { it != 0L }?.let { "${(now - it) / 1_000_000} ms" } ?: "never"
            val inputs = if (m.id in ls.partners) " ${ls.known(m.id)} in," else ""
            "$who$inputs heard $heard"
        }
        return "Game ${epoch} · you: $me, frame ${ls.nextLocal}" + if (others.isNotEmpty()) " · $others" else ""
    }

    /**
     * Starts playing: the owner seats itself (and opens the room to others);
     * a newcomer tells the owner it's ready to be seated.
     */
    fun start(listener: RoomListener) {
        this.listener = listener
        thread("net-udp") { receiveLoop() }
        thread("net-send") { sendLoop() }
        thread("net-accept") { acceptLoop() }
        if (isOwner) {
            worker.execute { seat(news = null) }
            startBeacon()
        } else {
            val line = ownerLine ?: return
            thread("net-tcp-owner") { memberLoop(line) }
            // Until it's seated, now and then: the owner may have missed it.
            thread("net-ready") {
                while (!closed && epoch == 0) {
                    runCatching { ownerLine?.output?.message(Msg.READY, byteArrayOf(localId.toByte())) }
                    Thread.sleep(READY_MS)
                }
            }
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

    // Only ever on the send thread: every other phone of the game gets what
    // it's missing of our inputs (a watcher has none to send: it acknowledges,
    // and keeps the line alive).
    private fun send() {
        // The game and its number together: a seating between reading one and
        // the other would label the old game's inputs as the new one's.
        val (ls, game, sum) = synchronized(deliverLock) { Triple(lockstep, epoch, latestSum) }
        val now = members
        for (id in ls.partners + ls.watchers) {
            val m = now[id] ?: continue
            val (start, masks) = ls.outgoing(id, InputPacket.MAX_MASKS)
            val packet = InputPacket(
                session, ls.known(id), start, masks, sum?.first ?: -1, sum?.second ?: 0,
                advantage[id], game and 0xFF, ls.delay, localId,
            )
            val bytes = packet.encode()
            // A Wi-Fi hiccup can fail one send: the inputs go again with the
            // next packet, and a phone gone for good shows as silence.
            try {
                udp.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(m.host, m.udpPort)))
            } catch (e: Exception) {
                if (closed) return
            }
        }
        lastSent = System.nanoTime()
    }

    /** Our game's state checksum after [frame], compared with the others'. */
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
            runCatching { worker.execute { requestResync() } }
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
            if (p.session != session || p.from !in members || p.from == localId || p.from >= MAX_IDS) continue
            lastHeard[p.from] = System.nanoTime()
            val player = synchronized(deliverLock) {
                // From the previous game (still in flight) or the next one
                // (that phone was seated first): not this game's inputs.
                val ls = lockstep
                val inGame = p.from in ls.partners || p.from in ls.watchers
                if (p.epoch != (epoch and 0xFF) || !inGame) return@synchronized false
                theirAdvantage[p.from] = p.advantage
                theirDelay[p.from] = p.delay
                ls.receive(p.from, p.start, p.masks, p.ack)
                if (p.sumFrame >= 0) check(p.sumFrame, p.sum, from = p.from)
                p.from in ls.partners
            }
            if (player) deliver(p.from)
            checkQuiet()
        }
    }

    // A phone silent for long is gone: the owner lets it go; a member whose
    // owner went quiet looks for the next one.
    private fun checkQuiet() {
        val now = System.nanoTime()
        val ls = lockstep
        val gone = (ls.partners + ls.watchers).filter { now - lastHeard[it] > QUIET_NS }
        if (gone.isEmpty()) return
        if (isOwner) {
            for (id in gone) {
                lastHeard[id] = now
                worker.execute { unseat(id, "${nameOf(id)} lost the connection") }
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
     * it (watching: none). Under the seating lock: the inputs are all of one
     * game, either the one before a seating or the one after.
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

    // ----- Pause, restart, seats, leaving (network calls: off the main thread) -----

    /** Tells everyone our menu opened or closed (players only: a watcher's menu stops nobody). */
    fun sendPause(paused: Boolean) {
        if (!watching) control(if (paused) Msg.PAUSE else Msg.RESUME)
    }

    /** Restarts the game for everyone from the state the room started from. */
    fun requestRematch() {
        if (isOwner) worker.execute { rematch() } else control(Msg.REMATCH)
    }

    /** Watching: asks for a free seat (the first to ask gets it). */
    fun requestSeat() {
        if (!watching) return
        if (isOwner) worker.execute { sit(localId) } else control(Msg.SIT)
    }

    /** Puts everyone back in step from the owner's game: for a game that stopped. */
    fun requestResync() {
        if (isOwner) worker.execute { resync() } else control(Msg.RESYNC)
    }

    private fun control(type: Byte) {
        val payload = byteArrayOf(localId.toByte())
        if (isOwner) {
            synchronized(lines) { lines.values.toList() }.forEach { runCatching { it.output.message(type, payload) } }
        } else {
            ownerLine?.let { runCatching { it.output.message(type, payload) } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        beacon?.close()
        worker.shutdownNow()
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
        val bye = byteArrayOf(localId.toByte())
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
                Beacon(
                    PROTOCOL, name, set, title, rom, server.localPort,
                    players = seats.size.coerceAtLeast(1), maxPlayers = maxPlayers, key = key, watchers = watcherCount,
                )
            }
        }.getOrNull()
    }

    // Everyone the owner talks to: members and newcomers.
    private fun ownerLines(): List<Line> = synchronized(lines) { lines.values.toList() }

    /** Messages from one member to the owner (on the owner). */
    private fun ownerLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                val from = line.id
                when (t) {
                    Msg.READY -> {
                        val waiting = synchronized(lines) { arriving[from]?.also { it.ready = true } != null }
                        // Seated already but it didn't hear: once more, for everyone.
                        worker.execute { if (waiting) seat(news = "${line.name} joined") else if (from in members) resync() }
                    }
                    Msg.BYE -> {
                        worker.execute { unseat(from, "${line.name} left") }
                        return
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        paused[from] = t == Msg.PAUSE
                        for (other in ownerLines()) if (other !== line) runCatching { other.output.message(t, payload) }
                        members[from]?.let { listener?.onPartnerPaused(it.seat, t == Msg.PAUSE) }
                    }
                    Msg.REMATCH -> worker.execute { rematch() }
                    Msg.RESYNC -> worker.execute { resync() }
                    Msg.SIT -> worker.execute { sit(from) }
                }
            }
        } catch (e: IOException) {
            if (!closed) worker.execute { unseat(line.id, "${line.name} left") }
        }
    }

    /**
     * Seats everyone (on the worker): newcomers in (watching), [gone] out,
     * [sit] (member id to seat) into a seat; everyone carries on from the
     * owner's game, or from the start for a restart.
     */
    private fun seat(news: String?, gone: Int? = null, sit: Pair<Int, Int>? = null, fromStart: Boolean = false) {
        if (closed || !isOwner) return
        val ln = listener ?: return
        val newcomers = synchronized(lines) {
            arriving.filterValues { it.ready }.also { ready -> ready.keys.forEach(arriving::remove) }
        }
        val next = members.toMutableMap()
        if (gone != null) next.remove(gone)
        // The room's first seating: its owner is player 1.
        next[localId] = (members[localId] ?: Member(localId, name, "", 0, 0, seat = 0))
            .copy(name = name, host = "", udpPort = udp.localPort, tcpPort = server.localPort)
        for ((id, a) in newcomers) {
            next[id] = Member(id, a.line.name, a.line.socket.inetAddress.hostAddress, a.hello.udpPort, a.hello.tcpPort)
        }
        if (sit != null) next[sit.first]?.let { next[sit.first] = it.copy(seat = sit.second) }
        // Phones whose connection is gone aren't in.
        val live = ownerLines().associateBy { it.id }
        next.keys.retainAll { it == localId || it in live }
        val state = if (fromStart) startState else null
        val sent = state ?: runCatching { ln.captureState() }.getOrElse { return }
        val packed = pack(sent)
        val nextEpoch = epoch + 1
        for (id in next.keys) {
            if (id == localId) continue
            val line = live[id] ?: continue
            // Each phone sees the owner at the address it reaches it on.
            val membersFor = next.values.map {
                if (it.id == localId) it.copy(host = line.socket.localAddress.hostAddress) else it
            }
            try {
                line.output.message(Msg.SEATING, encode(Seating(nextEpoch, localId, membersFor)))
                line.output.message(Msg.STATE, packed)
            } catch (e: IOException) {
                line.close()
            }
        }
        apply(Seating(nextEpoch, localId, next.values.toList()), if (fromStart) state else null)
        if (news != null) ln.onNews(news)
    }

    /** A watcher asks for a seat: the lowest free one, if any (on the worker, so the first to ask gets it). */
    private fun sit(id: Int) {
        if (closed || !isOwner) return
        val m = members[id] ?: return
        if (m.seat >= 0) return
        val taken = members.values.map { it.seat }.toSet()
        val free = (0 until maxPlayers).firstOrNull { it !in taken }
        if (free == null) {
            tell(id, "No free seat: someone took it first")
            return
        }
        seat("${m.name} is player ${free + 1}", sit = id to free)
    }

    // Tells one member something.
    private fun tell(id: Int, message: String) {
        if (id == localId) {
            listener?.onNews(message)
            return
        }
        val line = synchronized(lines) { lines[id] } ?: return
        runCatching { line.output.message(Msg.NEWS, message.toByteArray()) }
    }

    private fun unseat(id: Int, news: String) {
        if (id == localId || closed) return
        val line = synchronized(lines) {
            arriving.remove(id)
            lines.remove(id)
        }
        line?.close()
        if (id in members) seat(news, gone = id)
    }

    private fun resync() {
        // Every phone notices the same difference: one seating fixes it.
        val now = System.nanoTime()
        if (now - lastResync < 3_000_000_000L) return
        lastResync = now
        seat(null)
    }

    private fun rematch() {
        // Two players tapping Restart at once mean one restart.
        val now = System.nanoTime()
        if (now - lastRematch < 2_000_000_000L || startState == null) return
        lastRematch = now
        seat("Restarted", fromStart = true)
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
            socket.soTimeout = 30_000
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

    // Anyone with the room's game comes in, to watch until they take a seat.
    private fun welcome(line: Line, hello: Hello) {
        val out = line.output
        fun refuse(reason: String) {
            out.message(Msg.WELCOME, encode(Welcome(false, reason)))
            line.close()
        }
        if (hello.set == PROBE) {
            out.message(
                Msg.WELCOME,
                encode(
                    Welcome(
                        false, PROBE, name = name, set = set, title = title, rom = rom, maxPlayers = maxPlayers, key = key,
                        players = seats.size, watchers = watcherCount,
                    ),
                ),
            )
            line.close()
            return
        }
        if (!isOwner) return refuse("This phone isn't running the room any more. Look for it again.")
        when {
            hello.protocol != PROTOCOL -> return refuse("Different app versions. Update every phone.")
            hello.set != set -> return refuse("The room is playing a different game.")
            hello.rom != rom -> return refuse("Your copy of the game differs from the host's. Download it from the library on every phone.")
            hello.bios != bios -> return refuse("Your Neo Geo BIOS differs from the host's. Download it from the library on every phone.")
        }
        val id = synchronized(lines) {
            freeId()?.also { id ->
                line.id = id
                line.name = hello.name
                lines[id] = line
                arriving[id] = Arriving(line, hello)
            }
        } ?: return refuse("The room is full: $MAX_MEMBERS phones at most.")
        line.socket.soTimeout = 0
        out.message(Msg.WELCOME, encode(Welcome(true, name = name, delay = startDelay, session = session, id = id, maxPlayers = maxPlayers, owner = localId, key = key)))
        out.message(Msg.START_STATE, pack(startState ?: ByteArray(0)))
        thread("net-tcp-$id") { ownerLoop(line) }
    }

    // A member id for a newcomer, if the room has space.
    private fun freeId(): Int? = synchronized(lines) {
        val used = members.keys + lines.keys + localId
        if (used.size >= MAX_MEMBERS) null else (0 until MAX_IDS).firstOrNull { it !in used }
    }

    // ----- A member's side -----

    /** Messages from the owner (on a member). */
    private fun memberLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                when (t) {
                    Msg.SEATING -> {
                        val seating = decode<Seating>(payload)
                        val (t2, state) = line.input.message()
                        if (t2 != Msg.STATE) throw IOException("Expected the game state")
                        apply(seating, unpack(state))
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        val from = payload.firstOrNull()?.toInt() ?: owner
                        if (from in 0 until MAX_IDS) paused[from] = t == Msg.PAUSE
                        members[from]?.let { listener?.onPartnerPaused(it.seat, t == Msg.PAUSE) }
                    }
                    Msg.NEWS -> listener?.onNews(String(payload))
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

    /** Game [Seating.epoch] starts with these members, from [state] (null: the game as it stands). */
    private fun apply(seating: Seating, state: ByteArray?) {
        synchronized(deliverLock) {
            val ln = listener
            val next = seating.members.associateBy { it.id }
            members = next
            owner = seating.owner
            epoch = seating.epoch
            val others = next.values.filter { it.id != localId }
            val partners = others.filter { it.seat >= 0 }.map { it.id }.sorted()
            val watchers = others.filter { it.seat < 0 }.map { it.id }.sorted()
            val playing = (next[localId]?.seat ?: -1) >= 0
            ln?.onSeating(epoch, activeMask, state)
            lockstep = Lockstep(lockstep.delay, if (playing) localId else -1, players(), partners, watchers)
            latestSum = null
            synchronized(mySums) {
                mySums.clear()
                theirSums.clear()
            }
            advantage.fill(0)
            theirAdvantage.fill(0)
            paused.fill(false)
            val now = System.nanoTime()
            for (m in others) lastHeard[m.id] = now
        }
        key = keyNow()
    }

    // The room's key: the owner's address as this phone reaches it.
    private fun keyNow(): String = runCatching {
        if (isOwner) {
            lanAddress()?.let { roomKey(it, server.localPort) }
        } else {
            members[owner]?.let { o -> (InetAddress.getByName(o.host) as? Inet4Address)?.let { roomKey(it, o.tcpPort) } }
        }
    }.getOrNull() ?: ""

    /** The owner's [line] ended: the member with the lowest id takes the room over. */
    private fun ownerGone(line: Line, why: String = "left") {
        val old: Int
        val rest: List<Int>
        synchronized(ownerLock) {
            // A new owner may have called first: then the old line ending is old news.
            if (closed || line !== ownerLine || !migrating.compareAndSet(false, true)) return
            old = owner
            line.close()
            ownerLine = null
            rest = members.keys.filter { it != old }.sorted()
            if (rest.firstOrNull().let { it != null && it != localId }) takenOver = CountDownLatch(1)
        }
        val news = "${nameOf(old)} $why"
        val next = rest.firstOrNull()
        if (next == null || next == localId) {
            worker.execute {
                becomeOwner(old, rest.filter { it != localId }, news)
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
        owner = localId
        for (id in others) {
            val m = members[id] ?: continue
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(m.host, m.tcpPort), 5_000)
                socket.tcpNoDelay = true
                val line = Line(socket, id, m.name)
                line.output.message(Msg.TAKEOVER, encode(Takeover(session, localId)))
                synchronized(lines) { lines[id] = line }
                thread("net-tcp-$id") { ownerLoop(line) }
            } catch (e: IOException) {
                // Unreachable: not in the next game.
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
        line.id = takeover.owner
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

    // One lock keeps each player's inputs reaching the emulator in order.
    private fun deliver(from: Int) {
        synchronized(deliverLock) {
            val seat = members[from]?.seat ?: return
            if (seat < 0) return
            val (start, masks) = lockstep.takeRemote(from)
            if (masks.isNotEmpty()) listener?.onRemote(seat, start, masks)
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
            val pending = (ls.partners + ls.watchers).any { ls.outgoing(it, 1).second.isNotEmpty() }
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
        private const val READY_MS = 3_000L

        // The first free room port from [port] (0: any, for tests).
        private fun openServer(port: Int): ServerSocket {
            if (port != 0) {
                for (p in port until port + TCP_PORTS) {
                    try {
                        return ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(p))
                        }
                    } catch (e: IOException) {
                        // Taken: the next one.
                    }
                }
            }
            return ServerSocket(0)
        }

        /**
         * Opens a room for [set] with [maxPlayers] seats, this phone in seat
         * 0. Others find it by its key, or its beacon ([beaconPort] -1: none, for tests).
         */
        fun host(
            name: String, set: String, title: String, rom: Long, bios: Long, maxPlayers: Int,
            delay: Int = 1, port: Int = TCP_PORT, beaconPort: Int = BEACON_PORT,
        ): Room = Room(
            name, set, title, rom, bios, maxPlayers, localId = 0, session = Random.nextInt(),
            server = openServer(port), udp = DatagramSocket(0), owner = 0, startDelay = delay, beaconPort = beaconPort,
        ).also { it.key = it.keyNow() }

        /**
         * Joins the room at [host]:[port], to watch until a seat is taken
         * ([requestSeat]); the room's start state is then in
         * [Room.startState]. [Refused] (with the reason) if it can't.
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
                socket.soTimeout = 30_000
                val line = Line(socket, 0, "Host")
                line.output.message(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, udp.localPort, server.localPort)))
                val (t, payload) = line.input.message()
                if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
                val w = decode<Welcome>(payload)
                if (!w.accepted) throw Refused(w.reason)
                val start = line.input.message()
                if (start.first != Msg.START_STATE) throw IOException("Expected the start state")
                socket.soTimeout = 0
                line.id = w.owner
                line.name = w.name
                val room = Room(
                    name, set, "", rom, bios, w.maxPlayers, localId = w.id, session = w.session,
                    server = server, udp = udp, owner = w.owner, startDelay = w.delay, beaconPort = beaconPort,
                )
                room.ownerLine = line
                room.startState = unpack(start.second).takeIf { it.isNotEmpty() }
                // Until the first seating: who's who, for messages.
                room.members = mapOf(w.owner to Member(w.owner, w.name, host.hostAddress, 0, port, seat = 0))
                room.key = w.key
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

// Game states are mostly zeros: squeezed, they cross Wi-Fi many times faster.
internal fun pack(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream(data.size / 4 + 64)
    DataOutputStream(out).writeInt(data.size)
    val buf = ByteArray(64 * 1024)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    deflater.end()
    return out.toByteArray()
}

internal fun unpack(data: ByteArray): ByteArray {
    if (data.size < 4) return ByteArray(0)
    val size = DataInputStream(data.inputStream()).readInt()
    if (size < 0 || size > 64 shl 20) throw IOException("Bad state size $size")
    val inflater = Inflater()
    inflater.setInput(data, 4, data.size - 4)
    val out = ByteArray(size)
    var n = 0
    while (n < size && !inflater.finished()) {
        val got = inflater.inflate(out, n, size - n)
        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        n += got
    }
    inflater.end()
    if (n != size) throw IOException("Bad game state")
    return out
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
        if (w.reason != PROBE) throw Refused(w.reason)
        return Beacon(PROTOCOL, w.name, w.set, w.title, w.rom, port, players = w.players, maxPlayers = w.maxPlayers, key = w.key, watchers = w.watchers)
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
