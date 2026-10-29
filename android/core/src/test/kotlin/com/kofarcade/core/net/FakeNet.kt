package com.kofarcade.core.net

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// A simulated network for room tests: phones as hosts ("phone1"...), links
// and datagrams carried in memory, with the internet's troubles between any
// two phones: latency, jitter (datagrams overtake each other), datagram
// loss, and outages (links stall, datagrams are lost, nobody new gets
// through). The room logic runs over it exactly as over real sockets.

/** The way between two phones, both directions. */
class Path(
    @Volatile var latencyMs: Int = 0,
    @Volatile var jitterMs: Int = 0,
    /** Each datagram is lost with this chance (links lose nothing: they're reliable). */
    @Volatile var loss: Double = 0.0,
    /** Down: nothing gets through until it's up again (links stall, datagrams are lost). */
    @Volatile var down: Boolean = false,
)

class FakeNet(
    /** Every new way between two phones starts like this. */
    private val start: () -> Path = { Path() },
) {
    private val hosts = ConcurrentHashMap<String, FakeTransport>()
    private val paths = ConcurrentHashMap<Pair<String, String>, Path>()
    private var count = 0
    private val random = Random(7)
    internal val late = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-net").apply { isDaemon = true } }

    /** A new phone on this network. */
    @Synchronized
    fun phone(): FakeTransport {
        val host = "phone${++count}"
        return FakeTransport(this, host).also { hosts[host] = it }
    }

    /** The way between [a] and [b] (hosts), to change it. */
    fun path(a: String, b: String): Path = paths.getOrPut(if (a < b) a to b else b to a, start)

    internal fun host(name: String): FakeTransport? = hosts[name]

    /** How long one message or datagram from [a] to [b] takes now (ms). */
    internal fun delayMs(a: String, b: String): Long {
        val p = path(a, b)
        val j = p.jitterMs
        return p.latencyMs + if (j > 0) synchronized(random) { random.nextInt(j + 1) }.toLong() else 0L
    }

    internal fun lost(a: String, b: String): Boolean {
        val p = path(a, b)
        return p.down || (p.loss > 0 && synchronized(random) { random.nextDouble() } < p.loss)
    }
}

class FakeTransport internal constructor(private val net: FakeNet, val host: String) : Transport {
    private val listeners = ConcurrentHashMap<Int, FakeListener>()
    internal val endpoints = ConcurrentHashMap<Int, FakeDatagrams>()
    private var nextPort = 40_000

    @Synchronized
    private fun freePort(from: Int, count: Int): Int? = (from until from + count).firstOrNull { !listeners.containsKey(it) && !endpoints.containsKey(it) }

    @Synchronized
    override fun listen(port: Int): Listener {
        val p = (if (port != 0) freePort(port, TCP_PORTS) else null) ?: freePort(nextPort, 10_000)!!.also { nextPort = it + 1 }
        return FakeListener(p).also { listeners[p] = it }
    }

    @Synchronized
    override fun datagrams(): Datagrams {
        val p = freePort(nextPort, 10_000)!!.also { nextPort = it + 1 }
        return FakeDatagrams(p).also { endpoints[p] = it }
    }

    override fun connect(to: Address, timeoutMs: Int): Link {
        val there = net.host(to.host)
        val path = net.path(host, to.host)
        if (path.down) {
            // Nothing answers: the wait runs out.
            Thread.sleep(timeoutMs.toLong().coerceAtLeast(1))
            throw SocketTimeoutException("connect timed out")
        }
        Thread.sleep(net.delayMs(host, to.host) * 2)
        val listener = there?.listeners?.get(to.port) ?: throw ConnectException("Connection refused")
        val mine = FakeLink(host, to.host)
        val theirs = FakeLink(to.host, host)
        mine.peer = theirs
        theirs.peer = mine
        if (!listener.offer(theirs)) throw ConnectException("Connection refused")
        return mine
    }

    override fun key(host: String, port: Int): String = ""

    private inner class FakeListener(override val port: Int) : Listener {
        private val queue = LinkedBlockingQueue<FakeLink>()
        @Volatile private var closed = false

        fun offer(link: FakeLink): Boolean = !closed && queue.offer(link)

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

    /** One end of a link: what the other end sends arrives here, in order, each when its time comes. */
    internal inner class FakeLink(override val localHost: String, override val remoteHost: String) : Link {
        lateinit var peer: FakeLink
        private val lock = Object()
        // (when it's due, System.nanoTime; the message, or null: the other end closed)
        private val inbox = ArrayDeque<Pair<Long, Pair<Byte, ByteArray>?>>()
        private var lastDue = 0L
        @Volatile private var closed = false
        @Volatile override var timeoutMs = 0
        override val isClosed: Boolean get() = closed

        // From the other end.
        fun arrive(message: Pair<Byte, ByteArray>?) = synchronized(lock) {
            // Messages keep their order: none is due before the one sent before it.
            val due = maxOf(lastDue, System.nanoTime() + net.delayMs(remoteHost, localHost) * 1_000_000)
            lastDue = due
            inbox.addLast(due to message)
            lock.notifyAll()
        }

        override fun send(vararg messages: Pair<Byte, ByteArray>) {
            if (closed) throw SocketException("Socket closed")
            if (peer.closed) throw SocketException("Connection reset")
            // Together: nothing another thread sends comes between them.
            synchronized(peer.lock) { for (m in messages) peer.arrive(m) }
        }

        override fun receive(): Pair<Byte, ByteArray> {
            val limit = timeoutMs
            val until = if (limit > 0) System.nanoTime() + limit * 1_000_000L else Long.MAX_VALUE
            synchronized(lock) {
                while (true) {
                    if (closed) throw SocketException("Socket closed")
                    val now = System.nanoTime()
                    val head = inbox.firstOrNull()
                    // While the way is down nothing gets through (it's all still on its way).
                    val down = net.path(localHost, remoteHost).down
                    if (head != null && head.first <= now && !down) {
                        inbox.removeFirst()
                        val message = head.second
                        if (message == null) throw EOFException()
                        return message
                    }
                    if (now >= until) throw SocketTimeoutException("Read timed out")
                    val wake = minOf(until, if (head != null && !down) head.first else now + 5_000_000L)
                    val ms = ((wake - now) / 1_000_000).coerceIn(1, 20)
                    lock.wait(ms)
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) { lock.notifyAll() }
            // The other end reads what was sent before, then finds it closed.
            if (!peer.closed) peer.arrive(null)
        }
    }

    internal inner class FakeDatagrams(override val port: Int) : Datagrams {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        override fun send(bytes: ByteArray, to: Address) {
            if (closed) throw SocketException("Socket closed")
            if (net.lost(host, to.host)) return
            val copy = bytes.copyOf()
            val target = net.host(to.host) ?: return
            net.late.schedule({ target.endpoints[to.port]?.queue?.offer(copy) }, net.delayMs(host, to.host), TimeUnit.MILLISECONDS)
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
}

/** For tests: a room's network (null: real sockets). */
val Room.fakeHost: String? get() = (via as? FakeTransport)?.host

/** For tests: where to reach a room's phone: its host on the fake network, or this machine. */
val Room.address: Address get() = Address(fakeHost ?: "127.0.0.1", roomPort)
