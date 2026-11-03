package com.kofarcade.core.replay

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

// A saved match (docs/recording-plan.md): the game's state at its first
// frame, every player's input for every frame, and the RAM checksums along
// the way. Run again from that state with those inputs, the game plays the
// same match; the checksums prove it.

/** What a list of matches shows, read without the rest of the file. */
@Serializable
data class MatchInfo(
    /** [com.kofarcade.core.Game]'s id. */
    val game: String,
    /** The game's name as the app shows it. */
    val title: String,
    /** When it began (ms since 1970). */
    val startedAt: Long,
    /** Frames recorded (60 a second). */
    val frames: Int,
    /** Played in a room (online or nearby), not alone. */
    val room: Boolean = false,
    /** The players' names, by seat, where known. */
    val players: List<String> = emptyList(),
) {
    val seconds: Int get() = frames / 60
}

/** A recording as the emulator hands it over ([parse]). */
class Recording(
    /** The frame the state is at, and the first input's. */
    val first: Int,
    /** Inputs a frame: one a seat. */
    val ports: Int,
    /** [frames] x [ports] button masks, frame by frame. */
    val masks: ShortArray,
    /** (frame, sum): the checksum once the frames before [frame] have run. */
    val sums: List<Pair<Int, Long>>,
    val state: ByteArray,
) {
    val frames: Int get() = if (ports == 0) 0 else masks.size / ports

    fun mask(frame: Int, port: Int): Int = masks[(frame - first) * ports + port].toInt() and 0xFFFF

    companion object {
        private val MAGIC = "90SKREC1".toByteArray()

        /** The emulator's block (LibretroDroid.recTake, see MatchRecorder::take); null if empty or not one. */
        fun parse(block: ByteArray): Recording? {
            if (block.size < 28 || !block.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            fun int(at: Int) = (block[at].toInt() and 0xFF) or ((block[at + 1].toInt() and 0xFF) shl 8) or
                ((block[at + 2].toInt() and 0xFF) shl 16) or ((block[at + 3].toInt() and 0xFF) shl 24)
            val first = int(8)
            val ports = int(12)
            val frames = int(16)
            val sumCount = int(20)
            val stateSize = int(24)
            if (ports !in 1..8 || frames < 0 || sumCount < 0 || stateSize <= 0) return null
            val inputsAt = 28
            val sumsAt = inputsAt + frames.toLong() * ports * 2
            val stateAt = sumsAt + sumCount.toLong() * 12
            if (stateAt + stateSize != block.size.toLong()) return null
            val masks = ShortArray(frames * ports) { i ->
                val at = inputsAt + i * 2
                ((block[at].toInt() and 0xFF) or ((block[at + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            val sums = List(sumCount) { i ->
                val at = (sumsAt + i * 12).toInt()
                int(at) to ((int(at + 4).toLong() and 0xFFFFFFFFL) or (int(at + 8).toLong() shl 32))
            }
            return Recording(first, ports, masks, sums, block.copyOfRange(stateAt.toInt(), block.size))
        }
    }
}

/**
 * The file a match is kept in:
 *   "90SKMATCH", a version byte, the [MatchInfo] (2-byte length, JSON),
 *   then, compressed (deflate): first frame, ports, frames, each port's
 *   inputs as runs (a button mask held for so many frames: most of a match
 *   is runs), the sums, the state.
 * The info comes first so a list reads just that ([readInfo]).
 */
object MatchFile {
    private val MAGIC = "90SKMATCH".toByteArray()
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun write(info: MatchInfo, rec: Recording): ByteArray {
        val out = ByteArrayOutputStream()
        val head = DataOutputStream(out)
        head.write(MAGIC)
        head.writeByte(VERSION)
        val infoBytes = json.encodeToString(info).toByteArray()
        head.writeShort(infoBytes.size)
        head.write(infoBytes)
        head.flush()
        DataOutputStream(DeflaterOutputStream(out)).use { body ->
            body.writeInt(rec.first)
            body.writeInt(rec.ports)
            body.writeInt(rec.frames)
            for (port in 0 until rec.ports) {
                var f = 0
                while (f < rec.frames) {
                    val mask = rec.mask(rec.first + f, port)
                    var run = 1
                    while (f + run < rec.frames && rec.mask(rec.first + f + run, port) == mask) run++
                    writeVarInt(body, run)
                    body.writeShort(mask)
                    f += run
                }
            }
            body.writeInt(rec.sums.size)
            for ((frame, sum) in rec.sums) {
                body.writeInt(frame)
                body.writeLong(sum)
            }
            body.writeInt(rec.state.size)
            body.write(rec.state)
        }
        return out.toByteArray()
    }

    /** Just the info (for a list): reads only the file's start. */
    fun readInfo(input: InputStream): MatchInfo {
        val head = DataInputStream(input)
        readHead(head)
        return readInfoJson(head)
    }

    fun read(bytes: ByteArray): Pair<MatchInfo, Recording> {
        val head = DataInputStream(ByteArrayInputStream(bytes))
        readHead(head)
        val info = readInfoJson(head)
        val body = DataInputStream(InflaterInputStream(head))
        val first = body.readInt()
        val ports = body.readInt()
        val frames = body.readInt()
        if (ports !in 1..8 || frames < 0) throw IOException("Not a match file (ports $ports, frames $frames)")
        val masks = ShortArray(frames * ports)
        for (port in 0 until ports) {
            var f = 0
            while (f < frames) {
                val run = readVarInt(body)
                val mask = body.readShort()
                if (run < 1 || f + run > frames) throw IOException("A broken match file (inputs)")
                repeat(run) { masks[(f + it) * ports + port] = mask }
                f += run
            }
        }
        val sums = List(body.readInt().also { if (it < 0) throw IOException("A broken match file (sums)") }) {
            body.readInt() to body.readLong()
        }
        val state = ByteArray(body.readInt().also { if (it <= 0) throw IOException("A broken match file (state)") })
        body.readFully(state)
        return info to Recording(first, ports, masks, sums, state)
    }

    private fun readHead(head: DataInputStream) {
        val magic = ByteArray(MAGIC.size)
        head.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a match file")
        val version = head.readUnsignedByte()
        if (version != VERSION) throw IOException("A match file from a newer version ($version)")
    }

    private fun readInfoJson(head: DataInputStream): MatchInfo {
        val bytes = ByteArray(head.readUnsignedShort())
        head.readFully(bytes)
        return json.decodeFromString(MatchInfo.serializer(), String(bytes))
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("A broken match file (a number)")
    }
}

/** A saved match on the phone: its file's name, when it was saved, and whether it's starred. */
data class SavedMatch(val name: String, val savedAt: Long, val starred: Boolean)

object MatchShelf {
    /** Matches kept when saving is on (starred ones on top of these). */
    const val KEEP = 20

    /** Shorter than this, a recording isn't kept (a game opened and closed). */
    const val MIN_FRAMES = 10 * 60

    /** The matches to delete: all but the newest [keep] unstarred ones; starred ones always stay. */
    fun overflow(matches: List<SavedMatch>, keep: Int = KEEP): List<SavedMatch> =
        matches.filter { !it.starred }.sortedByDescending { it.savedAt }.drop(keep)
}

/**
 * Whether a replay is the match: each checksum the replay reaches is
 * compared with the one saved when it was played ([see] is given the
 * newest one the emulator has, as often as liked).
 */
class ReplayCheck(sums: List<Pair<Int, Long>>) {
    private val expected = sums.toMap()
    private var last = -1

    /** Checksums that matched so far. */
    var matched = 0
        private set

    /** The first frame whose checksum didn't match, or -1. */
    var wrongAt = -1
        private set

    fun see(frame: Int, sum: Long) {
        if (frame <= last) return
        last = frame
        val want = expected[frame] ?: return
        if (want == sum) matched++ else if (wrongAt < 0) wrongAt = frame
    }

    /** Every checksum reached so far matched (and there was one). */
    val matches: Boolean get() = wrongAt < 0 && matched > 0
}

/** How far ahead of the game a replay's inputs are handed in (more, and a watcher races to catch up). */
const val REPLAY_LEAD = 4

/**
 * A video's sound, a frame at a time: each game frame's own sound (whatever
 * its length: 735, 745, 800 samples… the core's real rate, whatever it
 * reports) becomes exactly [perFrame] samples, the frame's 1/60 of a second
 * at the video's rate. Picture and sound can't come apart: every frame's
 * sound is that frame's. Stretched by linear interpolation, carried on from
 * the last frame's last sample so no click where frames meet. Stereo
 * 16-bit, interleaved.
 */
class FrameSound(private val perFrame: Int) {
    private var lastL = 0
    private var lastR = 0

    fun fit(input: ShortArray): ShortArray {
        val n = input.size / 2
        val out = ShortArray(perFrame * 2)
        if (n == 0) {
            // A frame without sound: the last sample held (silence if there was none).
            for (i in 0 until perFrame) {
                out[i * 2] = lastL.toShort()
                out[i * 2 + 1] = lastR.toShort()
            }
            return out
        }
        // Index -1 is the previous frame's last sample.
        fun l(i: Int) = if (i < 0) lastL else input[minOf(i, n - 1) * 2].toInt()
        fun r(i: Int) = if (i < 0) lastR else input[minOf(i, n - 1) * 2 + 1].toInt()
        val step = n.toDouble() / perFrame
        for (j in 0 until perFrame) {
            // Output j sits at this point of the frame's input: from just after the
            // previous frame's last sample (-1) to this frame's last (n - 1).
            val pos = (j + 1) * step - 1
            val i = kotlin.math.floor(pos).toInt()
            val f = pos - i
            out[j * 2] = (l(i) + (l(i + 1) - l(i)) * f).toInt().toShort()
            out[j * 2 + 1] = (r(i) + (r(i + 1) - r(i)) * f).toInt().toShort()
        }
        lastL = input[(n - 1) * 2].toInt()
        lastR = input[(n - 1) * 2 + 1].toInt()
        return out
    }
}
