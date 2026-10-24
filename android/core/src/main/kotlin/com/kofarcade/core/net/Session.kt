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

// Hosting, joining and the live connection between the two phones.

class Refused(reason: String) : IOException(reason)

/** What the game screen hears from the connection. Called on network threads. */
interface LinkListener {
    /** The partner's inputs for frames [start] onwards, in order and without gaps: feed them to the emulator. */
    fun onRemote(start: Int, masks: IntArray)

    /** The two games differ at [frame]. */
    fun onDesync(frame: Int)

    /** The connection ended: the partner left, or went quiet. */
    fun onClosed(reason: String)

    /** The partner opened (true) or closed (false) their menu: the game waits for both. */
    fun onPartnerPaused(paused: Boolean) {}

    /** The partner started a rematch: restart from the session's start state, then [Link.restart]. */
    fun onRematch() {}
}

/**
 * A connected pair of phones, from the handshake until one leaves. The host
 * is player 1. Before [start]: the host [sendState]s, the guest
 * [receiveState]s and loads it, then [sendReady] / [awaitReady].
 */
class Link internal constructor(
    private val socket: Socket,
    private val udp: DatagramSocket,
    private val peer: InetSocketAddress,
    val localPlayer: Int,
    val delay: Int,
    private val session: Int,
    val peerName: String,
) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    /** This game's inputs; replaced by [restart] for a rematch. */
    @Volatile var lockstep = Lockstep(delay, localPlayer)
        private set

    /** Which game of the session this is: 0, then +1 per rematch. */
    @Volatile var epoch = 0
        private set

    @Volatile private var closed = false
    @Volatile private var listener: LinkListener? = null
    @Volatile private var lastHeard = System.nanoTime()
    @Volatile private var lastSent = 0L
    private val sums = HashMap<Int, Long>()
    private val remoteSums = HashMap<Int, Long>()
    @Volatile private var latestSum: Pair<Int, Long>? = null

    /** How many frames this phone runs ahead of the partner's inputs; sent for time sync. */
    @Volatile var advantage = 0

    /** The partner's [advantage], as last heard. */
    @Volatile var partnerAdvantage = 0
        private set
    private val threads = ArrayList<Thread>()

    val isHost: Boolean get() = localPlayer == 0

    fun sendState(state: ByteArray) = output.message(Msg.STATE, state)

    fun receiveState(): ByteArray = expect(Msg.STATE)

    fun sendReady() = output.message(Msg.READY)

    fun awaitReady() {
        expect(Msg.READY)
    }

    private fun expect(type: Byte): ByteArray {
        while (true) {
            val (t, payload) = input.message()
            if (t == type) return payload
            if (t == Msg.BYE) throw IOException("$peerName left")
        }
    }

    /** Starts exchanging inputs. */
    fun start(listener: LinkListener) {
        this.listener = listener
        lastHeard = System.nanoTime()
        thread("net-udp") { receiveLoop() }
        thread("net-tcp") { controlLoop() }
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

    // Only ever on the send thread.
    private fun send() {
        val (start, masks) = lockstep.outgoing(InputPacket.MAX_MASKS)
        val sum = latestSum
        val packet = InputPacket(
            session, lockstep.remoteKnown, start, masks, sum?.first ?: -1, sum?.second ?: 0, advantage, epoch and 0xFF,
        )
        val bytes = packet.encode()
        try {
            udp.send(DatagramPacket(bytes, bytes.size, peer))
            lastSent = System.nanoTime()
        } catch (e: IOException) {
            if (!closed) end("Network error: ${e.message}")
        }
    }

    /** Our game's state checksum after [frame], compared with the partner's. */
    fun reportSum(frame: Int, sum: Long) {
        latestSum = frame to sum
        check(frame, sum, mine = true)
    }

    private fun check(frame: Int, sum: Long, mine: Boolean) {
        val other = synchronized(sums) {
            (if (mine) sums else remoteSums)[frame] = sum
            val o = (if (mine) remoteSums else sums)[frame]
            // Keep a few recent ones only.
            sums.keys.removeAll { it < frame - 1200 }
            remoteSums.keys.removeAll { it < frame - 1200 }
            o
        }
        if (other != null && other != sum) listener?.onDesync(frame)
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        udp.soTimeout = 500
        while (!closed) {
            try {
                udp.receive(packet)
            } catch (e: SocketTimeoutException) {
                if (System.nanoTime() - lastHeard > QUIET_NS) end("Lost the connection to $peerName")
                continue
            } catch (e: IOException) {
                if (!closed) end("Network error: ${e.message}")
                return
            }
            val p = InputPacket.decode(buf, packet.length) ?: continue
            if (p.session != session) continue
            lastHeard = System.nanoTime()
            val current = synchronized(deliverLock) {
                // From the previous game (still in flight) or the next one
                // (the partner restarted first): not this game's inputs.
                if (p.epoch != (epoch and 0xFF)) return@synchronized false
                partnerAdvantage = p.advantage
                lockstep.receive(p.start, p.masks, p.ack)
                true
            }
            if (!current) continue
            if (p.sumFrame >= 0) check(p.sumFrame, p.sum, mine = false)
            deliver()
        }
    }

    private fun controlLoop() {
        try {
            while (!closed) {
                val (t, _) = input.message()
                when (t) {
                    Msg.BYE -> {
                        end("$peerName left the game")
                        return
                    }
                    Msg.PAUSE -> listener?.onPartnerPaused(true)
                    Msg.RESUME -> listener?.onPartnerPaused(false)
                    Msg.REMATCH -> listener?.onRematch()
                }
            }
        } catch (e: IOException) {
            if (!closed) end("$peerName disconnected")
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
            val (_, masks) = lockstep.outgoing(1)
            val idle = System.nanoTime() - lastSent
            if (now || (masks.isNotEmpty() && idle > RESEND_NS) || idle > KEEPALIVE_NS) send()
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
        return new
    }

    /** Tells the partner our menu opened or closed. */
    fun sendPause(paused: Boolean) {
        runCatching { output.message(if (paused) Msg.PAUSE else Msg.RESUME) }
    }

    /** Asks the partner to restart too; then restart here (see [LinkListener.onRematch]). */
    fun sendRematch() {
        runCatching { output.message(Msg.REMATCH) }
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
            lockstep = Lockstep(delay, localPlayer)
            epoch++
            latestSum = null
            synchronized(sums) {
                sums.clear()
                remoteSums.clear()
            }
            advantage = 0
            partnerAdvantage = 0
        }
    }

    private val deliverLock = Any()

    // One lock keeps the partner's inputs reaching the emulator in order.
    private fun deliver() {
        synchronized(deliverLock) {
            val (start, masks) = lockstep.takeRemote()
            if (masks.isNotEmpty()) listener?.onRemote(start, masks)
        }
    }

    private fun end(reason: String) {
        if (closed) return
        close(sendBye = false)
        listener?.onClosed(reason)
    }

    fun close(sendBye: Boolean) {
        if (closed) return
        closed = true
        threads.forEach { it.interrupt() }
        // Called from the game screen (Android's main thread, where network
        // calls aren't allowed): say goodbye and close on a thread of its own.
        Thread({
            if (sendBye) runCatching { output.message(Msg.BYE) }
            runCatching { socket.close() }
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
            start()
        }
    }

    companion object {
        private const val RESEND_NS = 12_000_000L
        private const val KEEPALIVE_NS = 100_000_000L
        private const val QUIET_NS = 6_000_000_000L
    }
}

/** Waits for a partner on [port] (0: any free port, for tests). */
class NetHost(
    private val name: String,
    private val set: String,
    private val title: String,
    private val rom: Long,
    private val bios: Long,
    private val delay: Int = 1,
    port: Int = TCP_PORT,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    val port: Int get() = server.localPort

    /**
     * Blocks until a partner joins. [decide] sees each request and returns
     * null to accept or a reason to refuse; requests with a different game
     * are refused before it's asked.
     */
    fun accept(decide: (Hello) -> String?): Link {
        while (true) {
            val socket = server.accept()
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                socket.soTimeout = 120_000
                val (t, payload) = input.message()
                if (t != Msg.HELLO) throw IOException("Expected a hello")
                val hello = decode<Hello>(payload)
                if (hello.set == PROBE) {
                    output.message(Msg.WELCOME, encode(Welcome(false, PROBE, name = name, set = set, title = title, rom = rom)))
                    socket.close()
                    continue
                }
                val reason = when {
                    hello.protocol != PROTOCOL -> "Different app versions. Update both phones."
                    hello.set != set -> "The host is playing a different game."
                    hello.rom != rom -> "Your copy of the game differs from the host's. Download it from the library on both phones."
                    hello.bios != bios -> "Your Neo Geo BIOS differs from the host's. Download it from the library on both phones."
                    else -> decide(hello)
                }
                if (reason != null) {
                    output.message(Msg.WELCOME, encode(Welcome(false, reason)))
                    socket.close()
                    continue
                }
                val udp = DatagramSocket(0)
                val session = Random.nextInt()
                output.message(Msg.WELCOME, encode(Welcome(true, name = name, delay = delay, udpPort = udp.localPort, session = session)))
                socket.soTimeout = 0
                val peer = InetSocketAddress(socket.inetAddress, hello.udpPort)
                return Link(socket, udp, peer, localPlayer = 0, delay = delay, session = session, peerName = hello.name)
            } catch (e: IOException) {
                runCatching { socket.close() }
                if (server.isClosed) throw e
            }
        }
    }

    override fun close() = server.close()
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
        return Beacon(PROTOCOL, w.name, w.set, w.title, w.rom, port)
    }
}

/** Joins the host at [host]:[port]. Throws [Refused] with the host's reason. */
fun join(host: InetAddress, port: Int, name: String, set: String, rom: Long, bios: Long): Link {
    val udp = DatagramSocket(0)
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.message(Msg.HELLO, encode(Hello(PROTOCOL, name, set, rom, bios, udp.localPort)))
        // The host may take a while to tap Accept.
        socket.soTimeout = 120_000
        val (t, payload) = input.message()
        if (t != Msg.WELCOME) throw IOException("Unexpected answer from the host")
        val welcome = decode<Welcome>(payload)
        if (!welcome.accepted) throw Refused(welcome.reason)
        socket.soTimeout = 0
        val peer = InetSocketAddress(socket.inetAddress, welcome.udpPort)
        return Link(socket, udp, peer, localPlayer = 1, delay = welcome.delay, session = welcome.session, peerName = welcome.name)
    } catch (e: IOException) {
        runCatching { socket.close() }
        udp.close()
        throw e
    }
}

/** Announces a hosted game on the local network (Wi-Fi or hotspot) once a second. */
class BeaconSender(private val beacon: Beacon, private val port: Int = BEACON_PORT) : Closeable {
    @Volatile private var running = true
    private val socket = DatagramSocket().apply { broadcast = true }
    private val thread = Thread({
        val bytes = encode(beacon)
        while (running) {
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
