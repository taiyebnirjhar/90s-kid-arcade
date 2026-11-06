package com.kofarcade

import com.kofarcade.core.Key
import com.kofarcade.core.Move
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/**
 * PSP games kept on the phone (docs/psp-plan.md): the owner's own disc
 * images, as PPSSPP reads them. A download packed as .7z or .zip is
 * unpacked on the way in, so a game is always one ready file.
 */
object PspGames {
    /** What PPSSPP plays: disc images (plain, CSO, CHD) and PSN eboots. */
    val GAME_TYPES = setOf("iso", "cso", "chd", "pbp")

    fun isGame(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in GAME_TYPES

    /** The games in [dir], A to Z. */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && isGame(f.name) }.orEmpty().sortedBy { it.name.lowercase() }

    /** "God of War - Ghost of Sparta (USA)" from its file name. */
    fun title(file: File): String = file.nameWithoutExtension.replace('_', ' ').trim()

    class NotAGame(message: String) : IOException(message)

    /**
     * Puts the game in [name] into [dir] and returns its file. A .7z is read
     * through [channel] (it needs to seek), anything else from [open]. Writes to
     * a temporary file first, so a game is only ever there whole. [progress]
     * gets the bytes written so far.
     */
    fun import(
        name: String,
        dir: File,
        open: () -> InputStream,
        channel: () -> SeekableByteChannel,
        progress: (Long) -> Unit = {},
    ): File {
        dir.mkdirs()
        return when (name.substringAfterLast('.', "").lowercase()) {
            "7z" -> SevenZFile.builder().setSeekableByteChannel(channel()).get().use { z ->
                generateSequence { z.nextEntry }
                    .firstOrNull { !it.isDirectory && isGame(it.name) }
                    ?.let { entry -> save(entry.name.substringAfterLast('/'), dir, z.getInputStream(entry), progress) }
                    ?: throw NotAGame("No PSP game (.iso, .cso, .chd) inside $name")
            }
            "zip" -> ZipInputStream(open().buffered()).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { !it.isDirectory && isGame(it.name) }
                    ?.let { entry -> save(entry.name.substringAfterLast('/'), dir, zip, progress) }
                    ?: throw NotAGame("No PSP game (.iso, .cso, .chd) inside $name")
            }
            else -> {
                if (!isGame(name)) throw NotAGame("$name isn't a PSP game: pick a .iso, .cso, .chd, .7z or .zip")
                open().use { save(name.substringAfterLast('/'), dir, it, progress) }
            }
        }
    }

    private fun save(name: String, dir: File, input: InputStream, progress: (Long) -> Unit): File {
        val out = File(dir, name)
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                total += n
                progress(total)
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException("Couldn't keep $name")
        return out
    }
}

/**
 * A game's settings, kept per game: how it's drawn and the safety net.
 * The defaults keep it at full speed on a modest phone (Snapdragon 680
 * class): the PSP's own resolution, frame skipping only when it falls
 * behind (the game never slows; a frame's picture is skipped instead).
 */
data class PspSettings(
    val frameSkip: PspSpeed.FrameSkip = PspSpeed.FrameSkip.AUTO,
    /** 1 = the PSP's own 480x272, 2 = twice as sharp (costs the graphics chip). */
    val resolution: Int = 1,
    /** Skips some full-screen effects (glows, blur) for speed. */
    val fasterEffects: Boolean = false,
    val picture: Picture = Picture.SHARP,
    val controlsSize: Float = 0.85f,
    val controlsOpacity: Float = 0.6f,
) {
    /** How the PSP's picture is stretched to the screen (a shader after the game, nearly free). */
    enum class Picture(val label: String) { SHARP("Sharp"), SMOOTH("Smooth"), ENHANCED("Enhanced edges") }
}

/** The PPSSPP options for [settings]. */
object PspSpeed {
    enum class FrameSkip(val label: String, val value: String, val auto: Boolean) {
        OFF("Off", "disabled", false),
        ONE("1", "1", false),
        AUTO("Auto (only when behind)", "1", true),
    }

    fun options(skip: FrameSkip = FrameSkip.OFF): Map<String, String> = options(PspSettings(frameSkip = skip))

    fun options(settings: PspSettings): Map<String, String> = mapOf(
        "ppsspp_cpu_core" to "JIT",
        "ppsspp_fast_memory" to "enabled",
        "ppsspp_io_timing_method" to "Fast",
        "ppsspp_backend" to "opengl",
        "ppsspp_internal_resolution" to if (settings.resolution >= 2) "960x544" else "480x272",
        "ppsspp_mulitsample_level" to "Disabled",
        "ppsspp_texture_scaling_level" to "disabled",
        "ppsspp_texture_anisotropic_filtering" to "disabled",
        "ppsspp_texture_filtering" to "Auto",
        "ppsspp_lower_resolution_for_effects" to "disabled",
        "ppsspp_skip_buffer_effects" to if (settings.fasterEffects) "enabled" else "disabled",
        "ppsspp_skip_gpu_readbacks" to "enabled",
        "ppsspp_inflight_frames" to "Up to 2",
        // The whole disc in memory would take 1-2 GB: read it as needed.
        "ppsspp_cache_iso" to "disabled",
        "ppsspp_button_preference" to "Cross",
        "ppsspp_language" to "English",
        "ppsspp_frameskip" to settings.frameSkip.value,
        "ppsspp_frameskiptype" to "Number of frames",
        "ppsspp_auto_frameskip" to if (settings.frameSkip.auto) "enabled" else "disabled",
    )
}

/**
 * How fast the game runs, second by second, for the plan's rule: full speed in
 * 99% of seconds, no stall over a second. The PSP draws 59.94 frames a
 * second; a second with fewer than [SLOW_BELOW] counts as slow.
 */
class SpeedMeter {
    /** Stalls of a quarter second or more: when (seconds into the count) and how long (ms). */
    private val stallList = ArrayList<Pair<Int, Long>>()
    val stalls: List<Pair<Int, Long>> @Synchronized get() = stallList.toList()

    var seconds = 0
        private set
    var slowSeconds = 0
        private set
    var lastFps = 0
        private set
    /** The longest gap between two frames so far (ms). */
    var longestStallMs = 0L
        private set

    private var secondStart = -1L
    private var framesThisSecond = 0
    private var lastFrame = -1L

    /** A frame came out at [nowMs]. */
    @Synchronized
    fun frame(nowMs: Long) {
        if (lastFrame >= 0) {
            val gap = nowMs - lastFrame
            longestStallMs = maxOf(longestStallMs, gap)
            if (gap >= STALL_MS) stallList += seconds to gap
        }
        lastFrame = nowMs
        if (secondStart < 0) secondStart = nowMs
        framesThisSecond++
        while (nowMs - secondStart >= 1000) close()
    }

    private fun close() {
        lastFps = framesThisSecond
        seconds++
        if (framesThisSecond < SLOW_BELOW) slowSeconds++
        framesThisSecond = 0
        secondStart += 1000
    }

    /** Percent of seconds at full speed (100 before the first second). */
    val fullSpeedPercent: Int get() = if (seconds == 0) 100 else ((seconds - slowSeconds) * 100) / seconds

    @Synchronized
    fun reset() {
        stallList.clear()
        seconds = 0; slowSeconds = 0; lastFps = 0; longestStallMs = 0
        secondStart = -1; framesThisSecond = 0; lastFrame = -1
    }

    companion object {
        /** 95% of 59.94: a second under this the eye sees as slow. */
        const val SLOW_BELOW = 57
        /** A gap this long between frames counts as a stall. */
        const val STALL_MS = 250L
    }
}

/**
 * A PSP game's controls on the arcade layout (the KOF 2002 one): the same
 * base for every game, stick, A B C D, SELECT and START on top, and on top
 * of it what this game needs, like a fighter's own moves.
 *
 * The PSP's buttons on the arcade keys: A = square, B = cross, C = triangle,
 * D = circle (bottom row, then top), E = L, F = R, COIN = SELECT. The face
 * buttons show the PSP's symbols, since the game says "press ○".
 */
data class PspProfile(
    /** What each of A-D does in this game ("Light"), under its symbol. */
    val labels: Map<Char, String> = emptyMap(),
    /** SELECT on the top bar; off for a game that never uses it. */
    val select: Boolean = true,
    /** Pills over the buttons: one tap plays its keys (L, R, L+R...). */
    val system: List<Move> = listOf(Move("L", "E"), Move("R", "F")),
    /** A Block button above the stick that holds R while pressed. */
    val blockHoldsR: Boolean = false,
    /** One-tap moves on the ring, like a fighter's specials. */
    val moves: List<Move> = emptyList(),
) {
    companion object {
        /** God of War (Chains of Olympus, Ghost of Sparta): R blocks; L and R together roll, steered with the stick. */
        val GOD_OF_WAR = PspProfile(
            labels = mapOf('A' to "Light", 'B' to "Jump", 'C' to "Heavy", 'D' to "Grab"),
            select = false,
            system = listOf(Move("Roll", "EF"), Move("L", "E")),
            blockHoldsR = true,
        )

        /** The PSP's symbols on the arcade face buttons. */
        val FACE_NAMES = mapOf('A' to "□", 'B' to "✕", 'C' to "△", 'D' to "○")

        /** The profile for a game, from its title. */
        fun forTitle(title: String): PspProfile = when {
            title.contains("god of war", ignoreCase = true) -> GOD_OF_WAR
            else -> PspProfile()
        }

        /** The Android key code LibretroDroid gives the core as the PSP button for [key], or null. */
        fun keyCode(key: Key): Int? = when (key) {
            Key.A -> android.view.KeyEvent.KEYCODE_BUTTON_Y // square
            Key.B -> android.view.KeyEvent.KEYCODE_BUTTON_B // cross
            Key.C -> android.view.KeyEvent.KEYCODE_BUTTON_X // triangle
            Key.D -> android.view.KeyEvent.KEYCODE_BUTTON_A // circle
            Key.E -> android.view.KeyEvent.KEYCODE_BUTTON_L1
            Key.F -> android.view.KeyEvent.KEYCODE_BUTTON_R1
            Key.COIN -> android.view.KeyEvent.KEYCODE_BUTTON_SELECT
            Key.START -> android.view.KeyEvent.KEYCODE_BUTTON_START
            else -> null // directions go to the analog stick
        }
    }
}
