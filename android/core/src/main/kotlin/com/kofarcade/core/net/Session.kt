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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random

// Rooms: phones on the same Wi-Fi or hotspot playing one game. The room has
// a seat per player the game takes (Cadillacs and Dinosaurs: three), and up
// to MAX_MEMBERS phones in all: the rest watch. Anyone with the room's key
// comes in and watches; a watcher takes a seat with COIN or START (the first
// request to reach the owner gets it): a free seat, or one whose player has
// pressed nothing for IDLE_SECONDS (they go back to watching). Anyone may
// leave: their seat empties and the game goes on. If the owner (the phone
// that runs the room) leaves, the next one takes the room over.
//
// Who plays changes only at a "seating": the owner stops the game, sends
// everyone its state and the new seats, and all carry on from that state as
// the next game (epoch). Between seatings the players are fixed, so the
// rollback netcode stays as simple as for a fixed match. A seating also
// puts phones whose games went out of step back in step.
//
// Watchers never hold the players up. They come and go without a seating:
// the owner sends a newcomer the game at the newest frame every input is in
// for (taken from the emulator's rollback states, without stopping it), then
// relays the players' inputs from there over that watcher's own connection,
// from a thread of its own. The players send nothing to watchers, and a
// slow watcher only falls behind itself (and asks for the game again).

class Refused(reason: String) : IOException(reason)

/** Phones in a room at most: players and watchers. */
const val MAX_MEMBERS = 8

/** A player who presses nothing for this long can be replaced by a watcher. */
const val IDLE_SECONDS = 20

/** What the game screen hears from its room. Called on network threads. */
interface RoomListener {
    /** [player]'s inputs (a seat) for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(player: Int, start: Int, masks: IntArray)

    /** The games differed at [frame]: the room puts everyone back in step from the owner's game. */
    fun onDesync(frame: Int)

    /** This phone is out of the room (it lost everyone): the game can't go on together. */
    fun onClosed(reason: String)

    /** A player opened (true) or closed (false) their menu: the game waits for them. */
    fun onPartnerPaused(player: Int, paused: Boolean) {}

    /** Owner: stop the game and return its state, to seat everyone from. [onSeating] follows. */
    fun captureState(): ByteArray

    /**
     * Owner: the game at the newest frame every input is in for, without
     * stopping it, for a newcomer to watch from: (frame, state), or null if
     * there's none to give right now (it's asked again).
     */
    fun snapshot(): Pair<Int, ByteArray>? = null

    /**
     * Game [epoch] starts: the game restarts its rollback at [startFrame]
     * (0, or where a watcher comes in) with these seats ([activeMask]: bit p =
     * seat p taken; [Room.localPlayer]: this phone's, -1 if it watches), from
     * [state] (null: the game as it stands, on the owner). Runs before any of
     * the game's inputs are handed out; return once loaded.
     */
    fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?, startFrame: Int)

    /** Who's in changed (someone came to watch, or left) without a new game. */
    fun onRoster() {}

    /** Something to tell the player: "Rafi joined", "You're the host now". */
    fun onNews(message: String) {}
}

/** A TCP connection to another phone of the room ([id]: its member id, -1 before it has one). */
internal class Line(val socket: Socket, @Volatile var id: Int, @Volatile var name: String) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    @Volatile var lastHeard = System.nanoTime()

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
    private val build: String,
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

    /** This game's inputs exchanged over UDP; replaced at each seating. */
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

    // The owner's connections, one per other phone (newcomers too, before they watch).
    private val lines = HashMap<Int, Line>()
    // Newcomers let in, waiting for their game to be ready to load a state.
    private class Arriving(val line: Line, val hello: Hello)
    private val arriving = HashMap<Int, Arriving>()
    // The owner's feeds to watchers, by member id.
    private val feeds = HashMap<Int, Feed>()
    // A member's connection to the owner.
    @Volatile private var ownerLine: Line? = null
    // Seatings, joins and takeovers happen one at a time, here.
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "net-room").apply { isDaemon = true } }
    @Volatile private var beacon: BeaconSender? = null
    @Volatile private var lastRematch = 0L
    @Volatile private var lastResync = 0L
    @Volatile private var lastRewatch = 0L
    private val migrating = AtomicBoolean(false)
    private val ownerLock = Any()
    @Volatile private var takenOver = CountDownLatch(1)

    // This game's first frame on this phone: 0, or where it came in to watch.
    @Volatile private var startFrame = 0
    // Watching (not the owner): each seat's next frame of relayed inputs.
    private val relayed = IntArray(4)

    /** Frames of nothing pressed before a player's seat can be taken (shorter in tests). */
    @Volatile internal var idleLimit = IDLE_FRAMES

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

    /** Watching: the newest frame the players' inputs are all in for (relayed, or straight from them). */
    val watchedUpTo: Int
        get() = if (isOwner || !watching) lockstep.remoteKnown else seats.keys.minOfOrNull { relayed[it] } ?: Int.MAX_VALUE

    /** How the connection is doing, for the pause menu: this game, and each other phone. */
    fun status(): String {
        val ls = lockstep
        val now = System.nanoTime()
        val me = if (watching) "watching" else "P${localPlayer + 1}"
        val others = members.values.filter { it.id != localId }.sortedBy { it.id }.joinToString(" · ") { m ->
            val who = (if (m.seat >= 0) "P${m.seat + 1}" else "watcher") + if (m.id == owner) " (host)" else ""
            val heardAt = when {
                m.id in ls.partners || m.id in ls.watchers -> lastHeard[m.id]
                isOwner -> synchronized(lines) { lines[m.id] }?.lastHeard ?: 0L
                m.id == owner -> ownerLine?.lastHeard ?: 0L
                else -> 0L
            }
            val heard = if (heardAt == 0L) "" else " heard ${(now - heardAt) / 1_000_000} ms ago"
            val inputs = when {
                m.id in ls.partners -> " ${ls.known(m.id)} in,"
                watching && !isOwner && m.seat >= 0 -> " ${relayed[m.seat]} in,"
                else -> ""
            }
            "$who$inputs$heard"
        }
        return "Game $epoch · you: $me, frame ${if (watching) watchedUpTo.coerceAtMost(999_999) else ls.nextLocal}" +
            if (others.isNotEmpty()) " · $others" else ""
    }

    /**
     * Starts playing: the owner seats itself (and opens the room to others);
     * a newcomer tells the owner it's ready to watch.
     */
    fun start(listener: RoomListener) {
        this.listener = listener
        thread("net-udp") { receiveLoop() }
        thread("net-send") { sendLoop() }
        thread("net-accept") { acceptLoop() }
        thread("net-ping") { pingLoop() }
        if (isOwner) {
            worker.execute { seat(news = null) }
            startBeacon()
        } else {
            val line = ownerLine ?: return
            // Quiet while the game loaded is fine.
            line.lastHeard = System.nanoTime()
            thread("net-tcp-owner") { memberLoop(line) }
            // Until it's watching, now and then: the owner may have missed it.
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

    // Only ever on the send thread: every phone of the game we exchange
    // inputs with gets what it's missing of ours (an owner who watches
    // has none to send: it acknowledges, and keeps the line alive).
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
            // Back in step from the owner's game, without restarting anything:
            // a watcher alone, players all together. (On a thread: this can be the game's.)
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

    // A player silent for long is gone: the owner lets them go; a phone
    // whose owner went quiet looks for the next one.
    private fun checkQuiet() {
        val now = System.nanoTime()
        val ls = lockstep
        val gone = (ls.partners + ls.watchers).filter { now - lastHeard[it] > QUIET_NS }
        if (gone.isEmpty()) return
        if (isOwner) {
            for (id in gone) {
                lastHeard[id] = now
                // Closed now, so no write to it can hold the room up.
                synchronized(lines) { lines[id] }?.close()
                worker.execute { remove(id, "${nameOf(id)} lost the connection") }
            }
        } else if (owner in gone) {
            lastHeard[owner] = now
            ownerLine?.let { ownerGone(it, "lost the connection") }
        }
    }

    // Every second: tell the owner (or, owning, every player) we're here, and
    // notice a connection that's gone quiet (a watcher has no other sign).
    private fun pingLoop() {
        val ping = byteArrayOf(localId.toByte())
        while (!closed) {
            Thread.sleep(PING_MS)
            val now = System.nanoTime()
            if (isOwner) {
                val all = ownerLines()
                for (line in all) {
                    val newcomer = synchronized(lines) { line.id in arriving }
                    // A newcomer's phone may take a while to load the game.
                    val quiet = if (newcomer) ARRIVING_QUIET_NS else LINE_QUIET_NS
                    if (now - line.lastHeard > quiet) {
                        // Closed now: a write stuck on it (the worker's, say)
                        // fails at once instead of holding up the room.
                        line.lastHeard = now
                        line.close()
                        worker.execute { remove(line.id, "${line.name} lost the connection") }
                        continue
                    }
                    // Watchers hear from their feed.
                    if (line.id !in feedIds()) pingAsync(line, ping)
                }
            } else {
                val line = ownerLine ?: continue
                pingAsync(line, ping)
                if (now - line.lastHeard > LINE_QUIET_NS) {
                    line.lastHeard = now
                    ownerGone(line, "lost the connection")
                }
            }
        }
    }

    // A ping that can't hold the ping loop up: a line whose last ping is
    // still stuck (the phone stopped reading) gets no other.
    private val pinging = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Line, Boolean>())
    private val pinger = Executors.newCachedThreadPool { r -> Thread(r, "net-pinger").apply { isDaemon = true } }

    private fun pingAsync(line: Line, ping: ByteArray) {
        if (!pinging.add(line)) return
        runCatching {
            pinger.execute {
                try {
                    line.output.message(Msg.PING, ping)
                } catch (e: IOException) {
                    // Gone: the quiet check notices.
                } finally {
                    pinging.remove(line)
                }
            }
        }.onFailure { pinging.remove(line) }
    }

    /** New local inputs from [sample], for game [epoch]: frames from [start]; [emulated]: the game's next frame. */
    class Sample(val epoch: Int, val start: Int, val masks: IntArray, val emulated: Int = 0)

    /**
     * Samples our input for every frame up to [emulated] + delay and sends
     * it (watching: none). Under the seating lock: the inputs are all of one
     * game, either the one before a seating or the one after.
     */
    fun sample(emulated: Int, input: () -> Int): Sample = sample({ emulated }, input) { _, _ -> }

    /**
     * The game screen's [sample]: reads the emulator's next frame ([emulated])
     * and hands it the new inputs ([push]) under the seating lock too. A
     * seating resets the emulator to frame 0; read outside, an old game's
     * frame (thousands) against the new game would sample thousands of
     * inputs at once, which the emulator can't take, and it would stall.
     */
    fun sample(emulated: () -> Int, input: () -> Int, push: (start: Int, masks: IntArray) -> Unit): Sample {
        val s = synchronized(deliverLock) {
            val ls = lockstep
            val frame = emulated()
            val new = ls.sampleUpTo(frame, input)
            if (new.second.isNotEmpty()) {
                push(new.first, new.second)
                if (isOwner && ls.playing) relay.record(localPlayer, new.first, new.second)
            }
            ls.delay = tuner.update(ls.delay, partnerAdvantage)
            Sample(epoch, new.first, new.second, frame)
        }
        if (s.masks.isNotEmpty()) flush()
        return s
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

    /** Watching: asks for a seat (a free one, or an idle player's; the first to ask gets it). */
    fun requestSeat() {
        if (!watching) return
        if (isOwner) worker.execute { sit(localId) } else control(Msg.SIT)
    }

    /**
     * For a game that stopped or went out of step: a watcher asks the owner
     * for the game again (nobody else notices); players are all put back in
     * step from the owner's game.
     */
    fun requestResync() {
        when {
            isOwner -> worker.execute { resync() }
            watching -> {
                val now = System.nanoTime()
                if (now - lastRewatch < 2_000_000_000L) return
                lastRewatch = now
                control(Msg.REWATCH)
            }
            else -> control(Msg.RESYNC)
        }
    }

    private fun control(type: Byte) {
        val payload = byteArrayOf(localId.toByte())
        if (isOwner) {
            ownerLines().forEach { sendTo(it, type, payload) }
        } else {
            ownerLine?.let { runCatching { it.output.message(type, payload) } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        beacon?.close()
        worker.shutdownNow()
        pinger.shutdownNow()
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

    private fun feedIds(): Set<Int> = synchronized(lines) { feeds.keys.toSet() }

    // To one phone from the owner: through its feed if it watches (never
    // waiting on a slow watcher), straight to it otherwise.
    private fun sendTo(line: Line, type: Byte, payload: ByteArray) {
        val feed = synchronized(lines) { feeds[line.id] }
        if (feed != null) feed.post(type, payload) else runCatching { line.output.message(type, payload) }
    }

    /** Messages from one phone to the owner (on the owner). */
    private fun ownerLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                line.lastHeard = System.nanoTime()
                val from = line.id
                when (t) {
                    Msg.READY -> worker.execute { admit(from) }
                    Msg.BYE -> {
                        worker.execute { remove(from, "${line.name} left") }
                        return
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        val seat = members[from]?.seat ?: -1
                        if (seat >= 0) {
                            paused[from] = t == Msg.PAUSE
                            for (other in ownerLines()) if (other !== line) sendTo(other, t, payload)
                            listener?.onPartnerPaused(seat, t == Msg.PAUSE)
                        }
                    }
                    Msg.REMATCH -> worker.execute { rematch() }
                    Msg.RESYNC -> worker.execute { resync() }
                    Msg.SIT -> worker.execute { sit(from) }
                    Msg.REWATCH -> worker.execute { if (members[from]?.seat == -1) watch(from) }
                }
            }
        } catch (e: IOException) {
            if (!closed) worker.execute { remove(line.id, "${line.name} left") }
        }
    }

    /** A newcomer's game is ready: it comes in watching (on the worker). Nobody else stops. */
    private fun admit(id: Int) {
        if (closed || !isOwner) return
        val a = synchronized(lines) { arriving.remove(id) }
        if (a == null) {
            // In already, but it didn't hear: the game again.
            if (members[id]?.seat == -1) watch(id) else if (id in members) resync()
            return
        }
        val m = Member(id, a.line.name, a.line.socket.inetAddress.hostAddress, a.hello.udpPort, a.hello.tcpPort)
        synchronized(deliverLock) { members = members + (id to m) }
        val feed = Feed(a.line)
        synchronized(lines) { feeds[id] = feed }
        feed.start()
        roster()
        watch(id)
        listener?.onNews("${m.name} is watching")
    }

    /** Sends a watcher the game at the newest frame every input is in for, and the inputs from there (on the worker). */
    private fun watch(id: Int, tries: Int = 0) {
        if (closed || !isOwner) return
        val feed = synchronized(lines) { feeds[id] } ?: return
        val snap = runCatching { listener?.snapshot() }.getOrNull()
        val frame = snap?.first ?: -1
        // Squeezed outside the lock: the game's own frames never wait on it.
        val packed = snap?.let { pack(it.second) }
        val game = epoch
        val ready = synchronized(deliverLock) {
            // Inputs from that frame must still be at hand to relay, in the same game.
            if (packed == null || game != epoch || !relay.has(frame)) {
                false
            } else {
                feed.watchFrom(Watch(epoch, localId, membersFor(feed.line), frame), packed, epoch, frame)
                true
            }
        }
        // None to give right now (the game's between frames): again shortly.
        if (!ready && tries < 50) worker.schedule({ watch(id, tries + 1) }, 100, TimeUnit.MILLISECONDS)
    }

    // The members as [line]'s phone sees them: the owner at the address it reaches it on.
    private fun membersFor(line: Line, all: Collection<Member> = members.values): List<Member> =
        all.map { if (it.id == localId) it.copy(host = line.socket.localAddress.hostAddress) else it }

    // Tells everyone who's in, for the same game.
    private fun roster() {
        for (line in ownerLines()) {
            if (line.id !in members) continue
            sendTo(line, Msg.ROSTER, encode(Seating(epoch, localId, membersFor(line))))
        }
        listener?.onRoster()
    }

    /**
     * Seats everyone (on the worker): [gone] out, [moves] (member id to its
     * new seat, -1 to watch); everyone carries on from the owner's game, or
     * from the start for a restart.
     */
    private fun seat(news: String?, gone: Int? = null, moves: Map<Int, Int> = emptyMap(), fromStart: Boolean = false) {
        if (closed || !isOwner) return
        val ln = listener ?: return
        val next = members.toMutableMap()
        if (gone != null) next.remove(gone)
        // The room's first seating: its owner is player 1.
        next[localId] = (members[localId] ?: Member(localId, name, "", 0, 0, seat = 0))
            .copy(name = name, host = "", udpPort = udp.localPort, tcpPort = server.localPort)
        for ((id, seat) in moves) next[id]?.let { next[id] = it.copy(seat = seat) }
        // Phones whose connection is gone aren't in.
        val live = ownerLines().associateBy { it.id }
        next.keys.retainAll { it == localId || it in live }
        val state = if (fromStart) startState else null
        val sent = state ?: runCatching { ln.captureState() }.getOrElse { return }
        val packed = pack(sent)
        val nextEpoch = epoch + 1
        // Watchers who now play lose their feed (finished first, so nothing of
        // it comes between the seating and its state); players who now watch get one.
        val retired = synchronized(lines) {
            next.values.filter { it.id != localId && it.seat >= 0 }.mapNotNull { feeds.remove(it.id) }
        }
        retired.forEach { it.retire() }
        retired.forEach { it.join(FEED_STOP_MS) }
        synchronized(lines) {
            for (m in next.values) {
                if (m.id == localId || m.seat >= 0) continue
                val line = live[m.id] ?: continue
                if (m.id !in feeds) feeds[m.id] = Feed(line).also { it.start() }
            }
        }
        // Players, straight: they start the next game the moment it's in.
        for (m in next.values) {
            if (m.id == localId || m.seat < 0) continue
            val line = live[m.id] ?: continue
            try {
                sendWithState(line, Msg.SEATING, encode(Seating(nextEpoch, localId, membersFor(line, next.values))), packed)
            } catch (e: IOException) {
                line.close()
            }
        }
        apply(Seating(nextEpoch, localId, next.values.toList()), if (fromStart) state else null, 0)
        // Watchers through their feeds, so a slow one holds nobody up.
        synchronized(lines) {
            for ((id, feed) in feeds) {
                if (id !in next) continue
                feed.seating(Seating(nextEpoch, localId, membersFor(feed.line, next.values)), packed, nextEpoch)
            }
        }
        if (news != null) ln.onNews(news)
    }

    /**
     * A watcher asks for a seat (on the worker, so the first request in gets
     * it): the lowest free one, else the one whose player has been idle the
     * longest, if IDLE_SECONDS or more (they go back to watching).
     */
    private fun sit(id: Int) {
        if (closed || !isOwner) return
        val m = members[id]
        if (m == null) {
            tell(id, "Still joining: try again in a moment.")
            return
        }
        if (m.seat >= 0) return
        val taken = members.values.map { it.seat }.toSet()
        val free = (0 until maxPlayers).firstOrNull { it !in taken }
        if (free != null) {
            seat("${m.name} is player ${free + 1}", moves = mapOf(id to free))
            return
        }
        val idle = synchronized(deliverLock) {
            seats.values.map { it to relay.idleFrames(it.seat) }.filter { it.second >= idleLimit }.maxByOrNull { it.second }?.first
        }
        if (idle == null) {
            tell(id, "All seats are in use. One opens when its player presses nothing for $IDLE_SECONDS seconds.")
            return
        }
        tell(idle.id, "You were idle, so ${m.name} took your seat. Press COIN or START to play again.")
        seat("${m.name} is player ${idle.seat + 1} (${idle.name} was idle)", moves = mapOf(id to idle.seat, idle.id to -1))
    }

    // Tells one phone something.
    private fun tell(id: Int, message: String) {
        if (id == localId) {
            listener?.onNews(message)
            return
        }
        val line = synchronized(lines) { lines[id] } ?: return
        sendTo(line, Msg.NEWS, message.toByteArray())
    }

    /** A phone left (on the worker): a player's seat empties (a seating); a watcher just goes. */
    private fun remove(id: Int, news: String) {
        if (id == localId || closed) return
        val line = synchronized(lines) {
            arriving.remove(id)
            feeds.remove(id)?.retire()
            lines.remove(id)
        }
        line?.close()
        val m = members[id] ?: return
        if (m.seat >= 0) {
            seat(news, gone = id)
        } else {
            synchronized(deliverLock) { members = members - id }
            roster()
            listener?.onNews(news)
        }
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
            // Another emulator build can run the same game a little differently,
            // and the phones would drift apart.
            hello.build.isNotEmpty() && build.isNotEmpty() && hello.build != build ->
                return refuse("This phone and the host have different app builds. Install the latest app on every phone.")
        }
        val id = synchronized(lines) {
            freeId()?.also { id ->
                line.id = id
                line.name = hello.name
                arriving[id] = Arriving(line, hello)
            }
        } ?: return refuse("The room is full: $MAX_MEMBERS phones at most.")
        line.socket.soTimeout = 0
        try {
            out.message(Msg.WELCOME, encode(Welcome(true, name = name, delay = startDelay, session = session, id = id, maxPlayers = maxPlayers, owner = localId, key = key)))
            out.message(Msg.START_STATE, pack(startState ?: ByteArray(0)))
        } catch (e: IOException) {
            synchronized(lines) { arriving.remove(id) }
            throw e
        }
        // Only now one of the room's lines: nothing else could come between
        // its welcome and the start state.
        line.lastHeard = System.nanoTime()
        synchronized(lines) { lines[id] = line }
        thread("net-tcp-$id") { ownerLoop(line) }
    }

    // A member id for a newcomer, if the room has space.
    private fun freeId(): Int? = synchronized(lines) {
        val used = members.keys + lines.keys + arriving.keys + localId
        if (used.size >= MAX_MEMBERS) null else (0 until MAX_IDS).firstOrNull { it !in used }
    }

    /**
     * The owner's record of every player's inputs in this game (under the
     * seating lock), to relay to watchers and to see who's idle.
     */
    private inner class Relay {
        private var base = 0
        private val masks = Array(4) { ArrayList<Int>() }
        private val lastActive = IntArray(4)

        fun reset() {
            base = 0
            masks.forEach { it.clear() }
            lastActive.fill(0)
        }

        fun record(seat: Int, start: Int, new: IntArray) {
            if (seat !in 0 until 4) return
            val list = masks[seat]
            new.forEachIndexed { i, m ->
                if (start + i == base + list.size) {
                    list += m
                    if (m != 0) lastActive[seat] = start + i
                }
            }
            if (list.size > 4 * KEEP_FRAMES) trim(feedsFrom())
        }

        /** Frames below this have every player's input. */
        val frontier: Int get() = seatsPlaying().minOfOrNull { base + masks[it].size } ?: Int.MAX_VALUE

        fun has(frame: Int) = frame >= base

        /** Frames since [seat] last pressed anything. */
        fun idleFrames(seat: Int): Int = (base + masks[seat].size) - lastActive[seat]

        /** Every playing seat's inputs for [from] until at most [max] frames on, up to [frontier]. */
        fun take(from: Int, max: Int): Pair<Int, Array<IntArray>>? {
            val order = seatsPlaying()
            if (order.isEmpty() || from < base) return null
            val end = minOf(frontier, from + max)
            if (end <= from) return null
            val mask = order.fold(0) { m, p -> m or (1 shl p) }
            return mask to Array(order.size) { k -> IntArray(end - from) { masks[order[k]][from - base + it] } }
        }

        /** Forgets what no watcher still needs (keeping a little for newcomers). */
        fun trim(keepFrom: Int) {
            val front = frontier
            if (front == Int.MAX_VALUE) return
            val cut = minOf(keepFrom, front - KEEP_FRAMES) - base
            if (cut <= 0) return
            for (list in masks) list.subList(0, minOf(cut, list.size)).clear()
            base += cut
        }

        private fun seatsPlaying() = (0 until 4).filter { activeMask and (1 shl it) != 0 }
    }

    private val relay = Relay()

    /**
     * The owner's line to one watcher: everything it's sent goes through
     * here, on a thread of its own, so a slow watcher only slows itself.
     * After the game's state, it relays the players' inputs as they come in.
     */
    private inner class Feed(val line: Line) : Thread("net-feed-${line.id}") {
        private val queue = LinkedBlockingQueue<() -> Unit>()
        @Volatile private var retired = false
        // Next frame to relay, of game [game] (-1: not yet watching).
        @Volatile private var next = -1
        @Volatile private var game = -1
        private var lastWrite = 0L

        init {
            isDaemon = true
            synchronized(threads) { threads += this }
        }

        fun post(type: Byte, payload: ByteArray) = queue.add { line.output.message(type, payload) }

        /** Watch [watch] from its frame (its state [packed]): call under the seating lock. */
        fun watchFrom(watch: Watch, packed: ByteArray, epoch: Int, frame: Int) {
            next = -1
            // A newer game replaces any not sent yet (a slow watcher asking
            // again and again mustn't pile up states).
            queue.clear()
            queue.add {
                sendWithState(line, Msg.WATCH, encode(watch), packed)
                game = epoch
                next = frame
            }
        }

        /** The next game, from its start. */
        fun seating(seating: Seating, packed: ByteArray, epoch: Int) {
            next = -1
            queue.clear()
            queue.add {
                sendWithState(line, Msg.SEATING, encode(seating), packed)
                game = epoch
                next = 0
            }
        }

        fun retire() {
            retired = true
            interrupt()
        }

        override fun run() {
            try {
                while (!closed && !retired) {
                    queue.poll(FEED_MS, TimeUnit.MILLISECONDS)?.let {
                        it()
                        lastWrite = System.nanoTime()
                    }
                    if (queue.isNotEmpty()) continue
                    relayInputs()
                    if (System.nanoTime() - lastWrite > PING_MS * 1_000_000L) {
                        line.output.message(Msg.PING, byteArrayOf(localId.toByte()))
                        lastWrite = System.nanoTime()
                    }
                }
            } catch (e: InterruptedException) {
                // Retired.
            } catch (e: IOException) {
                if (!closed && !retired) worker.execute { remove(line.id, "${line.name} left") }
            } finally {
                synchronized(threads) { threads.remove(this) }
            }
        }

        private fun relayInputs() {
            val from = next
            if (from < 0) return
            val batch = synchronized(deliverLock) {
                if (game != epoch) return
                if (!relay.has(from)) {
                    // Fell behind what's kept: the game again, from now.
                    next = -1
                    worker.execute { watch(line.id) }
                    return
                }
                val got = relay.take(from, 240) ?: return
                relay.trim(feedsFrom())
                val sum = latestSum
                RelayedInputs(epoch, from, got.first, got.second, sum?.first ?: -1, sum?.second ?: 0)
            }
            line.output.message(Msg.INPUTS, batch.encode())
            next = from + batch.count
            lastWrite = System.nanoTime()
        }

        val nextFrame: Int get() = if (game == epoch) next else -1
    }

    // A seating or watch and the game state it starts from, back to back:
    // nothing another thread sends on the line can come between them.
    private fun sendWithState(line: Line, type: Byte, payload: ByteArray, state: ByteArray) {
        synchronized(line.output) {
            line.output.message(type, payload)
            line.output.message(Msg.STATE, state)
        }
    }

    // The oldest frame a watcher still needs (none: MAX).
    private fun feedsFrom(): Int = synchronized(lines) { feeds.values.map { it.nextFrame }.filter { it >= 0 }.minOrNull() } ?: Int.MAX_VALUE

    // ----- A member's side -----

    /** Messages from the owner (on a member). */
    private fun memberLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                // A line from an owner that's been replaced says nothing more.
                if (line !== ownerLine) return
                line.lastHeard = System.nanoTime()
                when (t) {
                    Msg.SEATING -> {
                        val seating = decode<Seating>(payload)
                        apply(seating, unpack(stateAfter(line)), 0)
                    }
                    Msg.WATCH -> {
                        val w = decode<Watch>(payload)
                        apply(Seating(w.epoch, w.owner, w.members), unpack(stateAfter(line)), w.frame)
                    }
                    Msg.INPUTS -> relayed(RelayedInputs.decode(payload))
                    Msg.ROSTER -> {
                        val seating = decode<Seating>(payload)
                        synchronized(deliverLock) {
                            if (seating.epoch == epoch) {
                                members = seating.members.associateBy { it.id }
                                owner = seating.owner
                            }
                        }
                        listener?.onRoster()
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

    // The state after a SEATING or WATCH (pings and old inputs in between go by).
    private fun stateAfter(line: Line): ByteArray {
        while (true) {
            val (t, payload) = line.input.message()
            if (t == Msg.STATE) return payload
            if (t !in SKIPPABLE && t != Msg.INPUTS) throw IOException("Expected the game state")
        }
    }

    // Watching: the players' inputs, relayed by the owner.
    private fun relayed(r: RelayedInputs) {
        synchronized(deliverLock) {
            if (r.epoch != epoch || !watching) return
            if (r.sumFrame >= 0) check(r.sumFrame, r.sum, from = owner)
            val ln = listener ?: return
            var k = 0
            for (seat in 0 until 4) {
                if (r.seats and (1 shl seat) == 0) continue
                val masks = r.masks[k++]
                // Only what's new, in order.
                val skip = relayed[seat] - r.start
                if (skip < 0 || skip >= masks.size) continue
                ln.onRemote(seat, relayed[seat], masks.copyOfRange(skip, masks.size))
                relayed[seat] = r.start + masks.size
            }
        }
    }

    /** Game [Seating.epoch] starts with these members, from [state] (null: the game as it stands), at [from]. */
    private fun apply(seating: Seating, state: ByteArray?, from: Int) {
        synchronized(deliverLock) {
            val ln = listener
            val next = seating.members.associateBy { it.id }
            members = next
            owner = seating.owner
            epoch = seating.epoch
            startFrame = from
            relayed.fill(from)
            val others = next.values.filter { it.id != localId }
            val players = others.filter { it.seat >= 0 }.map { it.id }.sorted()
            val playing = (next[localId]?.seat ?: -1) >= 0
            val ownerWatches = (next[seating.owner]?.seat ?: -1) < 0
            ln?.onSeating(epoch, activeMask, state, from)
            // Players trade inputs with the other players (and an owner who
            // watches, to relay them); an owner who watches gets them from the
            // players; other watchers get them from the owner.
            lockstep = when {
                playing -> Lockstep(lockstep.delay, localId, players(), players, if (ownerWatches && seating.owner != localId) listOf(seating.owner) else emptyList())
                isOwner -> Lockstep(lockstep.delay, -1, players(), players)
                else -> Lockstep(lockstep.delay, -1, players(), emptyList())
            }
            if (isOwner) relay.reset()
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
        if (epoch == 0) {
            // Not in yet: nothing to take over.
            migrating.set(false)
            listener?.onClosed("$news before you got in")
            return
        }
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
        // All at once: one unreachable phone mustn't make the others give up waiting.
        val calls = others.mapNotNull { id ->
            val m = members[id] ?: return@mapNotNull null
            Thread {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(m.host, m.tcpPort), 3_000)
                    socket.tcpNoDelay = true
                    val line = Line(socket, id, m.name)
                    line.output.message(Msg.TAKEOVER, encode(Takeover(session, localId)))
                    synchronized(lines) { lines[id] = line }
                    thread("net-tcp-$id") { ownerLoop(line) }
                } catch (e: IOException) {
                    // Unreachable: not in the next game.
                }
            }.apply { start() }
        }
        calls.forEach { it.join(4_000) }
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
            // The old owner's line, if this phone hadn't noticed it go: done with.
            ownerLine?.takeIf { it !== line }?.close()
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
            if (masks.isEmpty()) return
            listener?.onRemote(seat, start, masks)
            if (isOwner) relay.record(seat, start, masks)
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
            } finally {
                synchronized(threads) { threads.remove(Thread.currentThread()) }
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
        private const val LINE_QUIET_NS = 10_000_000_000L
        private const val ARRIVING_QUIET_NS = 60_000_000_000L
        private val SKIPPABLE = setOf(Msg.PING, Msg.NEWS, Msg.ROSTER, Msg.PAUSE, Msg.RESUME)
        private const val TAKEOVER_MS = 12_000L
        private const val FEED_STOP_MS = 1_000L
        private const val READY_MS = 3_000L
        private const val PING_MS = 1_000L
        private const val FEED_MS = 16L
        private const val IDLE_FRAMES = IDLE_SECONDS * 60
        // Inputs the owner keeps beyond what watchers need, for newcomers' snapshots.
        private const val KEEP_FRAMES = 600

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
            delay: Int = 1, port: Int = TCP_PORT, beaconPort: Int = BEACON_PORT, build: String = "",
        ): Room = Room(
            name, set, title, rom, bios, maxPlayers, localId = 0, session = Random.nextInt(),
            server = openServer(port), udp = DatagramSocket(0), owner = 0, startDelay = delay, beaconPort = beaconPort,
            build = build,
        ).also { it.key = it.keyNow() }

        /**
         * Joins the room at [host]:[port], to watch until a seat is taken
         * ([requestSeat]); the room's start state is then in
         * [Room.startState]. [Refused] (with the reason) if it can't.
         */
        fun join(
            host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long,
            listenPort: Int = TCP_PORT, beaconPort: Int = BEACON_PORT, build: String = "",
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
                line.output.message(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, udp.localPort, server.localPort, build)))
                val (t, payload) = line.input.message()
                if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
                val w = decode<Welcome>(payload)
                if (!w.accepted) throw Refused(w.reason)
                // (Anything else first, a ping say, can wait.)
                var start = line.input.message()
                while (start.first != Msg.START_STATE && start.first in SKIPPABLE) start = line.input.message()
                if (start.first != Msg.START_STATE) throw IOException("Expected the start state")
                socket.soTimeout = 0
                line.id = w.owner
                line.name = w.name
                val room = Room(
                    name, set, "", rom, bios, w.maxPlayers, localId = w.id, session = w.session,
                    server = server, udp = udp, owner = w.owner, startDelay = w.delay, beaconPort = beaconPort,
                    build = build,
                )
                room.ownerLine = line
                room.startState = unpack(start.second).takeIf { it.isNotEmpty() }
                // Until the first game: who's who, for messages.
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
