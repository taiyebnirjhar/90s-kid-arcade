package com.kofarcade.core.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
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
// request to reach the owner gets it): a free seat, or one whose player the
// game itself shows out (never started, or game over once its continue
// countdown ran out: [RoomListener.seatFree]), as on the cabinet: they go
// back to watching. No timer of our own, except for a game that can't say:
// there, a player who pressed nothing for FALLBACK_IDLE_SECONDS (about the
// games' own continue countdown). Anyone may
// leave: their seat empties and the game goes on. If the owner (the phone
// that runs the room) leaves, the next one takes the room over.
//
// Dropping out is not leaving. A player whose phone goes quiet keeps its
// seat for HOLD_SECONDS (the owner included: whoever takes over holds it),
// and the game waits for them. A phone that loses the room (its own Wi-Fi
// went, or the owner's did with nobody else to take over) doesn't carry on
// alone: it says so ([RoomListener.onLost]) and finds the room again
// ([Room.rejoin]): its owner, or whoever took it over, back into its seat.
// An owner left with nobody looks for a takeover the same way.
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
//
// How the phones reach each other is the [Transport]'s business: the room
// talks over [Link]s (to the owner, or from it) and [Datagrams] (inputs),
// and keeps where each phone is as an opaque [Address].

open class Refused(reason: String) : IOException(reason)

/** Turned away by a phone of the room that isn't running it, which says who is: [at]. */
class Redirected(reason: String, val at: Address) : Refused(reason)

/** Phones in a room at most: players and watchers. */
const val MAX_MEMBERS = 8

/** What the game screen hears from its room. Called on network threads. */
interface RoomListener {
    /** [player]'s inputs (a seat) for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(player: Int, start: Int, masks: IntArray)

    /** The games differed at [frame]: the room puts everyone back in step from the owner's game. */
    fun onDesync(frame: Int)

    /** This phone is out of the room (it lost everyone): the game can't go on together. */
    fun onClosed(reason: String)

    /**
     * This phone lost the room (its connection, or the owner's, went): call
     * [Room.rejoin] now and then to get back in (its seat is held a while).
     */
    fun onLost(reason: String) = onClosed(reason)

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

    /**
     * Owner: the game shows [seat]'s player out (never started, or game
     * over once its continue countdown ran out), from this phone's copy of
     * the game. A watcher's COIN or START takes such a seat. Null: this game
     * can't say (the room falls back to FALLBACK_IDLE_SECONDS of no input).
     */
    fun seatFree(seat: Int): Boolean? = null

    /**
     * Owner: whether this phone's game takes [state], a saved game a player
     * wants everyone to carry on from (a save of another game, or of another
     * version of it, doesn't). Asked before anyone's game is replaced.
     */
    fun canLoad(state: ByteArray): Boolean = true

    /** Something to tell the player: "Rafi joined", "You're the host now". */
    fun onNews(message: String) {}
}

/** A link to another phone of the room ([id]: its member id, -1 before it has one). */
internal class Line(
    val link: Link,
    @Volatile var id: Int,
    @Volatile var name: String,
    /** For tests: this phone's network faults (none in the app). */
    private val faults: Faults? = null,
) {
    @Volatile var lastHeard = System.nanoTime()

    fun close() = runCatching { link.close() }

    // While the phone is cut off, what it sends is lost or held back, and
    // nothing new arrives (tests only).
    fun send(vararg messages: Pair<Byte, ByteArray>) {
        val f = faults
        if (f != null) {
            f.waitWhileStalled { link.isClosed }
            if (f.drops) return
        }
        link.send(*messages)
    }

    fun send(type: Byte, payload: ByteArray = ByteArray(0)) = send(type to payload)

    fun receive(): Pair<Byte, ByteArray> {
        faults?.waitWhileStalled { link.isClosed }
        return link.receive()
    }
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
    /** How this phone reaches the others. */
    private val transport: Transport,
    // Where links from the others come in (newcomers, a new owner).
    private val incoming: Listener,
    // Inputs, to and from the phones of the game.
    private val packets: Datagrams,
    owner: Int,
    private val startDelay: Int,
    private val beaconPort: Int,
    private val build: String,
    private val device: String,
    // Where this phone listens for a new owner (to join again the same way).
    private val listenPort: Int,
    /** For tests: this phone's network faults, shared by every room it joins. */
    val faults: Faults = Faults(),
) : Closeable {
    /** The member id of the phone that runs the room: it seats everyone and runs every change. */
    @Volatile var owner: Int = owner
        private set

    val isOwner: Boolean get() = owner == localId

    // A line of the connection log (tests: with this phone's name).
    private fun log(text: String) = NetLog.add(if (NetLog.echo != null) "[$name] $text" else text)

    // Something to tell the player, kept in the connection log too.
    private fun news(text: String) {
        log(text)
        listener?.onNews(text)
    }

    /** For tests: which room this is (the same for every phone in it). */
    internal val sessionNumber: Int get() = session

    /** Everyone in the room, this phone included, by member id. */
    @Volatile var members: Map<Int, Member> = emptyMap()
        private set

    /** The players, by seat (0 = player 1). */
    val seats: Map<Int, Member> get() = members.values.filter { it.seat >= 0 && !it.away }.associateBy { it.seat }

    /** Seats held for players who dropped out (they get them back if they return in time), by seat. */
    val held: Map<Int, Member> get() = members.values.filter { it.seat >= 0 && it.away }.associateBy { it.seat }

    /** When [id]'s held seat goes free (System.currentTimeMillis), or 0. */
    fun holdEnds(id: Int): Long = holdUntil[id] ?: 0L

    /** How long [id]'s seat has been held (ms): since it dropped out. */
    fun heldFor(id: Int): Long = holdUntil[id]?.let { holdMs - (it - System.currentTimeMillis()) } ?: 0L

    /** This phone's seat (0 = player 1); -1 while it watches. */
    val localPlayer: Int get() = members[localId]?.seat ?: -1

    /** This phone watches rather than plays. */
    val watching: Boolean get() = localPlayer < 0

    /** Phones watching. */
    val watcherCount: Int get() = members.values.count { it.seat < 0 }

    /**
     * An internet room's round trip to the other players (or, watching, the
     * room's host), the slowest, in ms; null on Wi-Fi or before it's measured.
     */
    val pingMs: Int?
        get() {
            val t = transport as? WebRtcTransport ?: return null
            val others = partners.ifEmpty { listOfNotNull(members[owner]?.takeIf { it.id != localId }) }
            return others.mapNotNull { m -> m.host.takeIf { it.isNotEmpty() }?.let(t::rttMs) }.maxOrNull()
        }

    /** Watching and in line for a seat: this phone's place (1 is next), or 0. */
    val linePlace: Int get() = members[localId]?.takeIf { it.seat < 0 }?.line ?: 0

    /** A seat nobody's in. */
    val hasFreeSeat: Boolean get() = seats.size + held.size < maxPlayers

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
    // Over the internet, up to 4 frames (higher pings); on Wi-Fi, 3.
    private val tuner = DelayTuner(max = if (transport is WebRtcTransport) MAX_ONLINE_DELAY else 3)
    // An internet room's delay was set from the measured ping (at the first game with partners).
    private var delayFromPing = false

    /**
     * The input delay for a new game: as tuned so far; in an internet room's
     * first game with partners, at least the delay for the ping to them
     * ([startingDelay]), and the tuner adapts from there.
     */
    private fun delayFor(partnerHosts: List<String>): Int {
        val t = transport as? WebRtcTransport ?: return lockstep.delay
        if (delayFromPing || partnerHosts.isEmpty()) return lockstep.delay
        val rtt = partnerHosts.mapNotNull(t::rttMs).maxOrNull() ?: return lockstep.delay
        delayFromPing = true
        return maxOf(lockstep.delay, startingDelay(rtt)).also { log("Ping to the others: $rtt ms, input delay $it") }
    }
    private val timeSync = TimeSync()

    /** Seats taken, as a bit mask (bit p = seat p). */
    val activeMask: Int get() = seats.keys.fold(0) { m, p -> m or (1 shl p) }

    @Volatile private var closed = false
    @Volatile private var listener: RoomListener? = null
    private val lastHeard = LongArray(MAX_IDS)
    // The longest wait between two packets from each phone, since last asked.
    private val gap = LongArray(MAX_IDS)
    @Volatile private var lastSent = 0L
    private val mySums = HashMap<Int, Long>()
    // Each partner's sum for a frame (one each: every packet carries the latest again).
    private val theirSums = HashMap<Int, MutableMap<Int, Long>>()
    // Frames already found to differ: put back in step once, not on every packet after.
    private val reportedDesync = HashSet<Int>()
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

    // Held seats: member id -> when the hold ends (System.currentTimeMillis).
    private val holdUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** How long a dropped player's seat is held (shorter in tests). */
    @Volatile internal var holdMs = HOLD_SECONDS * 1000L

    /** Silence before a player (UDP) or a connection (TCP) counts as gone, and the wait for a takeover (shorter in tests). */
    @Volatile internal var quietNs = QUIET_NS
    @Volatile internal var lineQuietNs = LINE_QUIET_NS
    @Volatile internal var takeoverMs = TAKEOVER_MS

    /**
     * For tests: the phone's network is cut (nothing in or out), as when its
     * Wi-Fi drops. With [TcpCut.RESET], its TCP connections die at once, as
     * Android destroys them.
     */
    internal var offline: Boolean
        get() = faults.cut
        set(value) {
            faults.cut = value
            if (value && faults.tcp == TcpCut.RESET) resetConnections()
        }

    /** For tests: only this phone's connection to the owner breaks (the network's fine). */
    internal fun breakOwnerLine() {
        ownerLine?.close()
    }

    // Every TCP connection of this phone, gone (its listening socket stays).
    private fun resetConnections() {
        (synchronized(lines) { lines.values.toList() } + listOfNotNull(ownerLine)).forEach { it.close() }
    }

    // Where the room's other phones were last (the owner first), to find the room again.
    @Volatile private var peers: List<Address> = emptyList()
    // Found again: the owner who took the room over, tried first.
    @Volatile private var foundAt: Address? = null
    // Back in after losing the room ([rejoin]): losing it again before the
    // first game is lost again (try once more), not the end.
    @Volatile private var rejoined = false

    private fun line(link: Link, id: Int, name: String) = Line(link, id, name, faults)

    /** For tests: where this phone takes links. */
    internal val roomPort: Int get() = incoming.port

    /** For tests: how this phone reaches the others. */
    internal val via: Transport get() = transport

    /** An internet room (WebRTC), not one on this Wi-Fi: it isn't tied to the Wi-Fi, and has no beacon. */
    val overInternet: Boolean get() = transport is WebRtcTransport

    // This game's first frame on this phone: 0, or where it came in to watch.
    @Volatile private var startFrame = 0
    // Watching (not the owner): each seat's next frame of relayed inputs.
    private val relayed = IntArray(4)

    /** How long a new player has to start in the game before its word that their seat is free counts (shorter in tests). */
    @Volatile internal var seatGraceMs = SEAT_GRACE_MS

    /** For a game that can't say who's out: frames of nothing pressed before a player's seat can be taken (shorter in tests). */
    @Volatile internal var idleLimit = FALLBACK_IDLE_SECONDS * 60

    // When each seat was last given to someone (System.currentTimeMillis).
    private val seatedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun players() = maxPlayers.coerceIn(2, 4)

    fun nameOf(id: Int): String = members[id]?.name ?: "Player"

    // The other players.
    private val partners: List<Member> get() = members.values.filter { it.seat >= 0 && !it.away && it.id != localId }

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
     * Once per frame, after [updateAdvantage]: how many frames this phone's
     * game should wait now so it doesn't run ahead of the others' (0 almost
     * always; see [TimeSync]). A partner unheard for a moment isn't measured:
     * what it last said is out of date.
     */
    fun timeSync(): Int {
        val ls = lockstep
        if (!ls.playing || ls.partners.isEmpty()) return 0
        val now = System.nanoTime()
        return timeSync.update(ls.partners) { p ->
            if (now - lastHeard[p] > FRESH_NS) null else frameLead(advantage[p], ls.delay, theirAdvantage[p], theirDelay[p])
        }
    }

    /** Watching: the newest frame the players' inputs are all in for (relayed, or straight from them). */
    val watchedUpTo: Int
        get() = if (isOwner || !watching) lockstep.remoteKnown else seats.keys.minOfOrNull { relayed[it] } ?: Int.MAX_VALUE

    /**
     * For the connection log: each other phone's longest silence (ms) since
     * last asked, when one was long enough to notice (a Wi-Fi hiccup; with
     * nothing new to send, a phone still sends every 100 ms).
     */
    fun takeHiccups(atLeastMs: Long = 150): Map<String, Long> {
        val ls = lockstep
        val out = LinkedHashMap<String, Long>()
        for (p in ls.partners + ls.watchers) {
            val ms = gap[p] / 1_000_000
            gap[p] = 0
            if (ms >= atLeastMs) out[nameOf(p)] = ms
        }
        return out
    }

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
        log("Room ${session.toUInt().toString(16)}: member $localId, ${if (isOwner) "host" else "host is $owner"}, ${build.ifEmpty { "?" }}")
        thread("net-udp") { receiveLoop() }
        thread("net-send") { sendLoop() }
        thread("net-accept") { acceptLoop() }
        thread("net-ping") { pingLoop() }
        thread("net-scout") { scoutLoop() }
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
                    runCatching { ownerLine?.send(Msg.READY, byteArrayOf(localId.toByte())) }
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
        if (offline) return
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
            if (faults.lose()) continue
            val to = Address(m.host, m.udpPort)
            val late = faults.delayMs()
            if (late > 0) {
                runCatching { Faults.late.schedule({ runCatching { if (!faults.cut) packets.send(bytes, to) } }, late, TimeUnit.MILLISECONDS) }
                continue
            }
            try {
                packets.send(bytes, to)
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
            if (from == null) {
                mySums[frame] = sum
            } else {
                val known = theirSums.getOrPut(frame) { HashMap() }
                if (known[from] == sum) return
                known[from] = sum
            }
            mySums.keys.removeAll { it < frame - 1200 }
            theirSums.keys.removeAll { it < frame - 1200 }
            reportedDesync.removeAll { it < frame - 1200 }
            val mine = mySums[frame]
            mine != null && theirSums[frame].orEmpty().values.any { it != mine } && reportedDesync.add(frame)
        }
        if (differs) {
            log("Games differ at frame $frame: putting everyone back in step")
            listener?.onDesync(frame)
            // Back in step from the owner's game, without restarting anything:
            // a watcher alone, players all together. (On a thread: this can be the game's.)
            runCatching { worker.execute { requestResync() } }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (!closed) {
            val length = try {
                packets.receive(buf, 500)
            } catch (e: IOException) {
                if (closed) return
                log("Receiving: ${e.message ?: e.javaClass.simpleName}")
                // One failure isn't the end; silence is.
                checkQuiet()
                Thread.sleep(20)
                continue
            }
            if (length < 0 || offline || faults.lose()) {
                checkQuiet()
                continue
            }
            val p = InputPacket.decode(buf, length) ?: continue
            if (p.session != session || p.from !in members || p.from == localId || p.from >= MAX_IDS) continue
            val heard = System.nanoTime()
            gap[p.from] = maxOf(gap[p.from], heard - lastHeard[p.from])
            lastHeard[p.from] = heard
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
        val gone = (ls.partners + ls.watchers).filter { now - lastHeard[it] > quietNs }
        if (gone.isEmpty()) return
        log("No inputs for ${quietNs / 1_000_000_000.0} s from ${gone.joinToString { nameOf(it) }}")
        if (isOwner) {
            for (id in gone) {
                lastHeard[id] = now
                // Closed now, so no write to it can hold the room up.
                synchronized(lines) { lines[id] }?.close()
                worker.execute { remove(id, "${nameOf(id)} lost the connection", dropped = true) }
            }
        } else if (owner in gone) {
            lastHeard[owner] = now
            ownerLine?.let { ownerGone(it, "lost the connection", dropped = true) }
        } else {
            // Another player (not the owner) unheard: the way between us broke while
            // the owner still hears both. Asked once per quiet spell for a new way
            // (over the internet: an ICE restart, through the relay if need be).
            for (id in gone) {
                lastHeard[id] = now
                members[id]?.host?.takeIf { it.isNotEmpty() }?.let { host -> (transport as? WebRtcTransport)?.reconnect(host) }
            }
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
                releaseEnded()
                // A seat that opened (a game over, its countdown run out) goes to the first in line.
                if (members.values.any { it.line > 0 }) worker.execute { serveLine() }
                val all = ownerLines()
                for (line in all) {
                    val newcomer = synchronized(lines) { line.id in arriving }
                    // A newcomer's phone may take a while to load the game.
                    val quiet = if (newcomer) ARRIVING_QUIET_NS else lineQuietNs
                    if (now - line.lastHeard > quiet) {
                        log("Nothing heard from ${line.name} for ${quiet / 1_000_000_000} s: closing its connection")
                        // Closed now: a write stuck on it (the worker's, say)
                        // fails at once instead of holding up the room.
                        line.lastHeard = now
                        line.close()
                        worker.execute { remove(line.id, "${line.name} lost the connection", dropped = true) }
                        continue
                    }
                    // Watchers hear from their feed.
                    if (line.id !in feedIds()) pingAsync(line, ping)
                }
            } else {
                val line = ownerLine ?: continue
                pingAsync(line, ping)
                if (now - line.lastHeard > lineQuietNs) {
                    log("Nothing heard from the host for ${lineQuietNs / 1_000_000_000} s")
                    line.lastHeard = now
                    ownerGone(line, "lost the connection", dropped = true)
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
                    line.send(Msg.PING, ping)
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

    /**
     * A player (or the owner) wants everyone to carry on from [state], a saved
     * game: the owner checks its game takes it, then seats everyone from it.
     * False if this phone can't ask (it watches, or isn't connected to the owner).
     */
    fun loadForEveryone(state: ByteArray): Boolean {
        if (watching && !isOwner) return false
        if (isOwner) {
            worker.execute { loadSaved(localId, state) }
            return true
        }
        val line = ownerLine ?: return false
        return runCatching { line.send(Msg.LOAD, pack(state)) }.isSuccess
    }

    /** Owner: frees a seat held for a player who dropped out, now. */
    fun freeHeldSeat(seat: Int) {
        if (isOwner) worker.execute { release(held[seat]?.id ?: return@execute, "Player ${seat + 1}'s seat is free", now = true) }
    }

    /** Playing: gives up the seat, to watch (it's free for someone else). In line for a seat: leaves the line. */
    fun requestWatch() {
        if (watching && linePlace == 0) return
        if (isOwner) worker.execute { stand(localId) } else control(Msg.STAND)
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
            ownerLine?.let { runCatching { it.send(type, payload) } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Nothing reaches the game after this (the screen may be closing it).
        listener = null
        beacon?.close()
        worker.shutdownNow()
        pinger.shutdownNow()
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
        val bye = byteArrayOf(localId.toByte())
        val all = synchronized(lines) { lines.values.toList() } + listOfNotNull(ownerLine)
        Thread({
            for (line in all) {
                runCatching { line.send(Msg.BYE, bye) }
                line.close()
            }
            incoming.close()
            runCatching { packets.close() }
        }, "net-close").apply {
            isDaemon = true
            start()
        }
    }

    /** For tests: gone without a goodbye, as when Wi-Fi drops or the app is killed. */
    internal fun vanish() {
        if (closed) return
        closed = true
        beacon?.close()
        worker.shutdownNow()
        pinger.shutdownNow()
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
        (synchronized(lines) { lines.values.toList() } + listOfNotNull(ownerLine)).forEach { it.close() }
        incoming.close()
        runCatching { packets.close() }
    }

    // ----- The owner's side -----

    private fun startBeacon() {
        if (beaconPort < 0 || beacon != null) return
        beacon = runCatching {
            BeaconSender(beaconPort) {
                Beacon(
                    PROTOCOL, name, set, title, rom, incoming.port,
                    players = seats.size.coerceAtLeast(1), maxPlayers = maxPlayers, key = key, watchers = watcherCount,
                    session = session, owner = localId, live = liveCount(),
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
        if (feed != null) feed.post(type, payload) else runCatching { line.send(type, payload) }
    }

    /** Messages from one phone to the owner (on the owner). */
    private fun ownerLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.receive()
                if (faults.drops) continue
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
                    Msg.STAND -> worker.execute { stand(from) }
                    Msg.REWATCH -> worker.execute { if (members[from]?.seat == -1) watch(from) }
                    Msg.LOAD -> {
                        val state = runCatching { unpack(payload) }.getOrNull()
                        worker.execute { if (state != null) loadSaved(from, state) else tell(from, "That saved game didn't arrive whole. Try again.") }
                    }
                }
            }
        } catch (e: IOException) {
            // Gone without saying goodbye: dropped, not left.
            if (!closed) {
                log("Connection to ${line.name} broke: ${e.message ?: e.javaClass.simpleName}")
                worker.execute { remove(line.id, "${line.name} lost the connection", dropped = true) }
            }
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
        val back = members[id]?.takeIf { it.away }
        if (back != null) {
            // Its seat was held: straight back in it, where it can be reached now.
            holdUntil.remove(id)
            val now = back.copy(
                name = a.line.name, host = a.line.link.remoteHost,
                udpPort = a.hello.udpPort, tcpPort = a.hello.tcpPort, away = false, holdLeft = 0,
            )
            seat("${now.name} is back as player ${now.seat + 1}", replace = mapOf(id to now))
            return
        }
        val m = Member(id, a.line.name, a.line.link.remoteHost, a.hello.udpPort, a.hello.tcpPort, device = a.hello.device)
        synchronized(deliverLock) { members = members + (id to m) }
        val feed = Feed(a.line)
        synchronized(lines) { feeds[id] = feed }
        feed.start()
        roster()
        watch(id)
        news("${m.name} is watching")
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
        all.map { if (it.id == localId) it.copy(host = line.link.localHost) else it }

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
    private fun seat(
        news: String?,
        gone: Int? = null,
        moves: Map<Int, Int> = emptyMap(),
        fromStart: Boolean = false,
        replace: Map<Int, Member> = emptyMap(),
        hold: Int? = null,
        /** A saved game everyone carries on from (the owner's game too), instead of the owner's game as it stands. */
        fromState: ByteArray? = null,
    ) {
        if (closed || !isOwner) return
        val ln = listener ?: return
        val next = members.toMutableMap()
        if (gone != null) {
            next.remove(gone)
            holdUntil.remove(gone)
        }
        // The room's first seating: its owner is player 1.
        next[localId] = (members[localId] ?: Member(localId, name, "", 0, 0, seat = 0))
            .copy(name = name, host = "", udpPort = packets.port, tcpPort = incoming.port, device = device)
        for ((id, seat) in moves) next[id]?.let { next[id] = it.copy(seat = seat) }
        // A seat just given to someone: they get a moment to start in the game.
        for ((id, seat) in moves) if (seat >= 0 && next[id] != null) seatedAt[seat] = System.currentTimeMillis()
        next.putAll(replace)
        if (hold != null) next[hold]?.let { next[hold] = it.copy(away = true) }
        // The line for seats: those seated now are out of it, the rest keep their order.
        val waiting = next.values.filter { it.seat < 0 && it.line > 0 }.sortedBy { it.line }.map { it.id }
        for ((id, m) in next) next[id] = m.copy(line = if (m.seat < 0) waiting.indexOf(id) + 1 else 0)
        // Held seats say how long they're held for.
        val nowMs = System.currentTimeMillis()
        for ((id, m) in next) {
            if (m.away) next[id] = m.copy(holdLeft = (((holdUntil[id] ?: nowMs) - nowMs) / 1000).toInt().coerceAtLeast(0))
        }
        // Phones whose connection is gone aren't in (a held player stays, away).
        val live = ownerLines().associateBy { it.id }
        next.keys.retainAll { it == localId || it in live || next[it]?.away == true }
        val state = fromState ?: if (fromStart) startState else null
        val sent = state ?: runCatching { ln.captureState() }.getOrElse { return }
        val packed = pack(sent)
        // The first game after taking the room over skips a number: the old owner
        // may have handed some phones game epoch+1 already (from another state).
        val nextEpoch = epoch + 1 + (if (tookOver) 1 else 0)
        tookOver = false
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
        apply(Seating(nextEpoch, localId, next.values.toList()), if (fromStart || fromState != null) state else null, 0)
        // Watchers through their feeds, so a slow one holds nobody up.
        synchronized(lines) {
            for ((id, feed) in feeds) {
                if (id !in next) continue
                feed.seating(Seating(nextEpoch, localId, membersFor(feed.line, next.values)), packed, nextEpoch)
            }
        }
        if (news != null) news(news)
    }

    /**
     * A watcher asks for a seat (on the worker, so the first request in gets
     * it): the lowest free one, else the lowest one whose player the game
     * shows out ([RoomListener.seatFree]); that player goes back to watching.
     */
    private fun sit(id: Int) {
        if (closed || !isOwner) return
        val m = members[id]
        if (m == null) {
            tell(id, "Still joining: try again in a moment.")
            return
        }
        if (m.seat >= 0) return
        // Someone's in line before it: it joins the line, and they're seen to first.
        val first = inLine().firstOrNull()
        if (first != null && first != id) {
            if (m.line == 0) {
                setLine(inLine() + id)
                tell(id, "You're ${ordinal(inLine().size)} in line for a seat.")
            }
            serveLine()
            return
        }
        if (!seatFor(id, m)) {
            // Nothing free: in line, and seated as soon as a seat opens.
            if (m.line == 0) setLine(inLine() + id)
            val place = inLine().indexOf(id) + 1
            val h = held.values.minByOrNull { it.seat }
            tell(
                id,
                if (h != null) {
                    val left = ((holdEnds(h.id) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    "Player ${h.seat + 1}'s seat is held for ${h.name} (${left} s). You're ${ordinal(place)} in line."
                } else {
                    "All seats are in use: you're ${ordinal(place)} in line. A seat opens when a player's game is over."
                },
            )
        }
    }

    // ----- The line for seats (the owner keeps it; everyone sees it in the members) -----

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    // The watchers in line, first first.
    private fun inLine(): List<Int> = members.values.filter { it.line > 0 && it.seat < 0 }.sortedBy { it.line }.map { it.id }

    // The line is now [order] (on the worker): everyone's told.
    private fun setLine(order: List<Int>) {
        val places = order.withIndex().associate { (i, id) -> id to i + 1 }
        synchronized(deliverLock) {
            members = members.mapValues { (id, m) -> m.copy(line = places[id] ?: 0) }
        }
        roster()
    }

    /** Owner (on the worker): a seat that's opened goes to the first in line. */
    private fun serveLine() {
        if (closed || !isOwner) return
        val first = inLine().firstOrNull() ?: return
        val m = members[first] ?: return
        seatFor(first, m)
    }

    /**
     * Seats watcher [id] (on the worker): the lowest free seat, else the
     * lowest whose player the game shows out ([RoomListener.seatFree]; that
     * player goes back to watching). False when there's none.
     */
    private fun seatFor(id: Int, m: Member): Boolean {
        val taken = members.values.map { it.seat }.toSet()
        val free = (0 until maxPlayers).firstOrNull { it !in taken }
        if (free != null) {
            seat("${m.name} is player ${free + 1}", moves = mapOf(id to free))
            return true
        }
        val now = System.currentTimeMillis()
        val ln = listener
        // The game's word for each seat; null where it can't say.
        val word = seats.values.associate { p -> p.seat to ln?.let { runCatching { it.seatFree(p.seat) }.getOrNull() } }
        val idle = synchronized(deliverLock) { seats.keys.associateWith { relay.idleFrames(it) } }
        val out = seats.values.sortedBy { it.seat }.firstOrNull { p ->
            now - (seatedAt[p.seat] ?: 0L) >= seatGraceMs &&
                (word[p.seat] ?: ((idle[p.seat] ?: 0) >= idleLimit))
        }
        if (out == null) return false
        val why = if (word[out.seat] == null) "you weren't playing" else "your game was over"
        tell(out.id, "${why.replaceFirstChar { it.uppercase() }}, so ${m.name} took your seat. Press COIN or START to play again.")
        seat("${m.name} is player ${out.seat + 1} (${out.name}'s ${if (word[out.seat] == null) "turn was idle" else "game was over"})",
            moves = mapOf(id to out.seat, out.id to -1))
        return true
    }

    /** A player gives up their seat (on the worker). */
    private fun stand(id: Int) {
        if (closed || !isOwner) return
        val m = members[id] ?: return
        if (m.seat < 0) {
            if (m.line > 0) {
                setLine(inLine() - id)
                tell(id, "You left the line. You're still watching.")
            }
            return
        }
        seat("${m.name} is watching now", moves = mapOf(id to -1))
    }

    // Tells one phone something.
    private fun tell(id: Int, message: String) {
        if (id == localId) {
            news(message)
            return
        }
        val line = synchronized(lines) { lines[id] } ?: return
        sendTo(line, Msg.NEWS, message.toByteArray())
    }

    /**
     * A phone left (on the worker): a player's seat empties (a seating); a
     * watcher just goes. A player that [dropped] (no goodbye) keeps its seat
     * for [holdMs], to come back to.
     */
    private fun remove(id: Int, news: String, dropped: Boolean = false) {
        if (id == localId || closed) return
        val line = synchronized(lines) {
            arriving.remove(id)
            feeds.remove(id)?.retire()
            lines.remove(id)
        }
        line?.close()
        val m = members[id] ?: return
        if (m.away) return
        if (m.seat >= 0 && dropped && m.device.isNotEmpty() && holdMs > 0) {
            hold(id, "$news. Seat held ${holdMs / 1000} s")
        } else if (m.seat >= 0) {
            seat(news, gone = id)
        } else {
            synchronized(deliverLock) { members = members - id }
            // Its place in line goes to those behind it (roster() tells everyone).
            if (m.line > 0) setLine(inLine()) else roster()
            news(news)
        }
    }

    // Holds [id]'s seat (it dropped out) until it comes back or the hold ends (on the worker).
    private fun hold(id: Int, news: String) {
        if (members[id] == null) return
        holdUntil[id] = System.currentTimeMillis() + holdMs
        seat(news, hold = id)
        // It goes free when the hold ends (the owner's ping loop looks every second).
    }

    // Owner, every second: held seats whose hold has ended go free. (Looked
    // at afresh each time, not one timer per hold: a phone that drops again
    // is held anew, and an owner that took the room over frees the seats it
    // was handed too.)
    private fun releaseEnded() {
        val now = System.currentTimeMillis()
        for (m in members.values) {
            if (!m.away) continue
            val until = holdUntil[m.id] ?: (now + holdMs).also { holdUntil[m.id] = it }
            if (until <= now) worker.execute { release(m.id, "${m.name} didn't come back: player ${m.seat + 1}'s seat is free") }
        }
    }

    // ----- Two owners -----
    //
    // A split network (the owner's Wi-Fi went and the others took the room
    // over, say) can leave two phones each running the room. So an owner
    // missing phones keeps asking where they were (seats it holds; alone,
    // everyone) whether one of them runs this room too. Of two owners, the
    // one with fewer phones connected moves (on a tie, the higher member
    // id): it gets itself into the other's room first, then tells its own
    // phones to follow. Whichever looks first, it's the same one that moves,
    // and it gives up its room only once it's in the other: the room is
    // never left with nobody running it.

    @Volatile private var yielding = false
    // The room this phone moved into, for [rejoin] to hand over.
    @Volatile private var moved: Room? = null

    /** Phones connected to this owner, itself included. */
    private fun liveCount(): Int = 1 + synchronized(lines) { lines.keys.count { members[it]?.away == false } }

    private fun scoutLoop() {
        while (!closed) {
            Thread.sleep(SCOUT_MS + Random.nextLong(SCOUT_MS))
            if (!isOwner || offline || yielding || closed) continue
            val connected = synchronized(lines) { lines.keys.toSet() }
            val targets = LinkedHashSet<Address>()
            for (m in members.values) {
                if (m.away && m.id !in connected && m.host.isNotEmpty() && m.tcpPort > 0) targets += Address(m.host, m.tcpPort)
            }
            if (connected.isEmpty()) targets += peers
            for (at in targets) {
                if (closed || !isOwner || yielding || offline) break
                val found = runCatching { probe(transport, at, LOOK_MS) }.getOrNull() ?: continue
                if (found.session != session || found.owner < 0 || found.owner == localId) continue
                val ours = liveCount()
                log("${found.name} runs this room too (${found.live} phones; here $ours)")
                if (found.live > ours || (found.live == ours && found.owner < localId)) {
                    moveTo(at, found.name)
                    break
                }
            }
        }
    }

    // Into the other owner's room, then everyone here follows.
    private fun moveTo(at: Address, winner: String) {
        yielding = true
        // Taking nobody new from here (it refuses them while moving anyway):
        // its port is free for the room it moves into (see ownPort).
        incoming.close()
        val next = try {
            join(
                transport, at, name, set, rom, bios, ownPort(), beaconPort, build, device,
                session = session, connectMs = LOOK_MS, faults = faults,
            )
        } catch (e: IOException) {
            // It isn't running the room after all, or not now: this one goes on.
            log("Couldn't move to $winner's room: ${e.message}")
            yielding = false
            return
        }
        moved = next
        foundAt = at
        val payload = encode(Moved(at.host, at.port))
        val telling = ownerLines().map { line ->
            Thread({ runCatching { line.send(Msg.MOVED, payload) } }, "net-moved").apply {
                isDaemon = true
                start()
            }
        }
        telling.forEach { it.join(MOVED_MS) }
        lost("$winner has the room: rejoining")
    }

    // A held seat goes free (on the worker).
    private fun release(id: Int, news: String, now: Boolean = false) {
        if (closed || !isOwner || members[id]?.away != true) return
        // Held anew since (it came back and dropped again): not yet. (The host
        // freeing it by hand [now]: at once.)
        if (!now && (holdUntil[id] ?: 0L) > System.currentTimeMillis()) return
        seat(news, gone = id)
    }

    private fun resync() {
        // Every phone notices the same difference: one seating fixes it.
        val now = System.nanoTime()
        if (now - lastResync < 3_000_000_000L) return
        lastResync = now
        seat(null)
    }

    /**
     * [id] wants everyone to carry on from a saved game (on the worker): a
     * player's or the owner's, never a watcher's (it would replace the game
     * under the players). One at a time: two at once mean the first.
     */
    private fun loadSaved(id: Int, state: ByteArray) {
        if (closed || !isOwner) return
        val m = members[id]
        if (m == null || (m.seat < 0 && id != localId)) {
            tell(id, "Only players can load a saved game for everyone.")
            return
        }
        val now = System.nanoTime()
        if (now - lastLoad < 3_000_000_000L) {
            tell(id, "A saved game was just loaded. Try again in a moment.")
            return
        }
        if (state.isEmpty() || runCatching { listener?.canLoad(state) }.getOrNull() != true) {
            tell(id, "That saved game doesn't load here: it's from another game or another version of it.")
            return
        }
        lastLoad = now
        seat("${m.name} loaded a saved game", fromState = state)
    }

    @Volatile private var lastLoad = 0L

    private fun rematch() {
        // Two players tapping Restart at once mean one restart.
        val now = System.nanoTime()
        if (now - lastRematch < 2_000_000_000L || startState == null) return
        lastRematch = now
        seat("Restarted", fromStart = true)
    }

    private fun acceptLoop() {
        while (!closed) {
            val link = try {
                incoming.accept()
            } catch (e: IOException) {
                return
            }
            thread("net-hello") { greet(link) }
        }
    }

    /** A new connection: someone joining (or asking what's here), or a new owner taking over. */
    private fun greet(link: Link) {
        try {
            if (offline) throw IOException("Cut off")
            link.timeoutMs = 30_000
            val line = line(link, -1, "")
            val (t, payload) = line.receive()
            when (t) {
                Msg.TAKEOVER -> takeover(line, decode(payload))
                Msg.HELLO -> welcome(line, decode(payload))
                else -> link.close()
            }
        } catch (e: Exception) {
            link.close()
        }
    }

    // Anyone with the room's game comes in, to watch until they take a seat.
    private fun welcome(line: Line, hello: Hello) {
        fun refuse(reason: String, redirect: Member? = null) {
            log("Turned ${hello.name.ifEmpty { "a phone" }} away: $reason")
            line.send(Msg.WELCOME, encode(Welcome(false, reason, ownerHost = redirect?.host.orEmpty(), ownerPort = redirect?.tcpPort ?: 0)))
            line.close()
        }
        // Only the phone running the room answers for it (a phone looking for
        // its room knows it by its session), but it says who does: a phone
        // finding its room again needs to reach just one of its phones.
        if (!isOwner) {
            val o = members[owner]?.takeIf { (hello.session == 0 || hello.session == session) && it.host.isNotEmpty() && it.tcpPort > 0 && ownerLine != null }
            return refuse("This phone isn't running the room any more. Look for it again.", o)
        }
        if (hello.set == PROBE) {
            // Moving to another owner: not one to move to.
            val live = if (yielding) 0 else liveCount()
            val owner = if (yielding) -1 else localId
            line.send(
                Msg.WELCOME,
                encode(
                    Welcome(
                        false, PROBE, name = name, session = session, set = set, title = title, rom = rom, maxPlayers = maxPlayers, key = key,
                        players = seats.size, watchers = watcherCount, owner = owner, live = live,
                    ),
                ),
            )
            line.close()
            return
        }
        when {
            yielding -> return refuse("Reconnecting: try again in a moment.")
            hello.session != 0 && hello.session != session -> return refuse("That room has ended.")
            hello.protocol != PROTOCOL -> return refuse("Different app versions. Update every phone.")
            hello.set != set -> return refuse("The room is playing a different game.")
            hello.rom != rom -> return refuse("Your copy of the game differs from the host's. Download it from the library on every phone.")
            hello.bios != bios -> return refuse("Your Neo Geo BIOS differs from the host's. Download it from the library on every phone.")
            // Another emulator build can run the same game a little differently,
            // and the phones would drift apart.
            hello.build.isNotEmpty() && build.isNotEmpty() && hello.build != build ->
                return refuse("This phone and the host have different app builds. Install the latest app on every phone.")
        }
        // A phone back before its old connection was noticed gone: that one's
        // dropped now (its seat held), and it comes straight back in on the next try.
        val stale = members.values.find { !it.away && it.id != localId && hello.device.isNotEmpty() && it.device == hello.device }
        if (stale != null) {
            synchronized(lines) { lines[stale.id] }?.close()
            worker.execute { remove(stale.id, "${stale.name} lost the connection", dropped = true) }
            return refuse("Reconnecting: try again in a moment.")
        }
        // A phone whose seat is held comes back as itself.
        val returning = members.values.find { it.away && hello.device.isNotEmpty() && it.device == hello.device }
        val id = synchronized(lines) {
            (returning?.id ?: freeId())?.also { id ->
                line.id = id
                line.name = hello.name
                arriving[id] = Arriving(line, hello)
            }
        } ?: return refuse("The room is full: $MAX_MEMBERS phones at most.")
        line.link.timeoutMs = 0
        try {
            line.send(Msg.WELCOME, encode(Welcome(true, name = name, delay = startDelay, session = session, id = id, maxPlayers = maxPlayers, owner = localId, key = key)))
            line.send(Msg.START_STATE, pack(startState ?: ByteArray(0)))
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
     * seating lock), to relay to watchers.
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

        /** Frames since [seat] last pressed anything (for a game that can't say who's out). */
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

        fun post(type: Byte, payload: ByteArray) = queue.add { line.send(type, payload) }

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
                        line.send(Msg.PING, byteArrayOf(localId.toByte()))
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
            line.send(Msg.INPUTS, batch.encode())
            next = from + batch.count
            lastWrite = System.nanoTime()
        }

        val nextFrame: Int get() = if (game == epoch) next else -1
    }

    // A seating or watch and the game state it starts from, back to back:
    // nothing another thread sends on the line can come between them.
    private fun sendWithState(line: Line, type: Byte, payload: ByteArray, state: ByteArray) {
        line.send(type to payload, Msg.STATE to state)
    }

    // The oldest frame a watcher still needs (none: MAX).
    private fun feedsFrom(): Int = synchronized(lines) { feeds.values.map { it.nextFrame }.filter { it >= 0 }.minOrNull() } ?: Int.MAX_VALUE

    // ----- A member's side -----

    /** Messages from the owner (on a member). */
    private fun memberLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.receive()
                // A line from an owner that's been replaced says nothing more.
                if (line !== ownerLine) return
                if (faults.drops) continue
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
                    Msg.NEWS -> news(String(payload))
                    Msg.BYE -> {
                        ownerGone(line)
                        return
                    }
                    Msg.MOVED -> {
                        val to = decode<Moved>(payload)
                        foundAt = Address(to.host, to.port)
                        lost("${nameOf(owner)} moved the room: rejoining")
                        return
                    }
                }
            }
        } catch (e: IOException) {
            // Gone without a goodbye: dropped.
            if (!closed && line === ownerLine) log("Connection to the host broke: ${e.message ?: e.javaClass.simpleName}")
            ownerGone(line, "lost the connection", dropped = true)
        }
    }

    // The state after a SEATING or WATCH (pings and old inputs in between go by).
    private fun stateAfter(line: Line): ByteArray {
        while (true) {
            val (t, payload) = line.receive()
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
            val players = others.filter { it.seat >= 0 && !it.away }.map { it.id }.sorted()
            val playing = (next[localId]?.seat ?: -1) >= 0
            val ownerWatches = (next[seating.owner]?.seat ?: -1) < 0
            // Held seats: when they go free, as told.
            if (!isOwner) {
                holdUntil.clear()
                val nowMs = System.currentTimeMillis()
                for (m in next.values) if (m.away) holdUntil[m.id] = nowMs + m.holdLeft * 1000L
            }
            ln?.onSeating(epoch, activeMask, state, from)
            // Players trade inputs with the other players (and an owner who
            // watches, to relay them); an owner who watches gets them from the
            // players; other watchers get them from the owner.
            lockstep = when {
                playing -> Lockstep(delayFor(players.mapNotNull { next[it]?.host }), localId, players(), players, if (ownerWatches && seating.owner != localId) listOf(seating.owner) else emptyList())
                isOwner -> Lockstep(lockstep.delay, -1, players(), players)
                else -> Lockstep(lockstep.delay, -1, players(), emptyList())
            }
            if (isOwner) relay.reset()
            latestSum = null
            synchronized(mySums) {
                mySums.clear()
                theirSums.clear()
                reportedDesync.clear()
            }
            advantage.fill(0)
            theirAdvantage.fill(0)
            timeSync.reset()
            paused.fill(false)
            val now = System.nanoTime()
            for (m in others) lastHeard[m.id] = now
            gap.fill(0)
            // Where to look for the room should it be lost: the owner first.
            val reachable = others.filter { it.host.isNotEmpty() && it.tcpPort > 0 }.sortedBy { if (it.id == seating.owner) -1 else it.id }
            val found = reachable.map { Address(it.host, it.tcpPort) }
            // The owner keeps those who've gone too: one of them may be running the room now.
            peers = if (isOwner) (found + peers).distinct() else found.ifEmpty { peers }
        }
        key = keyNow()
    }

    // The room's key: the owner's address as this phone reaches it.
    private fun keyNow(): String =
        if (isOwner) {
            transport.key("", incoming.port)
        } else {
            members[owner]?.takeIf { it.host.isNotEmpty() }?.let { transport.key(it.host, it.tcpPort) } ?: ""
        }

    /**
     * The owner's [line] ended: the member with the lowest id takes the room
     * over. If it [dropped] (no goodbye), its seat is held, and a phone that
     * can't reach anyone (nor be reached) has lost the room rather than taken it.
     */
    private fun ownerGone(line: Line, why: String = "left", dropped: Boolean = false) {
        val old: Int
        val rest: List<Int>
        synchronized(ownerLock) {
            // A new owner may have called first: then the old line ending is old news.
            if (closed || line !== ownerLine || !migrating.compareAndSet(false, true)) return
            old = owner
            line.close()
            ownerLine = null
            // A player away (seat held) isn't here to take over.
            rest = members.values.filter { it.id != old && !it.away }.map { it.id }.sorted()
            if (rest.firstOrNull().let { it != null && it != localId }) takenOver = CountDownLatch(1)
        }
        val news = "${nameOf(old)} $why"
        log("The host (${nameOf(old)}) $why; next host: ${rest.firstOrNull()?.let(::nameOf) ?: "nobody"}")
        if (epoch == 0) {
            // Not in yet: nothing to take over.
            migrating.set(false)
            // Coming back from a drop: the room is still there (held seat and
            // all), so it's lost again, to rejoin once more. Bad Wi-Fi can
            // drop a phone twice in a row; it mustn't end its game for good.
            if (rejoined) lost("$news. Reconnecting…") else listener?.onClosed("$news before you got in")
            return
        }
        // Dropped: maybe only this phone's connection broke, and the host is
        // fine. Then it's back to the host, not on to a new one.
        val o = members[old]
        if (dropped && o != null && o.host.isNotEmpty() && o.tcpPort > 0) {
            thread("net-host-check") {
                val alive = !offline && runCatching { probe(transport, Address(o.host, o.tcpPort), LOOK_MS) }.getOrNull()
                    ?.let { it.session == session && it.owner == old } == true
                // Closed, or a new owner called meanwhile: nothing to do.
                if (closed || ownerLine != null) return@thread
                if (alive) {
                    log("The host is still there: reconnecting to it")
                    foundAt = Address(o.host, o.tcpPort)
                    migrating.set(false)
                    lost("$news. Reconnecting…")
                } else {
                    nextOwner(old, rest, news, dropped)
                }
            }
            return
        }
        nextOwner(old, rest, news, dropped)
    }

    // The host is gone: the next phone takes the room over, or this one waits for it to call.
    private fun nextOwner(old: Int, rest: List<Int>, news: String, dropped: Boolean) {
        val next = rest.firstOrNull()
        if (next == null || next == localId) {
            worker.execute {
                becomeOwner(old, rest.filter { it != localId }, news, dropped)
                migrating.set(false)
            }
        } else {
            // Wait for the next owner to call.
            thread("net-wait-owner") {
                if (!takenOver.await(takeoverMs, TimeUnit.MILLISECONDS) && !closed) {
                    migrating.set(false)
                    if (dropped) lost("$news. Reconnecting…") else listener?.onClosed("$news, and nobody took the room over")
                }
            }
        }
    }

    /**
     * This phone owns the room now: it calls the others and seats everyone
     * without [old] (or holding its seat, if it [dropped]). Reaching nobody
     * after a drop, it's this phone that's cut off: it has lost the room.
     */
    // Set on taking the room over, until its first seating (see seat()).
    @Volatile private var tookOver = false

    private fun becomeOwner(old: Int, others: List<Int>, news: String, dropped: Boolean) {
        val reached = java.util.concurrent.atomic.AtomicInteger()
        if (dropped && others.isEmpty()) {
            lost("$news. Reconnecting…")
            return
        }
        owner = localId
        tookOver = true
        // All at once: one unreachable phone mustn't make the others give up waiting.
        val calls = others.mapNotNull { id ->
            val m = members[id] ?: return@mapNotNull null
            Thread {
                var link: Link? = null
                try {
                    if (offline) throw IOException("Cut off")
                    val l = transport.connect(Address(m.host, m.tcpPort), 3_000).also { link = it }
                    val line = line(l, id, m.name)
                    line.send(Msg.TAKEOVER, encode(Takeover(session, localId)))
                    // It follows only if it lost the host too (otherwise it hangs up).
                    l.timeoutMs = 3_000
                    if (line.receive().first != Msg.TAKEN) throw IOException("Didn't follow")
                    l.timeoutMs = 0
                    line.lastHeard = System.nanoTime()
                    synchronized(lines) { lines[id] = line }
                    reached.incrementAndGet()
                    thread("net-tcp-$id") { ownerLoop(line) }
                } catch (e: Exception) {
                    // Unreachable, or still with the host: not in the next game.
                    link?.close()
                }
            }.apply { start() }
        }
        calls.forEach { it.join(4_000) }
        log("Taking the room over: reached ${reached.get()} of ${others.size}")
        if (dropped && reached.get() == 0) {
            owner = old
            lost("$news. Reconnecting…")
            return
        }
        startBeacon()
        val m = members[old]
        if (dropped && m != null && m.seat >= 0 && m.device.isNotEmpty() && holdMs > 0) {
            hold(old, "$news. You're the host now; ${m.name}'s seat is held ${holdMs / 1000} s")
        } else {
            seat("$news. You're the host now", gone = old)
        }
    }

    /**
     * This phone lost the room: it stops (quietly: no goodbye, so its seat is
     * held) and says so; [rejoin] gets it back in.
     */
    private fun lost(reason: String) {
        if (closed) return
        log("Lost the room: $reason")
        val ln = listener
        vanish()
        ln?.onLost(reason)
    }

    /**
     * The port this phone listened on, to listen on again in the room it
     * rejoins: where the others remember it, so a phone finding the room
     * again (or a new owner calling) reaches it there.
     */
    private fun ownPort(): Int = incoming.port.takeIf { it > 0 } ?: listenPort

    /**
     * While finding the room again: a host's beacon heard on the network
     * ([BeaconListener]). If it's this room's, its owner is tried first, at
     * the address it has now (a phone can come back to the Wi-Fi with another).
     */
    fun heard(beacon: Beacon, from: InetAddress) {
        if (beacon.session != session || beacon.owner < 0 || beacon.owner == localId) return
        val at = Address(from.hostAddress, beacon.tcpPort)
        if (foundAt != at) log("Heard the room's host (${beacon.name}) at $at")
        foundAt = at
    }

    /**
     * After [RoomListener.onLost]: joins the room again (its owner, or
     * whoever took it over, where the phones were last), back into this
     * phone's seat if it's still held. Null if none answers yet: call again
     * in a moment (the room holds the seat for HOLD_SECONDS).
     */
    fun rejoin(connectMs: Int = REJOIN_CONNECT_MS): Room? {
        // Moved into another owner's room already: that's it.
        moved?.let {
            moved = null
            return it
        }
        val tries = ArrayDeque((listOfNotNull(foundAt) + peers).distinct())
        if (tries.isEmpty()) log("Rejoining: nowhere to look")
        val tried = HashSet<Address>()
        while (tries.isNotEmpty()) {
            val at = tries.removeFirst()
            if (!tried.add(at)) continue
            try {
                return join(
                    transport, at, name, set, rom, bios, ownPort(), beaconPort, build, device,
                    session = session, connectMs = connectMs, faults = faults,
                ).also {
                    // If it drops again before its first game: back here, to the same places.
                    it.rejoined = true
                    it.foundAt = at
                    it.peers = peers
                }
            } catch (e: Redirected) {
                // A phone of the room that knows where it is now: there, next.
                log("Rejoining at $at: the host is at ${e.at}")
                foundAt = e.at
                tries.addFirst(e.at)
            } catch (e: IOException) {
                // Not there, not running the room, or not ready for us yet: the next.
                log("Rejoining at $at: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return null
    }

    /** A new owner calls this phone. */
    private fun takeover(line: Line, takeover: Takeover) {
        if (takeover.session != session) {
            line.close()
            return
        }
        // Still hearing from the host (within 3 of the 5 s that make it gone):
        // it's the caller that lost it, not this phone. Stay (the caller finds nobody followed, and rejoins the host).
        val current = ownerLine
        if (current != null && current !== line && takeover.owner != owner && !migrating.get() &&
            System.nanoTime() - current.lastHeard < quietNs * 3 / 5
        ) {
            log("${nameOf(takeover.owner)} tried to take the room over; the host is still here")
            line.close()
            return
        }
        try {
            line.send(Msg.TAKEN)
        } catch (e: IOException) {
            line.close()
            return
        }
        line.id = takeover.owner
        line.name = nameOf(takeover.owner)
        line.link.timeoutMs = 0
        synchronized(ownerLock) {
            // The old owner's line, if this phone hadn't noticed it go: done with.
            ownerLine?.takeIf { it !== line }?.close()
            ownerLine = line
            owner = takeover.owner
            migrating.set(false)
            takenOver.countDown()
        }
        news("${line.name} is the host now")
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
                if (!closed) news("Connection error: ${e.message ?: e.javaClass.simpleName}")
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
        // A partner's word on how far ahead it runs is out of date after this (a few frames).
        private const val FRESH_NS = 50_000_000L
        private const val LINE_QUIET_NS = 10_000_000_000L
        private const val ARRIVING_QUIET_NS = 60_000_000_000L
        private val SKIPPABLE = setOf(Msg.PING, Msg.NEWS, Msg.ROSTER, Msg.PAUSE, Msg.RESUME)
        private const val TAKEOVER_MS = 12_000L
        private const val REJOIN_CONNECT_MS = 2_000
        private const val REJOIN_ANSWER_MS = 5_000
        private const val LOOK_MS = 1_500
        // An owner missing phones looks for another owner every 2 to 4 s.
        private const val SCOUT_MS = 2_000L
        private const val MOVED_MS = 500L
        private const val FEED_STOP_MS = 1_000L
        private const val READY_MS = 3_000L
        private const val PING_MS = 1_000L
        private const val FEED_MS = 16L
        private const val SEAT_GRACE_MS = 8_000L

        /** A game that can't say who's out: a seat whose player pressed nothing this long is free (about a continue countdown). */
        const val FALLBACK_IDLE_SECONDS = 10
        private const val HOLD_SECONDS = 60
        // Inputs the owner keeps beyond what watchers need, for newcomers' snapshots.
        private const val KEEP_FRAMES = 600

        /**
         * Opens a room for [set] with [maxPlayers] seats, this phone in seat
         * 0. Others find it by its key, or its beacon ([beaconPort] -1: none, for tests).
         */
        fun host(
            name: String, set: String, title: String, rom: Long, bios: Long, maxPlayers: Int,
            delay: Int = 1, port: Int = TCP_PORT, beaconPort: Int = BEACON_PORT, build: String = "", device: String = "",
            faults: Faults = Faults(),
            transport: Transport = LanTransport,
        ): Room {
            val incoming = transport.listen(port)
            val packets = try {
                transport.datagrams()
            } catch (e: Exception) {
                incoming.close()
                throw e
            }
            return Room(
                name, set, title, rom, bios, maxPlayers, localId = 0, session = Random.nextInt(), transport = transport,
                incoming = incoming, packets = packets, owner = 0, startDelay = delay, beaconPort = beaconPort,
                build = build, device = device, listenPort = port, faults = faults,
            ).also { it.key = it.keyNow() }
        }

        /**
         * Joins the room at [host]:[port] on the local network, to watch
         * until a seat is taken ([requestSeat]); the room's start state is
         * then in [Room.startState]. [Refused] (with the reason) if it can't.
         */
        fun join(
            host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long,
            listenPort: Int = TCP_PORT, beaconPort: Int = BEACON_PORT, build: String = "", device: String = "",
            /** Coming back to a room it lost: only that room takes it ([rejoin]). */
            session: Int = 0,
            connectMs: Int = 5_000,
            faults: Faults = Faults(),
        ): Room = join(LanTransport, Address(host.hostAddress, port), name, set, rom, bios, listenPort, beaconPort, build, device, session, connectMs, faults)

        /** Joins the room run at [at], over [transport]: as above. */
        fun join(
            transport: Transport, at: Address, name: String, set: String, rom: Long, bios: Long,
            listenPort: Int = TCP_PORT, beaconPort: Int = BEACON_PORT, build: String = "", device: String = "",
            session: Int = 0,
            connectMs: Int = 5_000,
            faults: Faults = Faults(),
        ): Room {
            // Cut off (tests): the host can't be reached.
            if (faults.cut) {
                Thread.sleep(connectMs.toLong())
                throw IOException("Can't reach the host")
            }
            val packets = transport.datagrams()
            // Where a new owner would call this phone, should the owner leave.
            val incoming = try {
                transport.listen(listenPort)
            } catch (e: Exception) {
                packets.close()
                throw e
            }
            var link: Link? = null
            try {
                val l = transport.connect(at, connectMs).also { link = it }
                // Finding a lost room, a phone that doesn't answer soon is
                // one of the room's phones finding it too (listening, not
                // in yet): the next place, rather than a long wait.
                l.timeoutMs = if (session != 0) REJOIN_ANSWER_MS else 30_000
                val line = Line(l, 0, "Host", faults)
                line.send(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, packets.port, incoming.port, build, device, session)))
                val (t, payload) = line.receive()
                if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
                val w = decode<Welcome>(payload)
                if (!w.accepted) {
                    if (w.ownerHost.isNotEmpty() && w.ownerPort > 0) throw Redirected(w.reason, Address(w.ownerHost, w.ownerPort))
                    throw Refused(w.reason)
                }
                // (Anything else first, a ping say, can wait.)
                var start = line.receive()
                while (start.first != Msg.START_STATE && start.first in SKIPPABLE) start = line.receive()
                if (start.first != Msg.START_STATE) throw IOException("Expected the start state")
                l.timeoutMs = 0
                line.id = w.owner
                line.name = w.name
                val room = Room(
                    name, set, "", rom, bios, w.maxPlayers, localId = w.id, session = w.session, transport = transport,
                    incoming = incoming, packets = packets, owner = w.owner, startDelay = w.delay, beaconPort = beaconPort,
                    build = build, device = device, listenPort = listenPort, faults = faults,
                )
                room.ownerLine = line
                room.startState = unpack(start.second).takeIf { it.isNotEmpty() }
                // Until the first game: who's who, for messages.
                room.members = mapOf(w.owner to Member(w.owner, w.name, at.host, 0, at.port, seat = 0))
                room.key = w.key
                return room
            } catch (e: Exception) {
                // Refused, unreachable, or an answer it can't read: nothing left open.
                link?.close()
                incoming.close()
                packets.close()
                throw if (e is IOException) e else IOException("Couldn't join: ${e.message}", e)
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

/** Asks the room at [host]:[port] on the local network which game it's running, as a [Beacon]. */
fun probe(host: InetAddress, port: Int = TCP_PORT, timeoutMs: Int = 5_000): Beacon =
    probe(LanTransport, Address(host.hostAddress, port), timeoutMs)

/** Asks the room run at [at] which game it's running, as a [Beacon]. */
fun probe(transport: Transport, at: Address, timeoutMs: Int = 5_000): Beacon {
    val link = transport.connect(at, timeoutMs)
    try {
        link.timeoutMs = timeoutMs
        link.send(Msg.HELLO, encode(Hello(PROTOCOL, "", PROBE, 0, 0, 0)))
        val (t, payload) = link.receive()
        if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
        val w = decode<Welcome>(payload)
        if (w.reason != PROBE) {
            if (w.ownerHost.isNotEmpty() && w.ownerPort > 0) throw Redirected(w.reason, Address(w.ownerHost, w.ownerPort))
            throw Refused(w.reason)
        }
        return Beacon(
            PROTOCOL, w.name, w.set, w.title, w.rom, at.port, players = w.players, maxPlayers = w.maxPlayers, key = w.key,
            watchers = w.watchers, session = w.session, owner = w.owner, live = w.live,
        )
    } finally {
        link.close()
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
