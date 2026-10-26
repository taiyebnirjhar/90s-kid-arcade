package com.kofarcade.core

// Small things the JVM had built in, written once for every platform the
// core runs on (Android and iPhone).

/** A file bundled with the app (resources/: rosters, ROM sets), or null. */
internal expect fun resourceText(path: String): String?

/** [n]'s low 32 bits as 8 lowercase hex digits ("%08x"). */
fun hex8(n: Long): String = (n and 0xFFFFFFFFL).toString(16).padStart(8, '0')

/** Percent-encodes [text] for a URL path, as URLEncoder does but with "%20" for spaces. */
fun urlEncode(text: String): String = buildString {
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in ".-*_") {
            append(c.toChar())
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"

/** CRC-32 (the zip one), as java.util.zip.CRC32. */
class Crc32 {
    private var crc = 0xFFFFFFFF.toInt()

    fun update(byte: Int) {
        crc = TABLE[(crc xor byte) and 0xFF] xor (crc ushr 8)
    }

    fun update(bytes: ByteArray) {
        for (b in bytes) update(b.toInt())
    }

    val value: Long get() = crc.inv().toLong() and 0xFFFFFFFFL

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}
