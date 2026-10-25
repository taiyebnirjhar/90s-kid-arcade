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
import kotlin.random.Random

// Hosting, joining and the live connection between the phones of a game: two,
// or more for games made for more players (Cadillacs and Dinosaurs: three).

class Refused(reason: String) : IOException(reason)

/** What the game screen hears from the connection. Called on network threads. */
interface LinkListener {
    /** [player]'s inputs for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(player: Int, start: Int, masks: IntArray)

    /** The games differ at [frame]. */
    fun onDesync(frame: Int)

    /** The connection ended: someone left, or went quiet. */
    fun onClosed(reason: String)

    /** [player] opened (true) or closed (false) their menu: the game waits for everyone. */
    fun onPartnerPaused(player: Int, paused: Boolean) {}

    /**
     * [player] started a rematch: restart from the session's start state with
     * [Link.restart] until [Link.epoch] reaches [Link.wantedEpoch].
     */
    fun onRematch(player: Int) {}
}

/** Another phone in the game: its player number, name, and where its inputs come from. */
class Peer(val player: Int, val name: String, val address: InetSocketAddress)

/** A TCP connection: a guest's to the host, or one of the host's to a guest ([player] is the far end). */
internal class Line(val socket: Socket, @Volatile var player: Int, @Volatile var name: String) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    fun expect(type: Byte): ByteArray {
        while (true) {
            val (t, payload) = input.message()
            if (t == type) return payload
            if (t == Msg.BYE) throw IOException("$name left")
        }
    }
}

/**
 * The phones of one game, from the start until someone leaves. The host is
 * player 1 (0 here). Before [start]: the host [sendState]s, each guest
 * [receiveState]s and loads it, then [sendReady] / [awaitReady].
 */
class Link internal constructor(
    private val lines: List<Line>,
    private val udp: DatagramSocket,
    /** Everyone else in the game. */
    val peers: List<Peer>,
    val localPlayer: Int,
    startDelay: Int,
    private val session: Int,
) : Closeable {
    /** How many play: this phone and its [peers]. */
    val players: Int = peers.size + 1

    /** This game's inputs; replaced by [restart] for a rematch. */
    @Volatile var lockstep = Lockstep(startDelay, localPlayer, players)
        private set

    /** This phone's input delay now: it adapts to the connection ([DelayTuner]). */
    val delay: Int get() = lockstep.delay
    private val tuner = DelayTuner()

    /** Which game of the session this is: 0, then +1 per rematch. */
    @Volatile var epoch = 0
        private set

    /**
     * The game every phone is heading for. Each rematch request names it, so
     * two players tapping Rematch at once both mean the same next game, not
     * two restarts on one phone and one on the other.
     */
    @Volatile var wantedEpoch = 0
        private set

    @Volatile private var closed = false
    @Volatile private var listener: LinkListener? = null
    private val lastHeard = LongArray(4)
    @Volatile private var lastSent = 0L
    private val mySums = HashMap<Int, Long>()
    private val theirSums = HashMap<Int, MutableList<Long>>()
    @Volatile private var latestSum: Pair<Int, Long>? = null

    // Per player (index = player number): how far this phone runs ahead of
    // their inputs, and what they last told us of theirs and their delay.
    private val advantage = IntArray(4)
    private val theirAdvantage = IntArray(4)
    private val theirDelay = IntArray(4) { startDelay }
    private val paused = BooleanArray(4)
    private val threads = ArrayList<Thread>()

    val isHost: Boolean get() = localPlayer == 0

    /** Everyone else's names, for messages: "Pixel 7" or "Pixel 7 & Galaxy S21". */
    val peerName: String get() = peers.joinToString(" & ") { it.name }

    fun nameOf(player: Int): String = peers.find { it.player == player }?.name ?: "Player ${player + 1}"

    /** The partner furthest ahead of our inputs (they guess the most): what the delay tuner watches. */
    val partnerAdvantage: Int get() = peers.maxOf { theirAdvantage[it.player] }

    /** Someone else has their menu open. */
    val partnerPaused: Boolean get() = peers.any { paused[it.player] }

    /** Who has their menu open, for "X paused the game". */
    val pausedName: String? get() = peers.firstOrNull { paused[it.player] }?.name

    /** Notes how far this phone ([emulated]: its next frame) runs ahead of each partner's inputs. */
    fun updateAdvantage(emulated: Int) {
        for (p in peers) advantage[p.player] = emulated - lockstep.known(p.player)
    }

    /**
     * How many frames this phone's game runs ahead of the slowest partner's
     * (time sync waits when it's 2 or more). Each advantage counts frames ahead
     * of the other's inputs, which arrive earlier by that phone's input delay;
     * adding it back compares the games themselves, whatever the delays.
     */
    val lead: Int
        get() = peers.maxOf { p ->
            (advantage[p.player] + theirDelay[p.player]) - (theirAdvantage[p.player] + delay)
        }

    fun sendState(state: ByteArray) = lines.forEach { it.output.message(Msg.STATE, state) }

    fun receiveState(): ByteArray = lines.single().expect(Msg.STATE)

    fun sendReady() = lines.single().output.message(Msg.READY)

    /** Host: waits until every guest has loaded the start state. */
    fun awaitReady() = lines.forEach { it.expect(Msg.READY) }

    /** Starts exchanging inputs. */
    fun start(listener: LinkListener) {
        this.listener = listener
        val now = System.nanoTime()
        for (p in peers) lastHeard[p.player] = now
        thread("net-udp") { receiveLoop() }
        for (line in lines) thread("net-tcp-${line.player}") { controlLoop(line) }
        thread("net-send") { sendLoop() }
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
        // The game and its number together: a rematch between reading one and
        // the other would label the old game's inputs as the new one's.
        val (ls, game, sum) = synchronized(deliverLock) { Triple(lockstep, epoch, latestSum) }
        for (p in peers) {
            val (start, masks) = ls.outgoing(p.player, InputPacket.MAX_MASKS)
            val packet = InputPacket(
                session, ls.known(p.player), start, masks, sum?.first ?: -1, sum?.second ?: 0,
                advantage[p.player], game and 0xFF, ls.delay, localPlayer,
            )
            val bytes = packet.encode()
            // A Wi-Fi hiccup can fail one send: the inputs go again with the
            // next packet, and a partner gone for good shows as silence.
            try {
                udp.send(DatagramPacket(bytes, bytes.size, p.address))
            } catch (e: IOException) {
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
            // Keep a few recent ones only.
            mySums.keys.removeAll { it < frame - 1200 }
            theirSums.keys.removeAll { it < frame - 1200 }
            val mine = mySums[frame]
            mine != null && theirSums[frame].orEmpty().any { it != mine }
        }
        if (differs) listener?.onDesync(frame)
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
                // As for sending: one failure isn't the end; silence is.
                checkQuiet()
                Thread.sleep(20)
                continue
            }
            val p = InputPacket.decode(buf, packet.length) ?: continue
            if (p.session != session || peers.none { it.player == p.from }) continue
            lastHeard[p.from] = System.nanoTime()
            val current = synchronized(deliverLock) {
                // From the previous game (still in flight) or the next one
                // (that phone restarted first): not this game's inputs.
                if (p.epoch != (epoch and 0xFF)) return@synchronized false
                theirAdvantage[p.from] = p.advantage
                theirDelay[p.from] = p.delay
                lockstep.receive(p.from, p.start, p.masks, p.ack)
                // Under the lock too, so a rematch can't clear the sums in between.
                if (p.sumFrame >= 0) check(p.sumFrame, p.sum, from = p.from)
                true
            }
            if (!current) continue
            deliver(p.from)
            checkQuiet()
        }
    }

    private fun checkQuiet() {
        val now = System.nanoTime()
        peers.firstOrNull { now - lastHeard[it.player] > QUIET_NS }?.let { end("Lost the connection to ${it.name}") }
    }

    // Pause, rematch and leaving travel over TCP, through the host: a guest's
    // message reaches the host, which passes it on to the other guests.
    private fun controlLoop(line: Line) {
        try {
            while (!closed) {
                val (t, payload) = line.input.message()
                val from = payload.firstOrNull()?.toInt() ?: line.player
                when (t) {
                    Msg.BYE -> {
                        // The host passes on who left, so every phone names them.
                        end("${nameOf(from)} left the game", leaver = from)
                        return
                    }
                    Msg.PAUSE, Msg.RESUME -> {
                        paused[from] = t == Msg.PAUSE
                        relay(line, t, payload)
                        listener?.onPartnerPaused(from, t == Msg.PAUSE)
                    }
                    Msg.REMATCH -> {
                        relay(line, t, payload)
                        val target = payload.getOrNull(1)?.toInt()?.and(0xFF)
                        if (target == null || wantRematch(target)) listener?.onRematch(from)
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed) end("${line.name} disconnected")
        }
    }

    private fun relay(source: Line, type: Byte, payload: ByteArray) {
        if (!isHost) return
        for (other in lines) if (other !== source) runCatching { other.output.message(type, payload) }
    }

    /** A request for game [target] (mod 256): true if it's a game beyond the one we're heading for. */
    @Synchronized private fun wantRematch(target: Int): Boolean {
        val ahead = (target - wantedEpoch) and 0xFF
        if (ahead == 0 || ahead >= 0x80) return false
        wantedEpoch += ahead
        return true
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
            val pending = peers.any { ls.outgoing(it.player, 1).second.isNotEmpty() }
            val idle = System.nanoTime() - lastSent
            if (now || (pending && idle > RESEND_NS) || idle > KEEPALIVE_NS) send()
        }
    }

    /**
     * Samples our input for every frame up to [emulated] + delay (see
     * [Lockstep.sampleUpTo]) and sends it. Returns the new inputs for the
     * emulator: (first frame, masks). Call once per displayed frame.
     */
    fun sample(emulated: Int, input: () -> Int): Pair<Int, IntArray> {
        val new = lockstep.sampleUpTo(emulated, input)
        if (new.second.isNotEmpty()) flush()
        lockstep.delay = tuner.update(lockstep.delay, partnerAdvantage)
        return new
    }

    /** Tells everyone our menu opened or closed. */
    fun sendPause(paused: Boolean) = sendControl(if (paused) Msg.PAUSE else Msg.RESUME)

    /**
     * A rematch from this phone: the game after the one we're heading for.
     * Restart here until [epoch] reaches [wantedEpoch], and tell everyone
     * with [sendRematch] (a network call: off the main thread).
     */
    @Synchronized fun nextRematch(): Int = ++wantedEpoch

    /** Asks everyone to restart for game [target] too. */
    fun sendRematch(target: Int) = sendControl(Msg.REMATCH, byteArrayOf(localPlayer.toByte(), target.toByte()))

    private fun sendControl(type: Byte, payload: ByteArray = byteArrayOf(localPlayer.toByte())) {
        for (line in lines) runCatching { line.output.message(type, payload) }
    }

    /**
     * Starts the next game of the session: inputs from frame 0 again, under a
     * new epoch so the old game's packets still in flight are ignored. Call
     * with the emulator stopped. [reset] (resetting the emulator's input
     * counters) runs at the switch, so no input of either game slips between.
     */
    fun restart(reset: () -> Unit = {}) {
        synchronized(deliverLock) {
            reset()
            lockstep = Lockstep(lockstep.delay, localPlayer, players)
            epoch++
            latestSum = null
            synchronized(mySums) {
                mySums.clear()
                theirSums.clear()
            }
            advantage.fill(0)
            theirAdvantage.fill(0)
        }
    }

    private val deliverLock = Any()

    // One lock keeps each partner's inputs reaching the emulator in order.
    private fun deliver(from: Int) {
        synchronized(deliverLock) {
            val (start, masks) = lockstep.takeRemote(from)
            if (masks.isNotEmpty()) listener?.onRemote(from, start, masks)
        }
    }

    private fun end(reason: String, leaver: Int = localPlayer) {
        if (closed) return
        // Tell the others the game is over, rather than leaving them to time
        // out: a guest tells the host, which tells everyone.
        close(sendBye = true, leaver = leaver)
        listener?.onClosed(reason)
    }

    fun close(sendBye: Boolean, leaver: Int = localPlayer) {
        if (closed) return
        closed = true
        threads.forEach { it.interrupt() }
        // Called from the game screen (Android's main thread, where network
        // calls aren't allowed): say goodbye and close on a thread of its own.
        Thread({
            for (line in lines) {
                if (sendBye) runCatching { line.output.message(Msg.BYE, byteArrayOf(leaver.toByte())) }
                runCatching { line.socket.close() }
            }
            runCatching { udp.close() }
        }, "net-close").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() = close(sendBye = true)

    private fun thread(name: String, body: () -> Unit) {
        // An unexpected error ends the game with a message; uncaught, it
        // would crash the whole app.
        val safe = {
            try {
                body()
            } catch (e: Throwable) {
                if (!closed) end("Connection error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        threads += Thread(safe, name).apply {
            isDaemon = true
            // Inputs go out and come in the moment they're ready, even while
            // the phone is busy drawing the game.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    companion object {
        private const val RESEND_NS = 12_000_000L
        private const val KEEPALIVE_NS = 100_000_000L
        private const val QUIET_NS = 6_000_000_000L
    }
}

/**
 * Hosts a game on [port] (0: any free port, for tests) for up to [maxPlayers]
 * phones, this one included. Guests join one by one ([acceptNext]); [start]
 * begins the game with whoever has joined.
 */
class NetHost(
    private val name: String,
    private val set: String,
    private val title: String,
    private val rom: Long,
    private val bios: Long,
    val maxPlayers: Int = 2,
    private val delay: Int = 1,
    port: Int = TCP_PORT,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    private val udp = DatagramSocket(0)
    private val session = Random.nextInt()

    private class Guest(val line: Line, val address: InetAddress, val udpPort: Int)

    private val guests = ArrayList<Guest>()
    private var started = false

    val port: Int get() = server.localPort

    /** Names of the guests in so far, in player order. */
    val joined: List<String> get() = synchronized(guests) { guests.map { it.line.name } }

    /** Everyone's in: the game can't take more. */
    val full: Boolean get() = synchronized(guests) { guests.size + 1 >= maxPlayers }

    /**
     * Drops guests who left before the start: they said goodbye, or their
     * connection is gone. Nothing else arrives from a guest until then, so a
     * quick look at each line is enough.
     */
    fun prune() = synchronized(guests) {
        guests.removeAll { g ->
            val gone = try {
                g.line.socket.soTimeout = 1
                g.line.input.message()
                true // a goodbye (or anything else: not a guest waiting quietly)
            } catch (e: SocketTimeoutException) {
                false
            } catch (e: IOException) {
                true
            } finally {
                runCatching { g.line.socket.soTimeout = 0 }
            }
            if (gone) runCatching { g.line.socket.close() }
            gone
        }
    }

    /**
     * Blocks until the next guest joins, and returns its name; null once the
     * host has started or stopped. [decide] sees each request and returns
     * null to accept or a reason to refuse; requests for a different game,
     * or when the game is full, are refused before it's asked.
     */
    fun acceptNext(decide: (Hello) -> String?): String? {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return null
            }
            try {
                socket.tcpNoDelay = true
                // Not numbered yet: the player number is given on acceptance.
                val line = Line(socket, -1, "")
                val output = line.output
                socket.soTimeout = 120_000
                val (t, payload) = line.input.message()
                if (t != Msg.HELLO) throw IOException("Expected a hello")
                val hello = decode<Hello>(payload)
                if (hello.set == PROBE) {
                    output.message(Msg.WELCOME, encode(Welcome(false, PROBE, name = name, set = set, title = title, rom = rom, maxPlayers = maxPlayers)))
                    socket.close()
                    continue
                }
                prune()
                val reason = when {
                    hello.protocol != PROTOCOL -> "Different app versions. Update every phone."
                    hello.set != set -> "The host is playing a different game."
                    hello.rom != rom -> "Your copy of the game differs from the host's. Download it from the library on every phone."
                    hello.bios != bios -> "Your Neo Geo BIOS differs from the host's. Download it from the library on every phone."
                    full -> "The game is full."
                    else -> decide(hello)
                }
                if (reason != null) {
                    output.message(Msg.WELCOME, encode(Welcome(false, reason)))
                    socket.close()
                    continue
                }
                val player = synchronized(guests) {
                    // The host tapped Start while this one was being asked.
                    if (started) return@synchronized -1
                    val player = guests.size + 1
                    line.player = player
                    line.name = hello.name
                    guests += Guest(line, socket.inetAddress, hello.udpPort)
                    player
                }
                if (player < 0) {
                    output.message(Msg.WELCOME, encode(Welcome(false, "The game has already started.")))
                    socket.close()
                    return null
                }
                output.message(
                    Msg.WELCOME,
                    encode(Welcome(true, name = name, delay = delay, udpPort = udp.localPort, session = session, player = player, maxPlayers = maxPlayers)),
                )
                socket.soTimeout = 0
                return hello.name
            } catch (e: IOException) {
                runCatching { socket.close() }
                if (server.isClosed) return null
            }
        }
    }

    /**
     * Starts the game with everyone who has joined (at least one guest):
     * tells each guest who plays and where to send its inputs, and returns
     * the host's side of the game.
     */
    fun start(): Link {
        runCatching { server.close() }
        prune()
        val all = synchronized(guests) {
            started = true
            guests.toList()
        }
        require(all.isNotEmpty()) { "Nobody has joined yet" }
        // Numbered again from 1, in case someone left the lobby: each guest is told its number.
        all.forEachIndexed { i, g -> g.line.player = i + 1 }
        val roster = Roster(
            listOf(RosterEntry(0, name, null, udp.localPort)) +
                all.mapIndexed { i, g -> RosterEntry(i + 1, g.line.name, g.address.hostAddress, g.udpPort) },
        )
        all.forEachIndexed { i, g -> g.line.output.message(Msg.ROSTER, encode(roster.copy(you = i + 1))) }
        val peers = all.mapIndexed { i, g -> Peer(i + 1, g.line.name, InetSocketAddress(g.address, g.udpPort)) }
        return Link(all.map { it.line }, udp, peers, localPlayer = 0, startDelay = delay, session = session)
    }

    /** Two players: waits for one guest, then starts. */
    fun accept(decide: (Hello) -> String?): Link {
        acceptNext(decide) ?: throw IOException("Hosting stopped")
        return start()
    }

    override fun close() {
        runCatching { server.close() }
        // Guests still waiting for the start hear that the host left.
        val all = synchronized(guests) { guests.toList().also { guests.clear() } }
        for (g in all) {
            runCatching { g.line.output.message(Msg.BYE, byteArrayOf(0)) }
            runCatching { g.line.socket.close() }
        }
        runCatching { udp.close() }
    }
}

/** Asks the host at [host]:[port] which game it's running, as a [Beacon]. */
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
        return Beacon(PROTOCOL, w.name, w.set, w.title, w.rom, port, maxPlayers = w.maxPlayers)
    }
}

/** Accepted into a hosted game, waiting for the host to start it. */
class Lobby internal constructor(
    private val line: Line,
    private val udp: DatagramSocket,
    private val welcome: Welcome,
) : Closeable {
    /** This phone's player number for now: 1 for player 2, and so on (final at the start). */
    val player: Int get() = welcome.player

    /** Blocks until the host starts the game; throws if the host leaves instead. */
    fun awaitStart(): Link {
        try {
            val roster = decode<Roster>(line.expect(Msg.ROSTER))
            val me = if (roster.you >= 0) roster.you else welcome.player
            val hostAddress = line.socket.inetAddress
            val peers = roster.players.filter { it.player != me }.map { e ->
                val address = e.host?.let(InetAddress::getByName) ?: hostAddress
                Peer(e.player, e.name, InetSocketAddress(address, e.udpPort))
            }
            return Link(listOf(line), udp, peers, localPlayer = me, startDelay = welcome.delay, session = welcome.session)
        } catch (e: IOException) {
            close()
            throw if (e.message?.endsWith("left") == true) Refused("The host left") else e
        }
    }

    /** Leaves the lobby, telling the host (a network call: off the main thread). */
    override fun close() {
        runCatching { line.output.message(Msg.BYE, byteArrayOf(welcome.player.toByte())) }
        runCatching { line.socket.close() }
        udp.close()
    }
}

/** Asks to join the host at [host]:[port]. Throws [Refused] with the host's reason. */
fun joinLobby(host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long): Lobby {
    val udp = DatagramSocket(0)
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.tcpNoDelay = true
        val line = Line(socket, 0, "Host")
        line.output.message(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, udp.localPort)))
        // The host may take a while to tap Accept, and then Start.
        socket.soTimeout = 0
        val (t, payload) = line.input.message()
        if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
        val welcome = decode<Welcome>(payload)
        if (!welcome.accepted) throw Refused(welcome.reason)
        line.name = welcome.name
        return Lobby(line, udp, welcome)
    } catch (e: IOException) {
        runCatching { socket.close() }
        udp.close()
        throw e
    }
}

/** Joins the host at [host]:[port] and waits for it to start (two players: right away). */
fun join(host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long): Link =
    joinLobby(host, port, name, set, rom, bios).awaitStart()

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
