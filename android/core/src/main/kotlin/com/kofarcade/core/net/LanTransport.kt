package com.kofarcade.core.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Rooms on one Wi-Fi or hotspot, no internet needed: links are TCP
 * connections, inputs go over UDP, and the room key is the owner's
 * address on the network.
 */
object LanTransport : Transport {
    override fun listen(port: Int): Listener = TcpListener(openServer(port))

    override fun connect(to: Address, timeoutMs: Int): Link {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(to.host, to.port), timeoutMs)
            return TcpLink(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    // Inputs go marked for Wi-Fi's quickest lane (DSCP EF: WMM's voice or
    // video queue), ahead of downloads and videos on a busy router.
    override fun datagrams(): Datagrams = UdpDatagrams(DatagramSocket(0).apply { runCatching { trafficClass = 0xB8 } })

    override fun key(host: String, port: Int): String = runCatching {
        val address = if (host.isEmpty()) lanAddress() else InetAddress.getByName(host) as? Inet4Address
        address?.let { roomKey(it, port) }
    }.getOrNull() ?: ""

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
}

private class TcpListener(private val server: ServerSocket) : Listener {
    override val port: Int get() = server.localPort

    override fun accept(): Link = TcpLink(server.accept())

    override fun close() {
        runCatching { server.close() }
    }
}

private class TcpLink(private val socket: Socket) : Link {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    init {
        socket.tcpNoDelay = true
    }

    override fun send(vararg messages: Pair<Byte, ByteArray>) {
        synchronized(output) {
            for ((type, payload) in messages) output.message(type, payload)
        }
    }

    override fun receive(): Pair<Byte, ByteArray> = input.message()

    override var timeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    override val remoteHost: String get() = socket.inetAddress.hostAddress
    override val localHost: String get() = socket.localAddress.hostAddress
    override val isClosed: Boolean get() = socket.isClosed

    override fun close() {
        runCatching { socket.close() }
    }
}

private class UdpDatagrams(private val socket: DatagramSocket) : Datagrams {
    override val port: Int get() = socket.localPort

    override fun send(bytes: ByteArray, to: Address) {
        socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(to.host, to.port)))
    }

    private val packet = DatagramPacket(ByteArray(0), 0)

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        if (socket.soTimeout != timeoutMs) socket.soTimeout = timeoutMs
        packet.setData(buf, 0, buf.size)
        return try {
            socket.receive(packet)
            packet.length
        } catch (e: SocketTimeoutException) {
            -1
        }
    }

    override fun close() = socket.close()
}
