package com.kofarcade.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

// The wire format. A TCP connection carries the handshake, the start state
// and leaving; UDP carries the inputs (small, frequent, and resent until
// acknowledged, so a lost packet costs nothing).

const val PROTOCOL = 4
const val TCP_PORT = 7845
const val BEACON_PORT = 7846

/** Who is joining, and with what game files. */
@Serializable
data class Hello(
    val protocol: Int,
    val name: String,
    val set: String,
    /** RomFingerprint of the game zip and of neogeo.zip: they must match the host's. */
    val rom: Long,
    val bios: Long,
    val udpPort: Int,
)

/** The host's answer: accepted (with the session settings) or not, and why. */
@Serializable
data class Welcome(
    val accepted: Boolean,
    val reason: String = "",
    val name: String = "",
    val delay: Int = 1,
    val udpPort: Int = 0,
    val session: Int = 0,
    /** The host's game, for a [PROBE]. */
    val set: String = "",
    val title: String = "",
    val rom: Long = 0,
)

/** A [Hello] with this set asks only which game the host is running (joining by address). */
const val PROBE = "?"

/** What a host announces on the local network. */
@Serializable
data class Beacon(val protocol: Int, val name: String, val set: String, val title: String, val rom: Long, val tcpPort: Int)

internal val json = Json { ignoreUnknownKeys = true }

internal object Msg {
    const val HELLO: Byte = 1
    const val WELCOME: Byte = 2
    const val STATE: Byte = 3
    const val READY: Byte = 4
    const val BYE: Byte = 5
    const val PAUSE: Byte = 6
    const val RESUME: Byte = 7
    const val REMATCH: Byte = 8
}

internal fun DataOutputStream.message(type: Byte, payload: ByteArray = ByteArray(0)) {
    synchronized(this) {
        writeByte(type.toInt())
        writeInt(payload.size)
        write(payload)
        flush()
    }
}

internal fun DataInputStream.message(): Pair<Byte, ByteArray> {
    val type = readByte()
    val size = readInt()
    if (size < 0 || size > 16 shl 20) throw IOException("Bad message size $size")
    val payload = ByteArray(size)
    readFully(payload)
    return type to payload
}

internal inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray()

internal inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(String(bytes))

/**
 * One input packet: the sender's unacknowledged inputs from [start], what it
 * has received from us ([ack]: all frames below it), how many frames it runs
 * ahead of our inputs ([advantage], for time sync) with its input [delay],
 * and optionally a state checksum for frame [sumFrame] (-1: none).
 */
data class InputPacket(
    val session: Int,
    val ack: Int,
    val start: Int,
    val masks: IntArray,
    val sumFrame: Int = -1,
    val sum: Long = 0,
    val advantage: Int = 0,
    /** Which game of the session (each rematch starts the next); others' packets are ignored. */
    val epoch: Int = 0,
    val delay: Int = 1,
) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(4 + 1 + 4 * 4 + 1 + 1 + 1 + 1 + masks.size * 2 + 8)
        b.putInt(MAGIC).put(VERSION).putInt(session).put(epoch.toByte()).putInt(ack).putInt(start).putInt(sumFrame)
        b.put(advantage.coerceIn(-127, 127).toByte())
        b.put(delay.toByte())
        b.put(masks.size.toByte())
        masks.forEach { b.putShort(it.toShort()) }
        b.putLong(sum)
        return b.array().copyOf(b.position())
    }

    companion object {
        private const val MAGIC = 0x39304B41 // "90KA"
        private const val VERSION: Byte = 4
        const val MAX_MASKS = 120

        fun decode(data: ByteArray, length: Int = data.size): InputPacket? = try {
            val b = ByteBuffer.wrap(data, 0, length)
            if (b.int != MAGIC || b.get() != VERSION) {
                null
            } else {
                val session = b.int
                val epoch = b.get().toInt() and 0xFF
                val ack = b.int
                val start = b.int
                val sumFrame = b.int
                val advantage = b.get().toInt()
                val delay = b.get().toInt()
                val n = b.get().toInt() and 0xFF
                val masks = IntArray(n) { b.short.toInt() and 0xFFFF }
                InputPacket(session, ack, start, masks, sumFrame, b.long, advantage, epoch, delay)
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun equals(other: Any?) = other is InputPacket && session == other.session && ack == other.ack &&
        start == other.start && masks.contentEquals(other.masks) && sumFrame == other.sumFrame && sum == other.sum &&
        advantage == other.advantage && epoch == other.epoch && delay == other.delay

    override fun hashCode() = start * 31 + masks.contentHashCode()
}
