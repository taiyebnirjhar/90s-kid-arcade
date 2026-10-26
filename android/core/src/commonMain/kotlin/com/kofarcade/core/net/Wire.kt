package com.kofarcade.core.net

// Bytes on the wire, big-endian (as the JVM's DataOutputStream and
// ByteBuffer wrote them, so phones of either kind understand each other).

class ByteWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    private var size = 0

    private fun room(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun put(v: Int): ByteWriter {
        room(1)
        buf[size++] = v.toByte()
        return this
    }

    fun putShort(v: Int): ByteWriter = put(v shr 8).put(v)

    fun putInt(v: Int): ByteWriter = putShort(v shr 16).putShort(v)

    fun putLong(v: Long): ByteWriter = putInt((v shr 32).toInt()).putInt(v.toInt())

    fun put(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteWriter {
        room(length)
        bytes.copyInto(buf, size, offset, offset + length)
        size += length
        return this
    }

    fun bytes(): ByteArray = buf.copyOf(size)
}

/** Reads [data] up to [length]; past the end throws IndexOutOfBoundsException. */
class ByteReader(private val data: ByteArray, private val length: Int = data.size) {
    var position = 0
        private set

    /** A signed byte. */
    fun byte(): Int {
        if (position >= length) throw IndexOutOfBoundsException("Past the end")
        return data[position++].toInt()
    }

    /** A signed 16-bit number. */
    fun short(): Int = ((byte() shl 8) or (byte() and 0xFF)).toShort().toInt()

    fun int(): Int = (short() shl 16) or (short() and 0xFFFF)

    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)
}

/**
 * A TCP connection carrying messages: a type byte, a length, the payload.
 * Writes are whole messages, one at a time; nothing gets out while [cut]
 * (tests: a phone whose Wi-Fi dropped).
 */
class Conn(val socket: TcpSocket, private val cut: () -> Boolean = { false }) {
    private val input = ByteArray(16 * 1024)
    private var start = 0
    private var end = 0
    private val writing = Monitor()

    fun message(type: Byte, payload: ByteArray = ByteArray(0)) = messages(type to payload)

    /** Several messages back to back: nothing another thread sends comes between them. */
    fun messages(vararg all: Pair<Byte, ByteArray>) = writing.locked {
        for ((type, payload) in all) {
            val frame = ByteWriter(5 + payload.size).put(type.toInt()).putInt(payload.size).put(payload).bytes()
            if (!cut()) socket.write(frame, 0, frame.size)
        }
    }

    /** The next message: (type, payload). */
    fun read(): Pair<Byte, ByteArray> {
        val type = readByte()
        val size = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
        if (size < 0 || size > 16 shl 20) throw NetException("Bad message size $size")
        val payload = ByteArray(size)
        readFully(payload)
        return type.toByte() to payload
    }

    private fun fill() {
        val n = socket.read(input, 0, input.size)
        if (n < 0) throw NetException("Connection closed")
        start = 0
        end = n
    }

    /** 0..255. */
    private fun readByte(): Int {
        if (start == end) fill()
        return input[start++].toInt() and 0xFF
    }

    private fun readFully(out: ByteArray) {
        var n = 0
        while (n < out.size) {
            if (start == end) fill()
            val k = minOf(end - start, out.size - n)
            input.copyInto(out, n, start, start + k)
            start += k
            n += k
        }
    }

    fun close() = socket.close()
}
