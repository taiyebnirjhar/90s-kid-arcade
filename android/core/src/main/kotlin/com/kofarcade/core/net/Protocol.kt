package com.kofarcade.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

// The wire format. TCP connections (each player's phone to the room's owner)
// carry joining, who sits where, the game state when that changes, and
// pause / rematch / leaving, which the owner passes on to the others; UDP
// carries the inputs, straight from every phone to every other (small,
// frequent, and resent until acknowledged, so a lost packet costs nothing).

const val PROTOCOL = 7
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
    /** Where a new owner can call this phone (should the owner leave). */
    val tcpPort: Int = 0,
)

/** The owner's answer: accepted (with the room's settings and your seat) or not, and why. */
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
    /** The guest's seat (player number, 0 = player 1) and how many can play this game. */
    val player: Int = 1,
    val maxPlayers: Int = 2,
    /** Who owns the room (their seat), and its code. */
    val owner: Int = 0,
    val code: String = "",
)

/** A seat of a room (a player, 0 = player 1) and the phone in it: where its inputs and TCP connections come from. */
@Serializable
data class Seat(val player: Int, val name: String, val host: String, val udpPort: Int, val tcpPort: Int)

/**
 * The owner's word on who plays from now on: game [epoch] starts from the
 * state that follows ([Msg.STATE]), with these [seats]. Sent to everyone
 * whenever someone joins or leaves, and for a rematch.
 */
@Serializable
data class Seating(val epoch: Int, val owner: Int, val seats: List<Seat>)

/** A new owner taking over the room (the old one left): it connects to each player with this. */
@Serializable
data class Takeover(val session: Int, val owner: Int)

/** A [Hello] with this set asks only which game the host is running (joining by address). */
const val PROBE = "?"

/** What a host announces on the local network. */
@Serializable
data class Beacon(
    val protocol: Int,
    val name: String,
    val set: String,
    val title: String,
    val rom: Long,
    val tcpPort: Int,
    /** Players in so far (the host counts) and how many the game takes. */
    val players: Int = 1,
    val maxPlayers: Int = 2,
    /** The room's code, to tell rooms apart. */
    val code: String = "",
)

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
    const val SEATING: Byte = 9
    const val TAKEOVER: Byte = 10
    const val START_STATE: Byte = 11
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
 * One input packet from player [from]: the sender's unacknowledged inputs
 * from [start], what it has received from us ([ack]: all frames below it), how many frames it runs
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
    val from: Int = 0,
) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(4 + 1 + 4 + 1 + 1 + 4 * 3 + 1 + 1 + 1 + masks.size * 2 + 8)
        b.putInt(MAGIC).put(VERSION).putInt(session).put(from.toByte()).put(epoch.toByte()).putInt(ack).putInt(start).putInt(sumFrame)
        b.put(advantage.coerceIn(-127, 127).toByte())
        b.put(delay.toByte())
        b.put(masks.size.toByte())
        masks.forEach { b.putShort(it.toShort()) }
        b.putLong(sum)
        return b.array().copyOf(b.position())
    }

    companion object {
        private const val MAGIC = 0x39304B41 // "90KA"
        private const val VERSION: Byte = 5
        const val MAX_MASKS = 120

        fun decode(data: ByteArray, length: Int = data.size): InputPacket? = try {
            val b = ByteBuffer.wrap(data, 0, length)
            if (b.int != MAGIC || b.get() != VERSION) {
                null
            } else {
                val session = b.int
                val from = b.get().toInt() and 0xFF
                val epoch = b.get().toInt() and 0xFF
                val ack = b.int
                val start = b.int
                val sumFrame = b.int
                val advantage = b.get().toInt()
                val delay = b.get().toInt()
                val n = b.get().toInt() and 0xFF
                val masks = IntArray(n) { b.short.toInt() and 0xFFFF }
                InputPacket(session, ack, start, masks, sumFrame, b.long, advantage, epoch, delay, from)
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun equals(other: Any?) = other is InputPacket && session == other.session && ack == other.ack &&
        start == other.start && masks.contentEquals(other.masks) && sumFrame == other.sumFrame && sum == other.sum &&
        advantage == other.advantage && epoch == other.epoch && delay == other.delay && from == other.from

    override fun hashCode() = start * 31 + masks.contentHashCode()
}
