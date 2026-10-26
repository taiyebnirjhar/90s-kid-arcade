@file:OptIn(ExperimentalForeignApi::class)

package com.kofarcade.core.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongfVar
import platform.zlib.uncompress

// Linux: only to run the iPhone's room code in tests on a Linux machine.

internal actual fun initAddress(address: sockaddr_in) {}

internal actual fun noSigPipe(fd: Int) {}

internal actual fun sendFlags(): Int = 0x4000 // MSG_NOSIGNAL

internal actual fun reusePort(fd: Int) {
    memScoped {
        val v = alloc<IntVar>()
        v.value = 1
        setsockopt(fd, SOL_SOCKET, 15 /* SO_REUSEPORT */, v.ptr, sizeOf<IntVar>().convert())
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

actual fun lanInterfaces(): List<LanInterface> = emptyList()
