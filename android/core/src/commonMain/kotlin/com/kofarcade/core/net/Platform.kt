package com.kofarcade.core.net

// What the room code needs from the phone, the same on Android (the JVM's
// java.net and threads) and iPhone (POSIX sockets and threads): sockets,
// threads, locks, clocks and zlib. Addresses are IPv4 text ("192.168.1.5").

/** A network error: the connection broke, closed, or the other side is gone. */
open class NetException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A read or receive waited its whole timeout. */
class NetTimeout(message: String = "Timed out") : NetException(message)

/** Monotonic nanoseconds, for intervals. */
expect fun nanoTime(): Long

/** Wall-clock milliseconds (since 1970). */
expect fun currentTimeMillis(): Long

expect fun sleepMs(ms: Long)

/**
 * Runs [body] on a new background thread named [name]; [urgent]: top
 * priority, so inputs go out and come in the moment they're ready, even
 * while the phone is busy drawing the game.
 */
expect fun startThread(name: String, urgent: Boolean = false, body: () -> Unit)

/**
 * A re-entrant lock with a condition, like a JVM object's monitor:
 * [locked] for synchronized, [await] / [signalAll] for wait / notifyAll
 * (only while holding it).
 */
expect class Monitor() {
    fun lock()
    fun unlock()

    /** Waits until signalled or [ms] pass; the lock is released meanwhile. */
    fun await(ms: Long)
    fun signalAll()
}

inline fun <T> Monitor.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** A UDP socket on [port] (0: any free one). */
expect class UdpSocket(port: Int = 0, broadcast: Boolean = false, reuse: Boolean = false) {
    val localPort: Int
    val isClosed: Boolean
    fun send(data: ByteArray, length: Int, host: String, port: Int)

    /** Waits up to [timeoutMs] for a datagram into [buffer]: its length and sender, or null. Throws once closed. */
    fun receive(buffer: ByteArray, timeoutMs: Int): Datagram?
    fun close()
}

class Datagram(val length: Int, val host: String)

/** A listening TCP socket on [port] (0: any free one); throws if it's taken. */
expect class TcpServer(port: Int) {
    val localPort: Int

    /** The next connection; throws once closed. */
    fun accept(): TcpSocket
    fun close()
}

/** A TCP connection, Nagle off. */
expect class TcpSocket {
    val remoteHost: String
    val localHost: String

    /** How long a read waits (0: forever) before [NetTimeout]. */
    var readTimeoutMs: Int

    /** Reads up to [length] bytes; -1 at the end of the stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

/** Connects to [host]:[port], waiting at most [timeoutMs]. */
expect fun tcpConnect(host: String, port: Int, timeoutMs: Int): TcpSocket

/** One network of this phone: its IPv4 address and broadcast address. */
class LanInterface(val name: String, val address: String, val broadcast: String?)

/** The phone's networks that are up (IPv4, not loopback). */
expect fun lanInterfaces(): List<LanInterface>

/** zlib-compressed (as java.util.zip.Deflater, fastest level). */
expect fun deflate(data: ByteArray): ByteArray

/** The data [deflate] packed, [size] bytes long. */
expect fun inflate(data: ByteArray, size: Int): ByteArray
