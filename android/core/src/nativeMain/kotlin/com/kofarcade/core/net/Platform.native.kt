@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class, ExperimentalNativeApi::class)

package com.kofarcade.core.net

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getpeername
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.gettimeofday
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_timedwait
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.timeval
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.native.ref.createCleaner
import kotlin.time.TimeSource

// iPhone (and Linux, where the same code is tested): POSIX sockets and
// threads. Blocking waits are cut into short polls, so a socket closed from
// another thread is noticed at once.

private val origin = TimeSource.Monotonic.markNow()

actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec.toLong() * 1000L + tv.tv_usec.toLong() / 1000L
}

actual fun sleepMs(ms: Long) {
    var left = ms
    while (left > 0) {
        val step = left.coerceAtMost(500)
        usleep((step * 1000).convert())
        left -= step
    }
}

// (iPhone: Workers' threads run at the default priority.)
actual fun startThread(name: String, urgent: Boolean, body: () -> Unit) {
    val worker = Worker.start(name = name)
    worker.executeAfter(0L) {
        try {
            body()
        } catch (e: Throwable) {
            // A thread's failure ends the thread, as on the JVM.
        }
        worker.requestTermination(processScheduledJobs = false)
    }
}

private class MonitorState {
    val mutex = nativeHeap.alloc<pthread_mutex_t>()
    val cond = nativeHeap.alloc<pthread_cond_t>()

    init {
        memScoped {
            val attr = alloc<pthread_mutexattr_t>()
            pthread_mutexattr_init(attr.ptr)
            pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
            pthread_mutex_init(mutex.ptr, attr.ptr)
            pthread_mutexattr_destroy(attr.ptr)
        }
        pthread_cond_init(cond.ptr, null)
    }

    fun free() {
        pthread_cond_destroy(cond.ptr)
        pthread_mutex_destroy(mutex.ptr)
        nativeHeap.free(cond)
        nativeHeap.free(mutex)
    }
}

actual class Monitor actual constructor() {
    private val state = MonitorState()

    @Suppress("unused")
    private val cleaner = createCleaner(state) { it.free() }

    actual fun lock() {
        pthread_mutex_lock(state.mutex.ptr)
    }

    actual fun unlock() {
        pthread_mutex_unlock(state.mutex.ptr)
    }

    actual fun await(ms: Long) {
        memScoped {
            val tv = alloc<timeval>()
            gettimeofday(tv.ptr, null)
            val nanos = tv.tv_usec.toLong() * 1000L + (ms % 1000) * 1_000_000L
            val until = alloc<timespec>()
            until.tv_sec = (tv.tv_sec.toLong() + ms / 1000 + nanos / 1_000_000_000L).convert()
            until.tv_nsec = (nanos % 1_000_000_000L).convert()
            pthread_cond_timedwait(state.cond.ptr, state.mutex.ptr, until.ptr)
        }
    }

    actual fun signalAll() {
        pthread_cond_broadcast(state.cond.ptr)
    }
}

// ----- Sockets -----

private const val POLL_STEP_MS = 100
private const val IPPROTO_TCP = 6
private const val TCP_NODELAY = 1

private fun fail(what: String): Nothing = throw NetException("$what: ${strerror(errno)?.toKString() ?: errno}")

private fun swap16(n: Int): Int = ((n and 0xFF) shl 8) or ((n shr 8) and 0xFF)

private fun setInt(fd: Int, level: Int, option: Int, value: Int) = memScoped {
    val v = alloc<IntVar>()
    v.value = value
    setsockopt(fd, level, option, v.ptr, sizeOf<IntVar>().convert())
}

private fun fillAddress(address: sockaddr_in, host: String?, port: Int) {
    platform.posix.memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
    initAddress(address)
    address.sin_family = AF_INET.convert()
    address.sin_port = swap16(port).convert()
    if (host != null) address.sin_addr.s_addr = parseIp(host).convert()
}

/** "192.168.1.5" as s_addr holds it: the first number in the lowest byte. */
private fun parseIp(host: String): Long {
    val parts = host.split('.').map { it.toIntOrNull() ?: -1 }
    if (parts.size != 4 || parts.any { it !in 0..255 }) throw NetException("Bad address $host")
    return parts.foldIndexed(0L) { i, n, b -> n or (b.toLong() shl (8 * i)) }
}

/** An s_addr as text. */
internal fun ipText(sAddr: Long): String = (0 until 4).joinToString(".") { ((sAddr ushr (8 * it)) and 0xFF).toString() }

private fun hostOf(address: sockaddr_in): String = ipText(address.sin_addr.s_addr.toLong())

private fun portOf(address: sockaddr_in): Int = swap16(address.sin_port.toInt())

private fun boundPort(fd: Int): Int = memScoped {
    val address = alloc<sockaddr_in>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, address.ptr.reinterpret(), length.ptr)
    portOf(address)
}

private fun bindTo(fd: Int, port: Int) = memScoped {
    val address = alloc<sockaddr_in>()
    fillAddress(address, null, port)
    if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        close(fd)
        fail("bind $port")
    }
}

/** Waits up to [ms] (-1: until closed) for [events] on [fd]; false on timeout. Throws once [closed]. */
private fun waitFor(fd: Int, events: Int, ms: Int, closed: () -> Boolean): Boolean {
    val start = nanoTime()
    while (true) {
        if (closed()) throw NetException("Socket closed")
        val left = if (ms < 0) POLL_STEP_MS else (ms - ((nanoTime() - start) / 1_000_000)).toInt()
        if (left <= 0) return false
        val ready = memScoped {
            val p = alloc<pollfd>()
            p.fd = fd
            p.events = events.convert()
            poll(p.ptr, 1.convert(), left.coerceAtMost(POLL_STEP_MS))
        }
        if (closed()) throw NetException("Socket closed")
        if (ready > 0) return true
    }
}

actual class UdpSocket actual constructor(port: Int, broadcast: Boolean, reuse: Boolean) {
    private val fd = socket(AF_INET, SOCK_DGRAM, 0).also { if (it < 0) fail("socket") }

    @Volatile private var closed = false

    init {
        if (reuse) setInt(fd, SOL_SOCKET, SO_REUSEADDR, 1)
        if (reuse) reusePort(fd)
        if (broadcast) setInt(fd, SOL_SOCKET, SO_BROADCAST, 1)
        bindTo(fd, port)
    }

    actual val localPort: Int = boundPort(fd)
    actual val isClosed: Boolean get() = closed

    actual fun send(data: ByteArray, length: Int, host: String, port: Int) {
        if (closed) throw NetException("Socket closed")
        memScoped {
            val address = alloc<sockaddr_in>()
            fillAddress(address, host, port)
            val sent = data.usePinned { p ->
                sendto(fd, p.addressOf(0), length.convert(), sendFlags(), address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
            if (sent < 0) fail("send to $host")
        }
    }

    actual fun receive(buffer: ByteArray, timeoutMs: Int): Datagram? {
        if (!waitFor(fd, POLLIN, timeoutMs) { closed }) return null
        return memScoped {
            val address = alloc<sockaddr_in>()
            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            val n = buffer.usePinned { p ->
                recvfrom(fd, p.addressOf(0), buffer.size.convert(), 0, address.ptr.reinterpret<sockaddr>(), length.ptr)
            }
            if (n < 0) fail("receive")
            Datagram(n.toInt(), hostOf(address))
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        close(fd)
    }
}

actual class TcpServer actual constructor(port: Int) {
    private val fd = socket(AF_INET, SOCK_STREAM, 0).also { if (it < 0) fail("socket") }

    @Volatile private var closed = false

    init {
        setInt(fd, SOL_SOCKET, SO_REUSEADDR, 1)
        bindTo(fd, port)
        if (listen(fd, 16) != 0) {
            close(fd)
            fail("listen")
        }
    }

    actual val localPort: Int = boundPort(fd)

    actual fun accept(): TcpSocket {
        while (true) {
            waitFor(fd, POLLIN, -1) { closed }
            val client = accept(fd, null, null)
            if (client >= 0) return TcpSocket(client)
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        close(fd)
    }
}

actual class TcpSocket internal constructor(private val fd: Int) {
    @Volatile private var closed = false

    init {
        setInt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
        noSigPipe(fd)
    }

    actual val remoteHost: String = memScoped {
        val address = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        getpeername(fd, address.ptr.reinterpret(), length.ptr)
        hostOf(address)
    }

    actual val localHost: String = memScoped {
        val address = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        getsockname(fd, address.ptr.reinterpret(), length.ptr)
        hostOf(address)
    }

    @Volatile actual var readTimeoutMs: Int = 0

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!waitFor(fd, POLLIN, if (readTimeoutMs > 0) readTimeoutMs else -1) { closed }) throw NetTimeout()
        val n = buffer.usePinned { p -> recv(fd, p.addressOf(offset), length.convert(), 0) }
        if (n < 0) fail("read")
        return if (n == 0L) -1 else n.toInt()
    }

    actual fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            if (closed) throw NetException("Socket closed")
            val n = buffer.usePinned { p -> send(fd, p.addressOf(offset + done), (length - done).convert(), sendFlags()) }
            if (n < 0) fail("write")
            done += n.toInt()
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }
}

actual fun tcpConnect(host: String, port: Int, timeoutMs: Int): TcpSocket {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) fail("socket")
    val flags = fcntl(fd, F_GETFL, 0)
    fcntl(fd, F_SETFL, flags or O_NONBLOCK)
    try {
        memScoped {
            val address = alloc<sockaddr_in>()
            fillAddress(address, host, port)
            if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                if (errno != EINPROGRESS) fail("connect to $host:$port")
                if (!waitFor(fd, POLLOUT, timeoutMs) { false }) throw NetTimeout("Couldn't reach $host:$port")
                val error = alloc<IntVar>()
                val length = alloc<socklen_tVar>()
                length.value = sizeOf<IntVar>().convert()
                getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr)
                if (error.value != 0) throw NetException("Couldn't connect to $host:$port: ${strerror(error.value)?.toKString()}")
            }
        }
    } catch (e: Throwable) {
        close(fd)
        throw e
    }
    fcntl(fd, F_SETFL, flags)
    return TcpSocket(fd)
}

// ----- zlib -----

actual fun deflate(data: ByteArray): ByteArray = zlibDeflate(data)

actual fun inflate(data: ByteArray, size: Int): ByteArray = zlibInflate(data, size)

/** Platform bits: sockaddr's length field (Apple), SIGPIPE, port reuse. */
internal expect fun initAddress(address: sockaddr_in)
internal expect fun noSigPipe(fd: Int)
internal expect fun sendFlags(): Int
internal expect fun reusePort(fd: Int)
internal expect fun zlibDeflate(data: ByteArray): ByteArray
internal expect fun zlibInflate(data: ByteArray, size: Int): ByteArray

@Suppress("unused")
private fun unusedPointer(p: CPointer<ByteVar>?) = p
