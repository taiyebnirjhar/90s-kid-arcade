package com.kofarcade.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

// The wire format. TCP connections (each phone of a room to its owner) carry
// joining, who sits where, the game state when that changes, and pause /
// restart / taking a seat / leaving, which the owner passes on to the
// others; UDP carries the inputs, straight from every phone to every other
// (small, frequent, and resent until acknowledged, so a lost packet costs
// nothing).

const val PROTOCOL = 8
const val TCP_PORT = 7845

/** Rooms listen on the first free one of these TCP ports from [TCP_PORT] (the room key says which). */
const val TCP_PORTS = 8
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
    /** Which emulator build runs the game: phones must match to play the same game ("": unknown). */
    val build: String = "",
    /** This phone, the same every time (a random id kept by the app): it gets its seat back if it dropped. */
    val device: String = "",
)

/** The owner's answer: let in (with the room's settings and your member [id]) or not, and why. */
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
    /** The newcomer's member id, and how many can play this game. */
    val id: Int = 1,
    val maxPlayers: Int = 2,
    /** The owner's member id, and the room's key. */
    val owner: Int = 0,
    val key: String = "",
    /** Players and watchers in, for a [PROBE]. */
    val players: Int = 0,
    val watchers: Int = 0,
)

/**
 * A phone in a room: its member [id] (for as long as it's in), the [seat] it
 * plays in (0 = player 1; -1: it watches), and where its inputs and TCP
 * connections come from.
 */
@Serializable
data class Member(
    val id: Int,
    val name: String,
    val host: String,
    val udpPort: Int,
    val tcpPort: Int,
    val seat: Int = -1,
    /** The phone's own id ([Hello.device]). */
    val device: String = "",
    /** Dropped out: its seat is held for it, for [holdLeft] seconds more (as of this seating). */
    val away: Boolean = false,
    val holdLeft: Int = 0,
)

/**
 * The owner's word on who plays from now on: game [epoch] starts from the
 * state that follows ([Msg.STATE]), with these [members] (in their seats).
 * Sent to everyone whenever someone joins, leaves or takes a seat, to put
 * everyone back in step, and for a restart.
 */
@Serializable
data class Seating(val epoch: Int, val owner: Int, val members: List<Member>)

/**
 * Owner to a newcomer (or a watcher that fell behind): watch game [epoch]
 * with these [members] from [frame], whose state follows ([Msg.STATE]);
 * the players' inputs from that frame on follow as [Msg.INPUTS].
 */
@Serializable
data class Watch(val epoch: Int, val owner: Int, val members: List<Member>, val frame: Int)

/** A new owner taking over the room (the old one left): it connects to each member with this. */
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
    /** The room's key (what friends type to join). */
    val key: String = "",
    /** Phones watching. */
    val watchers: Int = 0,
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
    const val RESYNC: Byte = 12
    /** A watcher asks for a free seat (COIN or START). */
    const val SIT: Byte = 13
    /** Owner to one phone: something to tell its player (UTF-8). */
    const val NEWS: Byte = 14
    /** Owner to a watcher: [Watch], then the state. */
    const val WATCH: Byte = 15
    /** Owner to a watcher: the players' inputs for some frames ([RelayedInputs]). */
    const val INPUTS: Byte = 16
    /** Either way, every second: still here. */
    const val PING: Byte = 17
    /** A watcher to the owner: send me the game again (I fell behind or out of step). */
    const val REWATCH: Byte = 18
    /** Owner to everyone: who's in now (a [Seating] of the same game: someone came to watch or left). */
    const val ROSTER: Byte = 19
    /** A player gives up their seat, to watch. */
    const val STAND: Byte = 20
}

/**
 * The players' inputs for frames [start] until start + count, as the owner
 * relays them to watchers: for each frame, each seat of [seats] (a bit
 * mask) in order. And the owner's latest checksum ([sumFrame] -1: none).
 */
class RelayedInputs(val epoch: Int, val start: Int, val seats: Int, val masks: Array<IntArray>, val sumFrame: Int = -1, val sum: Long = 0) {
    val count: Int get() = masks.firstOrNull()?.size ?: 0

    fun encode(): ByteArray {
        val order = (0 until 4).filter { seats and (1 shl it) != 0 }
        val n = count
        val b = ByteBuffer.allocate(4 + 4 + 1 + 2 + 4 + 8 + n * order.size * 2)
        b.putInt(epoch).putInt(start).put(seats.toByte()).putShort(n.toShort()).putInt(sumFrame).putLong(sum)
        for (i in 0 until n) for (k in order.indices) b.putShort(masks[k][i].toShort())
        return b.array()
    }

    companion object {
        fun decode(data: ByteArray): RelayedInputs {
            val b = ByteBuffer.wrap(data)
            val epoch = b.int
            val start = b.int
            val seats = b.get().toInt() and 0xFF
            val n = b.short.toInt() and 0xFFFF
            val sumFrame = b.int
            val sum = b.long
            val order = (0 until 4).filter { seats and (1 shl it) != 0 }
            val masks = Array(order.size) { IntArray(n) }
            for (i in 0 until n) for (k in order.indices) masks[k][i] = b.short.toInt() and 0xFFFF
            return RelayedInputs(epoch, start, seats, masks, sumFrame, sum)
        }
    }
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
 * One input packet from phone [from] (its member id): the sender's unacknowledged inputs
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
        private const val VERSION: Byte = 6
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
