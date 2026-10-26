@file:OptIn(ExperimentalForeignApi::class)

package com.kofarcade.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv

// Linux runs only the tests: resources come from the source tree
// (KOF_RESOURCES, else the folder the tests run in).
internal actual fun resourceText(path: String): String? {
    val dir = getenv("KOF_RESOURCES")?.toKString() ?: "src/commonMain/resources"
    val file = fopen("$dir/$path", "rb") ?: return null
    val out = ArrayList<Byte>()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = buf.usePinned { fread(it.addressOf(0), 1.convert(), buf.size.convert(), file) }.toInt()
        if (n <= 0) break
        for (i in 0 until n) out += buf[i]
    }
    fclose(file)
    return out.toByteArray().decodeToString()
}
