package com.kofarcade

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
 * The PPSSPP settings that keep the game at full speed on a modest phone
 * (Snapdragon 680 class): the PSP's own resolution, nothing that costs the
 * graphics chip extra. Frame skipping is the safety net, off unless chosen.
 */
object PspSpeed {
    enum class FrameSkip(val label: String, val value: String, val auto: Boolean) {
        OFF("Off", "disabled", false),
        ONE("1", "1", false),
        AUTO("Auto", "1", true),
    }

    fun options(skip: FrameSkip = FrameSkip.OFF): Map<String, String> = mapOf(
        "ppsspp_cpu_core" to "JIT",
        "ppsspp_fast_memory" to "enabled",
        "ppsspp_io_timing_method" to "Fast",
        "ppsspp_backend" to "opengl",
        "ppsspp_internal_resolution" to "480x272",
        "ppsspp_mulitsample_level" to "Disabled",
        "ppsspp_texture_scaling_level" to "disabled",
        "ppsspp_texture_anisotropic_filtering" to "disabled",
        "ppsspp_texture_filtering" to "Auto",
        "ppsspp_lower_resolution_for_effects" to "disabled",
        "ppsspp_skip_gpu_readbacks" to "enabled",
        "ppsspp_inflight_frames" to "Up to 2",
        // The whole disc in memory would take 1-2 GB: read it as needed.
        "ppsspp_cache_iso" to "disabled",
        "ppsspp_button_preference" to "Cross",
        "ppsspp_language" to "English",
        "ppsspp_frameskip" to skip.value,
        "ppsspp_frameskiptype" to "Number of frames",
        "ppsspp_auto_frameskip" to if (skip.auto) "enabled" else "disabled",
    )
}

/**
 * How fast the game runs, second by second, for the plan's rule: full speed in
 * 99% of seconds, no stall over a second. The PSP draws 59.94 frames a
 * second; a second with fewer than [SLOW_BELOW] counts as slow.
 */
class SpeedMeter {
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
    fun frame(nowMs: Long) {
        if (lastFrame >= 0) longestStallMs = maxOf(longestStallMs, nowMs - lastFrame)
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

    fun reset() {
        seconds = 0; slowSeconds = 0; lastFps = 0; longestStallMs = 0
        secondStart = -1; framesThisSecond = 0; lastFrame = -1
    }

    companion object {
        /** 95% of 59.94: a second under this the eye sees as slow. */
        const val SLOW_BELOW = 57
    }
}
