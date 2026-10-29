package com.kofarcade.core.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Rooms over the internet (phase 1.4): one WebRTC peer connection per pair
 * of phones, set up through [signaling]. ICE picks the way itself: the
 * local network when both phones are on it, else directly through the
 * internet (STUN), else the relay (TURN, in [iceServers]).
 *
 * A phone's [Address] here is its id in the room (the host) and a port of
 * this transport's own, so a room runs over it exactly as over sockets:
 * - links ride the connection's reliable channel, several at once, each
 *   message cut into pieces small enough for any data channel;
 * - datagrams ride its quick channel (unordered, never resent).
 *
 * The phone with the lower id makes every offer, so two phones never offer
 * at once; the other asks it to with a hello. A connection that fails or
 * stays disconnected gets an ICE restart (the same connection, a new way),
 * so links survive a phone switching from Wi-Fi to 4G.
 */
class WebRtcTransport(
    private val signaling: Signaling,
    private val rtc: RtcFactory,
    /** STUN and TURN servers, asked for each new connection (relay credentials last an hour). */
    private val iceServers: () -> List<IceServer>,
    /** The room's code: what friends type to join (see [key]). */
    private val code: String = "",
) : Transport {
    val self: String get() = signaling.self

    /** This run of the transport: an app that starts over is a new one. */
    private val instance = java.util.UUID.randomUUID().toString().take(8)

    private val listeners = ConcurrentHashMap<Int, RtcListener>()
    private val endpoints = ConcurrentHashMap<Int, RtcDatagrams>()
    private val peers = ConcurrentHashMap<String, Peer>()
    // Ports it picks itself start high: low ones are left for those asked for by number (OnlineRooms.PORT).
    private var nextPort = 100
    @Volatile private var closed = false

    // Every connection's state changes here, one at a time (see Peer).
    private val actor = Executors.newSingleThreadExecutor { r -> Thread(r, "webrtc").apply { isDaemon = true } }

    // Restarts and other waits, off the library's and the signaling's threads.
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "webrtc-timers").apply { isDaemon = true } }

    init {
        signaling.listen(::onSignal)
    }

    @Synchronized
    private fun freePort(): Int {
        while (listeners.containsKey(nextPort) || endpoints.containsKey(nextPort)) nextPort++
        return nextPort++
    }

    override fun listen(port: Int): Listener {
        synchronized(this) {
            val p = if (port != 0 && !listeners.containsKey(port) && !endpoints.containsKey(port)) port else freePort()
            return RtcListener(p).also { listeners[p] = it }
        }
    }

    override fun datagrams(): Datagrams = RtcDatagrams(freePort()).also { endpoints[it.port] = it }

    override fun connect(to: Address, timeoutMs: Int): Link {
        if (to.host == self) throw ConnectException("That's this phone")
        val until = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
        val peer = peer(to.host)
        peer.connect()
        peer.awaitOpen(until)
        val link = peer.newLink()
        if (!peer.sendFrame(open(link.id, to.port))) {
            peer.drop(link.id)
            throw ConnectException("Connection lost")
        }
        return link.awaitAccepted(until)
    }

    override fun key(host: String, port: Int): String = code

    /** Which way each phone is reached, and how quick it is: for the connection report. */
    fun paths(): Map<String, PathInfo> = peers.values.mapNotNull { p ->
        runCatching { p.pc?.path()?.get(500, TimeUnit.MILLISECONDS) }.getOrNull()?.let { p.id to it }
    }.toMap()

    /** Asks for a new way to every phone: after this phone's network changed (Wi-Fi to 4G). */
    fun networkChanged() = peers.values.forEach { it.restartIce() }

    /** Leaves: every connection ends, and the others are told. */
    fun close() {
        if (closed) return
        // What the room said last (its goodbye) goes out before the connections end.
        val until = System.nanoTime() + DRAIN_NS
        while (System.nanoTime() < until && peers.values.any { p -> p.pc?.let { it.buffered(RtcPeer.RELIABLE) > 0 } == true }) Thread.sleep(10)
        Thread.sleep(GOODBYE_MS)
        closed = true
        actor.shutdown()
        runCatching { actor.awaitTermination(2, TimeUnit.SECONDS) }
        peers.values.forEach {
            runCatching { signaling.send(Signal(Signal.BYE, self, it.id)) }
            it.endAtOnce("Left")
        }
        listeners.values.toList().forEach { it.close() }
        endpoints.values.toList().forEach { it.close() }
        timers.shutdownNow()
        signaling.close()
    }

    private fun peer(id: String): Peer = peers.computeIfAbsent(id) { Peer(it) }

    private fun onSignal(s: Signal) {
        if (closed || s.to != self || s.from == self) return
        val peer = peer(s.from)
        when (s.kind) {
            Signal.HELLO -> peer.onHello(s)
            Signal.OFFER -> peer.onOffer(s)
            Signal.ANSWER -> peer.onAnswer(s)
            Signal.CANDIDATE -> peer.onCandidate(s)
            Signal.RESTART -> peer.restartIce()
            Signal.BYE -> peer.end("${s.from} left")
        }
    }

    // ----- Frames on the reliable channel -----

    private fun open(link: Int, port: Int) = ByteBuffer.allocate(9).put(OPEN).putInt(link).putInt(port).array()
    private fun simple(type: Byte, link: Int) = ByteBuffer.allocate(5).put(type).putInt(link).array()

    /**
     * The connection to one other phone, and the links on it.
     *
     * Its state changes only on [actor], one task at a time, and that's
     * where the WebRTC library is called: nothing here holds a lock while
     * calling it, and the library's callbacks never wait (they hand their
     * work to the actor), so the two can't block each other. The data path
     * (messages in and out) skips the actor.
     */
    private inner class Peer(val id: String) {
        val offerer = self < id
        @Volatile var pc: RtcPeer? = null
        /** The connection's number; kept after it ends (an answerer's hello says it). */
        @Volatile var gen = 0

        // Actor-only state.
        private var remoteSet = false
        private val early = ArrayList<Candidate>()
        // Candidates for a connection whose offer hasn't come yet.
        private val ahead = ArrayList<Signal>()
        private var disconnectedSince = 0L
        private var restarting = false
        private var lastHello = 0L
        /** Which run of the other phone answered this connection (the offerer's note). */
        private var answeredBy: String? = null
        /** Which run of the other phone made this connection's offer (the answerer's note). */
        private var offeredBy: String? = null
        // The answerer's last offer and answer: a repeated offer gets the same answer again.
        private var lastOffer: String? = null
        private var lastAnswer: Sdp? = null

        // Whether the channels are open: read by waiting threads, so under [lock].
        private val lock = Object()
        private var reliableOpen = false
        private var quickOpen = false
        private val links = ConcurrentHashMap<Int, RtcLink>()
        private var nextLink = if (offerer) 2 else 3

        val open: Boolean get() = synchronized(lock) { reliableOpen && quickOpen }

        private fun act(task: () -> Unit) {
            if (closed) return
            try {
                actor.execute {
                    try {
                        task()
                    } catch (e: Exception) {
                        NetLog.add("rtc: $id: ${e.message}")
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Closed meanwhile.
            }
        }

        /** Sets up the connection if there's none: the offerer offers, the other asks it to. */
        fun connect() {
            if (closed) throw SocketException("Closed")
            act {
                if (pc == null) {
                    if (offerer) startOffer() else hello()
                }
            }
        }

        // At most one a second (inputs ask with every packet).
        private fun hello() {
            val now = System.nanoTime()
            if (lastHello != 0L && now - lastHello < HELLO_AGAIN_NS) return
            lastHello = now
            signalSafely(Signal(Signal.HELLO, self, id, gen = gen, instance = instance))
        }

        fun awaitOpen(until: Long) {
            var asked = System.nanoTime()
            synchronized(lock) {
                while (!(reliableOpen && quickOpen)) {
                    if (closed) throw SocketException("Closed")
                    val now = System.nanoTime()
                    if (now >= until) throw SocketTimeoutException("connect timed out")
                    // Asked again now and then: a hello can be lost while the other phone starts.
                    if (now - asked > HELLO_AGAIN_NS) {
                        asked = now
                        act { if (pc == null) (if (offerer) startOffer() else hello()) }
                    }
                    lock.wait(((until - now) / 1_000_000).coerceIn(1, 50))
                }
            }
        }

        private fun fresh(g: Int): RtcPeer {
            pc?.let { old ->
                pc = null
                failLinks("Connection replaced")
                setOpen(reliable = false, quick = false)
                old.close()
            }
            gen = g
            remoteSet = false
            early.clear()
            ahead.filter { it.gen == g }.forEach { early += Candidate(it.mid, it.index, it.sdp!!) }
            ahead.removeAll { it.gen <= g }
            disconnectedSince = 0
            restarting = false
            lastOffer = null
            lastAnswer = null
            answeredBy = null
            offeredBy = null
            val events = Events()
            val made = rtc.peer(iceServers(), events)
            events.pc = made
            pc = made
            return made
        }

        private fun setOpen(reliable: Boolean, quick: Boolean) = synchronized(lock) {
            reliableOpen = reliable
            quickOpen = quick
            lock.notifyAll()
        }

        // A new connection, numbered above any before.
        private fun startOffer() {
            val g = maxOf(gen + 1, (System.currentTimeMillis() / 1000).toInt())
            val made = fresh(g)
            made.offer(restart = false).whenComplete { sdp, e ->
                act {
                    if (e != null || sdp == null) {
                        NetLog.add("rtc: no offer for $id: ${e?.message}")
                    } else {
                        sendOffer(made, g, sdp, 0)
                    }
                }
            }
        }

        // Sent again until answered: the other phone may not be listening yet.
        private fun sendOffer(made: RtcPeer, g: Int, sdp: Sdp, tries: Int) {
            if (pc !== made || remoteSet) return
            if (tries >= OFFER_TRIES) {
                NetLog.add("rtc: no answer from $id")
                endNow("No answer")
                return
            }
            signalSafely(Signal(Signal.OFFER, self, id, gen = g, instance = instance, sdpType = sdp.type, sdp = sdp.sdp))
            runCatching { timers.schedule({ act { sendOffer(made, g, sdp, tries + 1) } }, OFFER_AGAIN_MS, TimeUnit.MILLISECONDS) }
        }

        fun onHello(s: Signal) = act {
            if (!offerer) return@act
            when {
                pc == null -> startOffer()
                // Its app started over (it crashed, or its goodbye was lost): a new connection.
                answeredBy != null && s.instance != answeredBy -> startOffer()
                // It had this connection and lost it: a new one.
                s.gen >= gen -> startOffer()
                // Sent before our offer reached it: the offer's on its way.
                else -> {}
            }
        }

        fun onOffer(s: Signal) = act {
            val offerSdp = s.sdp ?: return@act
            if (offerer) return@act
            val current = pc
            val target = when {
                // From another run of that phone (it started over), or a newer connection: a new one.
                s.gen > gen || (current != null && offeredBy != null && s.instance != offeredBy) -> fresh(s.gen)
                s.gen == gen && current != null -> {
                    // The same offer again: its answer was lost.
                    if (offerSdp == lastOffer) {
                        lastAnswer?.let { signalSafely(Signal(Signal.ANSWER, self, id, gen = gen, instance = instance, sdpType = it.type, sdp = it.sdp)) }
                        return@act
                    }
                    current // an ICE restart of this connection
                }
                else -> return@act
            }
            lastOffer = offerSdp
            lastAnswer = null
            offeredBy = s.instance
            target.answer(Sdp(s.sdpType ?: "offer", offerSdp)).whenComplete { sdp, e ->
                act {
                    if (e != null || sdp == null) {
                        NetLog.add("rtc: no answer for $id: ${e?.message}")
                        return@act
                    }
                    if (pc !== target) return@act
                    lastAnswer = sdp
                    remoteSet = true
                    early.forEach(target::addCandidate)
                    early.clear()
                    signalSafely(Signal(Signal.ANSWER, self, id, gen = s.gen, instance = instance, sdpType = sdp.type, sdp = sdp.sdp))
                }
            }
        }

        fun onAnswer(s: Signal) = act {
            val answerSdp = s.sdp ?: return@act
            if (!offerer) return@act
            val target = pc ?: return@act
            if (s.gen != gen || (remoteSet && !restarting)) return@act
            // An answer from another run of that phone can't continue this connection.
            if (restarting && answeredBy != null && s.instance != answeredBy) return@act
            answeredBy = s.instance
            remoteSet = true
            restarting = false
            target.accept(Sdp(s.sdpType ?: "answer", answerSdp)).whenComplete { _, e ->
                act {
                    if (e != null) {
                        NetLog.add("rtc: answer from $id refused: ${e.message}")
                        return@act
                    }
                    if (pc !== target) return@act
                    early.forEach(target::addCandidate)
                    early.clear()
                }
            }
        }

        fun onCandidate(s: Signal) = act {
            val sdp = s.sdp ?: return@act
            if (s.gen > gen) {
                if (ahead.size < MAX_AHEAD) ahead += s
                return@act
            }
            val target = pc ?: return@act
            if (s.gen != gen) return@act
            val c = Candidate(s.mid, s.index, sdp)
            if (remoteSet && early.isEmpty()) target.addCandidate(c) else early += c
        }

        /** A new way to the same phone, keeping the connection and its links. */
        fun restartIce() = act {
            val target = pc ?: return@act
            if (!offerer) {
                signalSafely(Signal(Signal.RESTART, self, id, gen = gen))
                return@act
            }
            if (restarting || !remoteSet) return@act
            restarting = true
            val g = gen
            target.offer(restart = true).whenComplete { sdp, e ->
                act {
                    if (e != null || sdp == null) {
                        restarting = false
                        return@act
                    }
                    if (pc !== target || gen != g) return@act
                    signalSafely(Signal(Signal.OFFER, self, id, gen = g, instance = instance, sdpType = sdp.type, sdp = sdp.sdp))
                    // Not answered in time: the next restart may try again.
                    runCatching { timers.schedule({ act { if (gen == g) restarting = false } }, RESTART_AFTER_MS * 2, TimeUnit.MILLISECONDS) }
                }
            }
        }

        fun end(reason: String) = act { endNow(reason) }

        /** On closing the transport: at once, on the caller's thread (the actor's going away). */
        fun endAtOnce(reason: String) {
            failLinks(reason)
            setOpen(reliable = false, quick = false)
            pc?.close()
            pc = null
        }

        private fun endNow(reason: String) {
            val old = pc
            pc = null
            remoteSet = false
            failLinks(reason)
            setOpen(reliable = false, quick = false)
            old?.close()
        }

        private fun failLinks(reason: String) {
            links.values.forEach { it.lost(reason) }
            links.clear()
        }

        /** One connection's events: dropped once it's been replaced or ended. */
        inner class Events : RtcPeer.Events {
            @Volatile var pc: RtcPeer? = null
            private fun current() = pc != null && pc === this@Peer.pc

            override fun onCandidate(candidate: Candidate) {
                val g = gen
                if (current()) signalSafely(Signal(Signal.CANDIDATE, self, id, gen = g, sdp = candidate.sdp, mid = candidate.mid, index = candidate.index))
            }

            override fun onState(state: PeerState) {
                val mine = pc
                act { if (mine === this@Peer.pc) this@Peer.onState(state) }
            }

            override fun onOpen(channel: Int) {
                if (!current()) return
                synchronized(lock) {
                    if (channel == RtcPeer.RELIABLE) reliableOpen = true else quickOpen = true
                    lock.notifyAll()
                }
                if (open) {
                    pc?.path()?.whenComplete { p, _ -> if (p != null) NetLog.add("rtc: $id ${p.kind}" + (p.rttMs?.let { ", $it ms" } ?: "")) }
                }
            }

            override fun onMessage(channel: Int, data: ByteArray) {
                if (current()) this@Peer.onMessage(channel, data)
            }
        }

        // On the actor.
        private fun onState(state: PeerState) {
            NetLog.add("rtc: $id ${state.name.lowercase()}")
            when (state) {
                PeerState.CONNECTED -> disconnectedSince = 0
                PeerState.DISCONNECTED -> {
                    // Often back on its own within a second or two; else a restart.
                    val since = System.nanoTime()
                    disconnectedSince = since
                    runCatching {
                        timers.schedule({ act { if (disconnectedSince == since) restartIce() } }, RESTART_AFTER_MS, TimeUnit.MILLISECONDS)
                    }
                }
                PeerState.FAILED -> restartIce()
                PeerState.CLOSED -> endNow("Connection closed")
                else -> {}
            }
        }

        // ----- The data path: the library's threads, no actor -----

        fun onMessage(channel: Int, data: ByteArray) {
            if (channel == RtcPeer.QUICK) {
                if (data.size < 4) return
                val port = ByteBuffer.wrap(data).int
                endpoints[port]?.arrive(data.copyOfRange(4, data.size))
                return
            }
            if (data.size < 5) return
            val b = ByteBuffer.wrap(data)
            val type = b.get()
            val link = b.int
            when (type) {
                OPEN -> {
                    val port = b.int
                    val listener = listeners[port]
                    if (listener == null || closed) {
                        sendFrame(simple(REFUSE, link))
                        return
                    }
                    val l = RtcLink(this, link)
                    links[link] = l
                    if (listener.offer(l)) sendFrame(simple(ACCEPT, link)) else {
                        links.remove(link)
                        sendFrame(simple(REFUSE, link))
                    }
                }
                ACCEPT -> links[link]?.accepted()
                REFUSE -> links.remove(link)?.refused()
                PIECE, LAST -> links[link]?.piece(data, 5, type == LAST)
                CLOSE -> links.remove(link)?.ended()
            }
        }

        fun newLink(): RtcLink = synchronized(lock) {
            val l = RtcLink(this, nextLink)
            nextLink += 2
            links[l.id] = l
            l
        }

        fun drop(link: Int) {
            links.remove(link)
        }

        /** Sends a frame on the reliable channel, waiting while too much is queued. */
        fun sendFrame(frame: ByteArray): Boolean {
            val c = pc ?: return false
            var waited = 0
            while (c.buffered(RtcPeer.RELIABLE) > MAX_QUEUED && waited < QUEUE_WAIT_MS && !closed) {
                Thread.sleep(2)
                waited += 2
            }
            return c.send(RtcPeer.RELIABLE, frame)
        }

        fun sendQuick(port: Int, bytes: ByteArray) {
            val c = pc
            if (c == null || !open) {
                // Lost, as a datagram can be; the connection is set up for the next.
                if (c == null && !closed) runCatching { connect() }
                return
            }
            c.send(RtcPeer.QUICK, ByteBuffer.allocate(4 + bytes.size).putInt(port).put(bytes).array())
        }

        private fun signalSafely(s: Signal) {
            try {
                signaling.send(s)
            } catch (e: Exception) {
                NetLog.add("rtc: couldn't signal ${s.kind} to $id: ${e.message}")
            }
        }
    }

    /** A link on a peer's reliable channel: messages in order, cut into pieces on the way. */
    private inner class RtcLink(private val peer: Peer, val id: Int) : Link {
        private val lock = Object()
        private val inbox = ArrayDeque<Pair<Byte, ByteArray>?>()
        private val partial = ByteArrayOutputStream()
        private var state = 0 // 0 waiting, 1 accepted, 2 refused
        @Volatile private var closed = false
        private var lostReason: String? = null
        @Volatile override var timeoutMs = 0
        override val remoteHost: String get() = peer.id
        override val localHost: String get() = self
        override val isClosed: Boolean get() = closed

        fun accepted() = synchronized(lock) {
            state = 1
            lock.notifyAll()
        }

        fun refused() = synchronized(lock) {
            state = 2
            lock.notifyAll()
        }

        fun awaitAccepted(until: Long): Link {
            synchronized(lock) {
                while (state == 0 && lostReason == null) {
                    val now = System.nanoTime()
                    if (now >= until) {
                        peer.drop(id)
                        throw SocketTimeoutException("connect timed out")
                    }
                    lock.wait(((until - now) / 1_000_000).coerceIn(1, 50))
                }
                if (state != 1) throw ConnectException(lostReason ?: "Connection refused")
            }
            return this
        }

        fun piece(data: ByteArray, from: Int, last: Boolean) = synchronized(lock) {
            partial.write(data, from, data.size - from)
            if (!last) return@synchronized
            val whole = partial.toByteArray()
            partial.reset()
            if (whole.isEmpty()) return@synchronized
            inbox.addLast(whole[0] to whole.copyOfRange(1, whole.size))
            lock.notifyAll()
        }

        /** The other end closed: what came before is still read. */
        fun ended() = synchronized(lock) {
            inbox.addLast(null)
            lock.notifyAll()
        }

        /** The connection under it is gone. */
        fun lost(reason: String) = synchronized(lock) {
            lostReason = reason
            lock.notifyAll()
        }

        override fun send(vararg messages: Pair<Byte, ByteArray>) {
            // Back to back: nothing another thread sends on this connection comes between them.
            synchronized(peer) {
                for ((type, payload) in messages) {
                    if (closed) throw SocketException("Socket closed")
                    lostReason?.let { throw SocketException(it) }
                    val whole = ByteArray(1 + payload.size)
                    whole[0] = type
                    payload.copyInto(whole, 1)
                    var at = 0
                    do {
                        val n = minOf(PIECE_BYTES, whole.size - at)
                        val last = at + n >= whole.size
                        val frame = ByteBuffer.allocate(5 + n).put(if (last) LAST else PIECE).putInt(id).put(whole, at, n).array()
                        if (!peer.sendFrame(frame)) throw SocketException("Connection lost")
                        at += n
                    } while (!last)
                }
            }
        }

        override fun receive(): Pair<Byte, ByteArray> {
            val limit = timeoutMs
            val until = if (limit > 0) System.nanoTime() + limit * 1_000_000L else Long.MAX_VALUE
            synchronized(lock) {
                while (true) {
                    if (closed) throw SocketException("Socket closed")
                    if (inbox.isNotEmpty()) return inbox.removeFirst() ?: throw EOFException()
                    lostReason?.let { throw SocketException(it) }
                    val now = System.nanoTime()
                    if (now >= until) throw SocketTimeoutException("Read timed out")
                    lock.wait(((until - now) / 1_000_000).coerceIn(1, 50))
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) { lock.notifyAll() }
            peer.drop(id)
            runCatching { peer.sendFrame(simple(CLOSE, id)) }
        }
    }

    private inner class RtcListener(override val port: Int) : Listener {
        private val queue = LinkedBlockingQueue<Link>()
        @Volatile private var closed = false

        fun offer(link: Link): Boolean = !closed && queue.offer(link)

        override fun accept(): Link {
            while (true) {
                if (closed) throw SocketException("Socket closed")
                queue.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
            }
        }

        override fun close() {
            closed = true
            listeners.remove(port, this)
        }
    }

    private inner class RtcDatagrams(override val port: Int) : Datagrams {
        private val queue = LinkedBlockingQueue<ByteArray>(256)
        @Volatile private var closed = false

        fun arrive(bytes: ByteArray) {
            // Full (nobody reading): the oldest goes, as a socket's buffer drops.
            if (!queue.offer(bytes)) {
                queue.poll()
                queue.offer(bytes)
            }
        }

        override fun send(bytes: ByteArray, to: Address) {
            if (closed) throw SocketException("Socket closed")
            if (to.host == self) {
                endpoints[to.port]?.arrive(bytes.copyOf())
                return
            }
            peer(to.host).sendQuick(to.port, bytes)
        }

        override fun receive(buf: ByteArray, timeoutMs: Int): Int {
            if (closed) throw SocketException("Socket closed")
            val got = queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (closed) throw SocketException("Socket closed") else -1
            val n = minOf(got.size, buf.size)
            got.copyInto(buf, 0, 0, n)
            return n
        }

        override fun close() {
            closed = true
            endpoints.remove(port, this)
        }
    }

    private companion object {
        const val OPEN: Byte = 1
        const val ACCEPT: Byte = 2
        const val REFUSE: Byte = 3
        const val PIECE: Byte = 4
        const val LAST: Byte = 5
        const val CLOSE: Byte = 6

        /** Pieces of a link's messages: well under any data channel's limit (16 KB is safe everywhere). */
        const val PIECE_BYTES = 16 * 1024 - 5
        /** A sender waits while this much is queued on the reliable channel. */
        const val MAX_QUEUED = 1L shl 20
        const val QUEUE_WAIT_MS = 10_000
        const val RESTART_AFTER_MS = 2_000L
        const val HELLO_AGAIN_NS = 1_000_000_000L
        const val OFFER_AGAIN_MS = 1_500L
        const val MAX_AHEAD = 64
        const val DRAIN_NS = 1_000_000_000L
        /** After the last bytes leave, time for them to arrive before the goodbye (BYE) does. */
        const val GOODBYE_MS = 250L
        /** An offer unanswered this many times (30 s) ends the try. */
        const val OFFER_TRIES = 20
    }
}
