package com.kofarcade.core.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.Inflater

// Android: the JVM's own.

actual fun nanoTime(): Long = System.nanoTime()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun sleepMs(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

actual fun startThread(name: String, urgent: Boolean, body: () -> Unit) {
    Thread(body, name).apply {
        isDaemon = true
        if (urgent) priority = Thread.MAX_PRIORITY
    }.start()
}

actual class Monitor actual constructor() {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    actual fun lock() = lock.lock()

    actual fun unlock() = lock.unlock()

    actual fun await(ms: Long) {
        try {
            condition.await(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    actual fun signalAll() = condition.signalAll()
}

private inline fun <T> net(block: () -> T): T = try {
    block()
} catch (e: SocketTimeoutException) {
    throw NetTimeout()
} catch (e: IOException) {
    throw NetException(e.message ?: e.toString(), e)
}

actual class UdpSocket actual constructor(port: Int, broadcast: Boolean, reuse: Boolean) {
    private val socket = net {
        DatagramSocket(null).apply {
            reuseAddress = reuse
            this.broadcast = broadcast
            bind(InetSocketAddress(port))
        }
    }
    private val packet = DatagramPacket(ByteArray(0), 0)

    actual val localPort: Int get() = socket.localPort
    actual val isClosed: Boolean get() = socket.isClosed

    actual fun send(data: ByteArray, length: Int, host: String, port: Int) = net {
        socket.send(DatagramPacket(data, length, InetSocketAddress(host, port)))
    }

    actual fun receive(buffer: ByteArray, timeoutMs: Int): Datagram? {
        packet.setData(buffer, 0, buffer.size)
        return try {
            net {
                socket.soTimeout = timeoutMs
                socket.receive(packet)
            }
            Datagram(packet.length, packet.address.hostAddress)
        } catch (e: NetTimeout) {
            null
        }
    }

    actual fun close() = socket.close()
}

actual class TcpServer actual constructor(port: Int) {
    private val server = net { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }

    actual val localPort: Int get() = server.localPort

    actual fun accept(): TcpSocket = TcpSocket(net { server.accept() })

    actual fun close() = server.close()
}

actual class TcpSocket(private val socket: Socket) {
    init {
        socket.tcpNoDelay = true
    }

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    actual val remoteHost: String get() = socket.inetAddress?.hostAddress.orEmpty()
    actual val localHost: String get() = socket.localAddress?.hostAddress.orEmpty()

    actual var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int = net { input.read(buffer, offset, length) }

    actual fun write(buffer: ByteArray, offset: Int, length: Int) = net { output.write(buffer, offset, length) }

    actual fun close() {
        runCatching { socket.close() }
    }
}

actual fun tcpConnect(host: String, port: Int, timeoutMs: Int): TcpSocket = net {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    } catch (e: IOException) {
        runCatching { socket.close() }
        throw e
    }
    TcpSocket(socket)
}

actual fun lanInterfaces(): List<LanInterface> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { nif ->
            nif.interfaceAddresses.mapNotNull { a ->
                (a.address as? Inet4Address)?.let { LanInterface(nif.name, it.hostAddress, a.broadcast?.hostAddress) }
            }
        }
}.getOrDefault(emptyList())

actual fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    deflater.setInput(data)
    deflater.finish()
    val out = java.io.ByteArrayOutputStream(data.size / 4 + 64)
    val buf = ByteArray(64 * 1024)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    deflater.end()
    return out.toByteArray()
}

actual fun inflate(data: ByteArray, size: Int): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArray(size)
    var n = 0
    while (n < size && !inflater.finished()) {
        val got = inflater.inflate(out, n, size - n)
        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        n += got
    }
    inflater.end()
    if (n != size) throw NetException("Bad game state")
    return out
}

/** For the Android app's [InetAddress] users. */
fun hostOf(address: InetAddress): String = address.hostAddress
