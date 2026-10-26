@file:OptIn(ExperimentalForeignApi::class)

package com.kofarcade.core.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEPORT
import platform.posix.freeifaddrs
import platform.posix.getifaddrs
import platform.posix.ifaddrs
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongfVar
import platform.zlib.uncompress

internal actual fun initAddress(address: sockaddr_in) {
    address.sin_len = sizeOf<sockaddr_in>().convert()
}

internal actual fun noSigPipe(fd: Int) {
    memScoped {
        val v = alloc<IntVar>()
        v.value = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, v.ptr, sizeOf<IntVar>().convert())
    }
}

internal actual fun sendFlags(): Int = 0

internal actual fun reusePort(fd: Int) {
    memScoped {
        val v = alloc<IntVar>()
        v.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, v.ptr, sizeOf<IntVar>().convert())
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
internal actual fun zlibDeflate(data: ByteArray): ByteArray {
    val out = UByteArray(compressBound(data.size.convert()).toInt())
    val n = memScoped {
        val length = alloc<uLongfVar>()
        length.value = out.size.convert()
        val result = out.usePinned { o ->
            data.asUByteArray().usePinned { i ->
                compress2(o.addressOf(0), length.ptr, if (data.isEmpty()) null else i.addressOf(0), data.size.convert(), 1)
            }
        }
        if (result != Z_OK) throw NetException("Couldn't pack the game ($result)")
        length.value.toInt()
    }
    return out.asByteArray().copyOf(n)
}

@OptIn(ExperimentalUnsignedTypes::class)
internal actual fun zlibInflate(data: ByteArray, size: Int): ByteArray {
    val out = UByteArray(size)
    memScoped {
        val length = alloc<uLongfVar>()
        length.value = size.convert()
        val result = out.usePinned { o ->
            data.asUByteArray().usePinned { i ->
                uncompress(if (size == 0) null else o.addressOf(0), length.ptr, i.addressOf(0), data.size.convert())
            }
        }
        if (result != Z_OK || length.value.toInt() != size) throw NetException("Bad game state")
    }
    return out.asByteArray()
}

// iPhone: Wi-Fi is en0, its own hotspot bridge100; cellular (pdp_ip) is never a room's.
actual fun lanInterfaces(): List<LanInterface> = memScoped {
    val list = allocPointerTo<ifaddrs>()
    if (getifaddrs(list.ptr) != 0) return emptyList()
    val out = ArrayList<LanInterface>()
    var p = list.value
    while (p != null) {
        val a = p.pointed
        val address = a.ifa_addr
        val up = a.ifa_flags.toInt() and 0x1 != 0
        val loopback = a.ifa_flags.toInt() and 0x8 != 0
        if (address != null && address.pointed.sa_family.toInt() == AF_INET && up && !loopback) {
            val ip = address.reinterpret<sockaddr_in>().pointed
            val mask = a.ifa_netmask?.reinterpret<sockaddr_in>()?.pointed
            val host = ipText(ip.sin_addr.s_addr.toLong())
            val broadcast = mask?.let { ipText(ip.sin_addr.s_addr.toLong() or (it.sin_addr.s_addr.toLong().inv() and 0xFFFFFFFFL)) }
            out += LanInterface(a.ifa_name?.toKString().orEmpty(), host, broadcast)
        }
        p = a.ifa_next
    }
    freeifaddrs(list.value)
    out
}
