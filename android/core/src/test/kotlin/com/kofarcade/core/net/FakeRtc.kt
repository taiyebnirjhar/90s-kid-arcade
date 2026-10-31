package com.kofarcade.core.net

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// A stand-in for WebRTC over the simulated internet (FakeNet), so the room
// tests run over WebRtcTransport: offers, answers and candidates go through
// a signaling hub; a connection opens once both sides have the other's
// description and a candidate; messages take the way's latency, inputs are
// lost with its loss, and while it's down the reliable channel stalls and
// the connection reports disconnected. A phone that moves to another
// network needs an ICE restart to be reached again, as with real WebRTC.

/** A network of phones that connect through WebRtcTransport. */
class RtcNet(val net: FakeNet = FakeNet()) : TestNet {
    val hub = SignalHub()
    private val conns = ConcurrentHashMap<String, FakePeer>()
    private var count = 0
    internal val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-rtc").apply { isDaemon = true } }

    @Synchronized
    override fun phone(): Transport = transport("phone${++count}")

    /** A phone's transport; again for the same [host]: that phone's app started over. */
    fun transport(host: String) = WebRtcTransport(hub.join(host), factory(host), { emptyList() })

    /** Simulated WebRTC for the phone [host]. */
    fun factory(host: String): RtcFactory = RtcFactory { _, events -> FakePeer(this, host, events).also { conns[it.id] = it } }

    override fun path(a: String, b: String): Path = net.path(a, b)

    /** [host] switches network (Wi-Fi to 4G): its connections stop until restarted. */
    fun move(host: String) {
        conns.values.filter { it.host == host || it.remote?.host == host }.forEach { it.lostWay() }
    }

    internal fun conn(id: String) = conns[id]
}

/** Carries signals between phones (as the room's Realtime channel would), a little late, in order. */
class SignalHub {
    private val phones = ConcurrentHashMap<String, (Signal) -> Unit>()
    private val post = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-signaling").apply { isDaemon = true } }
    /** Phones whose signaling is cut (their server connection is down). */
    val cut: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val sent = AtomicLong()

    fun join(host: String): Signaling = object : Signaling {
        override val self = host

        override fun send(signal: Signal) {
            if (host in cut) throw java.io.IOException("No connection to the server")
            sent.incrementAndGet()
            val text = signal.encode()
            // Through JSON, as on the wire.
            post.schedule({
                if (signal.to !in cut) Signal.decode(text)?.let { s -> phones[s.to]?.invoke(s) }
            }, 10, TimeUnit.MILLISECONDS)
        }

        override fun listen(handler: (Signal) -> Unit) {
            phones[host] = handler
        }

        override fun close() {
            phones.remove(host)
        }
    }
}

/**
 * One side of a simulated peer connection. Its events come from its own
 * thread, in order, as a real library's come from its signaling thread.
 */
class FakePeer(private val rtc: RtcNet, val host: String, target: RtcPeer.Events) : RtcPeer {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-rtc-events").apply { isDaemon = true } }
    private val events = object : RtcPeer.Events {
        private fun later(f: () -> Unit) {
            runCatching { thread.execute(f) }
        }
        override fun onCandidate(candidate: Candidate) = later { target.onCandidate(candidate) }
        override fun onState(state: PeerState) = later { target.onState(state) }
        override fun onOpen(channel: Int) = later { target.onOpen(channel) }
        override fun onMessage(channel: Int, data: ByteArray) = later { target.onMessage(channel, data) }
    }

    val id: String = UUID.randomUUID().toString()
    @Volatile var remote: FakePeer? = null
    private var hasLocal = false
    private var hasRemote = false
    private var gotCandidate = false
    @Volatile private var connected = false
    @Volatile private var opened = false
    @Volatile private var closed = false
    /** The way's been lost (a phone moved): only an ICE restart finds a new one. */
    @Volatile private var needsRestart = false
    private var restarts = 0
    private var downSince = 0L
    private var reported = PeerState.NEW
    private val lock = Object()
    // (when it's due, System.nanoTime; the message): each takes the way's latency, all in flight at once, in order.
    private val outbox = LinkedBlockingQueue<Pair<Long, ByteArray>>()
    private var lastDue = 0L
    private val queued = AtomicLong()

    private val watch = rtc.ticker.scheduleAtFixedRate(::check, 100, 100, TimeUnit.MILLISECONDS)

    // The reliable channel: one thread delivers in order, stalling while the way is down.
    private val sender = Thread({
        try {
            while (!closed) {
                val (due, data) = outbox.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val r = remote ?: continue
                val wait = (due - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                while (!closed && (!usable())) Thread.sleep(10)
                queued.addAndGet(-data.size.toLong())
                if (!closed) r.deliver(RtcPeer.RELIABLE, data)
            }
        } catch (_: InterruptedException) {
        }
    }, "fake-rtc-send").apply {
        isDaemon = true
        start()
    }

    private fun usable(): Boolean {
        val r = remote ?: return false
        return connected && !needsRestart && !rtc.path(host, r.host).down
    }

    private fun deliver(channel: Int, data: ByteArray) {
        if (!closed && opened) events.onMessage(channel, data)
    }

    /** Voice: what this phone wants (setVoice), and what the last offer and answer agreed (it sends its voice). */
    @Volatile var wantsVoice = false
    @Volatile var sendsVoice = false
    @Volatile var playVolume = 1.0
    private var offers = 0

    override fun setVoice(send: Boolean) {
        wantsVoice = send
    }

    override fun setVoiceVolume(volume: Double) {
        playVolume = volume
    }

    override fun voiceLevel(): CompletableFuture<Double?> = CompletableFuture.completedFuture(if (remote?.sendsVoice == true) 0.5 else null)

    override fun offer(restart: Boolean): CompletableFuture<Sdp> = synchronized(lock) {
        hasLocal = true
        if (restart) restarts++
        candidateSoon()
        // Each offer its own (a new one is a new negotiation; one sent again is the same).
        CompletableFuture.completedFuture(Sdp("offer", "$id/$restarts/${offers++}"))
    }

    override fun answer(offer: Sdp): CompletableFuture<Sdp> {
        val (other, restart) = offer.sdp.split("/").let { it[0] to it[1].toInt() }
        synchronized(lock) {
            val r = rtc.conn(other) ?: return CompletableFuture.failedFuture(IllegalStateException("No such offer"))
            remote = r
            hasRemote = true
            hasLocal = true
            if (restart > restarts) {
                restarts = restart
                needsRestart = false
                r.needsRestart = false
            }
            sendsVoice = wantsVoice
            candidateSoon()
        }
        tryConnect()
        return CompletableFuture.completedFuture(Sdp("answer", id))
    }

    override fun accept(answer: Sdp): CompletableFuture<Unit> {
        synchronized(lock) {
            val r = rtc.conn(answer.sdp) ?: return CompletableFuture.failedFuture(IllegalStateException("No such answer"))
            remote = r
            hasRemote = true
            needsRestart = false
            sendsVoice = wantsVoice
        }
        tryConnect()
        return CompletableFuture.completedFuture(Unit)
    }

    override fun addCandidate(candidate: Candidate) {
        synchronized(lock) { gotCandidate = true }
        tryConnect()
    }

    // As a phone gathers them: on its Wi-Fi, its internet address (STUN), the relay's.
    private fun candidateSoon() {
        val n = (host.hashCode() and 0x7f) + 1
        rtc.ticker.schedule({
            if (!closed) {
                events.onCandidate(Candidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.$n 50000 typ host generation 0"))
                events.onCandidate(Candidate("0", 0, "candidate:2 1 udp 1686052607 203.0.113.$n 41000 typ srflx raddr 192.168.1.$n rport 50000 generation 0"))
                events.onCandidate(Candidate("0", 0, "candidate:3 1 udp 41885439 198.51.100.7 3478 typ relay raddr 203.0.113.$n rport 41000 generation 0"))
            }
        }, 5, TimeUnit.MILLISECONDS)
    }

    private fun ready() = synchronized(lock) { hasLocal && hasRemote && gotCandidate && !closed }

    private fun tryConnect() {
        val r = remote ?: return
        if (!ready() || !r.ready() || r.remote !== this) return
        if (rtc.path(host, r.host).down) return
        this.connectedNow()
        r.connectedNow()
    }

    private fun connectedNow() {
        val first = synchronized(lock) {
            if (connected && reported == PeerState.CONNECTED) return
            connected = true
            downSince = 0
            !opened
        }
        report(PeerState.CONNECTED)
        if (first) {
            opened = true
            events.onOpen(RtcPeer.RELIABLE)
            events.onOpen(RtcPeer.QUICK)
        }
    }

    private fun report(state: PeerState) {
        synchronized(lock) {
            if (reported == state) return
            reported = state
        }
        events.onState(state)
    }

    /** A phone moved network: this way is gone until an ICE restart. */
    fun lostWay() {
        needsRestart = true
    }

    // Ten times a second: is the way still there?
    private fun check() {
        if (closed || !connected) return
        val r = remote ?: return
        val down = rtc.path(host, r.host).down || needsRestart || r.closed
        if (down) {
            val now = System.nanoTime()
            if (downSince == 0L) downSince = now
            val ms = (now - downSince) / 1_000_000
            if (ms > 300) report(PeerState.DISCONNECTED)
            if (ms > 6_000 && needsRestart) report(PeerState.FAILED)
        } else {
            downSince = 0
            if (reported != PeerState.CONNECTED) report(PeerState.CONNECTED)
        }
    }

    override fun send(channel: Int, data: ByteArray): Boolean {
        if (closed || !opened) return false
        if (channel == RtcPeer.RELIABLE) {
            queued.addAndGet(data.size.toLong())
            val r = remote
            synchronized(outbox) {
                val due = maxOf(lastDue, System.nanoTime() + (r?.let { rtc.net.delayMs(host, it.host) } ?: 0) * 1_000_000)
                lastDue = due
                outbox.offer(due to data.copyOf())
            }
            return true
        }
        val r = remote ?: return true
        if (!usable() || rtc.net.lost(host, r.host)) return true
        val copy = data.copyOf()
        rtc.net.late.schedule({ r.deliver(RtcPeer.QUICK, copy) }, rtc.net.delayMs(host, r.host), TimeUnit.MILLISECONDS)
        return true
    }

    override fun buffered(channel: Int): Long = if (channel == RtcPeer.RELIABLE) queued.get() else 0

    override fun path(): CompletableFuture<PathInfo?> =
        CompletableFuture.completedFuture(if (connected) PathInfo("direct", remote?.let { rtc.net.delayMs(host, it.host).toInt() * 2 }) else null)

    override fun close() {
        if (closed) return
        closed = true
        watch.cancel(false)
        sender.interrupt()
        thread.shutdown()
    }
}

/** The network a room test's phones are on. */
interface TestNet {
    /** A new phone's way of reaching the others. */
    fun phone(): Transport

    /** The way between two phones (by host), to change it. */
    fun path(a: String, b: String): Path
}
