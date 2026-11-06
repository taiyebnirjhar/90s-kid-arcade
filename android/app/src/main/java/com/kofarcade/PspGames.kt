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
/**
 * A PSP game the app knows by name: a card of its own on Home, with its
 * picture (packed at build time from libretro's PSP collection, psp-art.txt).
 * The owner's copy, once added, is the file whose name says [match].
 */
data class PspTitle(val id: String, val title: String, val subtitle: String, val match: String, val art: String) {
    fun copyIn(games: List<File>): File? = games.firstOrNull { PspGames.title(it).lowercase().contains(match) }

    companion object {
        val ALL = listOf(
            PspTitle(
                "gos", "God of War", "Ghost of Sparta", "ghost of sparta",
                "Named_Titles/God of War - Ghost of Sparta (USA) (En,Fr,Es).png",
            ),
        )
    }
}

object PspGames {
    /** A PSP game's last session log ([GameLogs]). */
    fun logFile(filesDir: File, game: String) = GameLogs.file(filesDir, "psp-$game")

    /** What PPSSPP plays: disc images (plain, CSO, CHD) and PSN eboots. */
    val GAME_TYPES = setOf("iso", "cso", "chd", "pbp")

    fun isGame(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in GAME_TYPES

    /** The games in [dir], A to Z. */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && isGame(f.name) }.orEmpty().sortedBy { it.name.lowercase() }

    /** Where a game's last session log is kept. */

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
    // Off: the owner's log (2026-11-07) ran steadier with it off than on Auto (sound drops
    // from thousands a second to almost none, 58-60 fps); Auto stays a choice.
    val frameSkip: PspSpeed.FrameSkip = PspSpeed.FrameSkip.OFF,
    /** 1 = the PSP's own 480x272, 2 = twice as sharp (costs the graphics chip). */
    val resolution: Int = 1,
    /** Skips some full-screen effects (glows, blur) for speed. */
    val fasterEffects: Boolean = false,
    val picture: Picture = Picture.SHARP,
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
        // A frame for every one of the screen's, even when the game shows a
        // new picture less often (its story videos): without it each frame we
        // ask for ran on to the game's next picture, and Ghost of Sparta's
        // story played about 1.5x fast (checked in the game: 67,000 sound
        // samples a second instead of 44,100; with it, 44,100).
        "ppsspp_frame_duplication" to "enabled",
    )
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
    /** Pills over the buttons: one tap plays its keys (L+R...), or [HOLD_L]/[HOLD_R] held while pressed. */
    val system: List<Move> = listOf(Move("L", HOLD_L), Move("R", HOLD_R)),
    /** What L and R do in this game, under their names ("Block"). */
    val shoulderHints: Pair<String, String> = "" to "",
    /** A Block button above the stick that holds L while pressed (God of War blocks with L). */
    val blockHoldsL: Boolean = false,
    /** One-tap moves on the ring, like a fighter's specials. */
    val moves: List<Move> = emptyList(),
    /**
     * Where the game says whether it's in a menu (a byte of its RAM, from the
     * start of the PSP's main memory) and the value meaning a menu: the stick
     * works the D-pad then, and the analog stick otherwise. Null: only the
     * D-PAD switch on the top bar.
     */
    val inPlay: Pair<Int, Int>? = null,
) {
    companion object {
        /**
         * God of War: Ghost of Sparta (and Chains of Olympus), from the move
         * list the owner sent (2026-11-06, corrected against the game's manual;
         * to check in the game): □ light, △ heavy, ○ grab, ✕ jump; L held
         * blocks (a tap parries); L+R with the stick rolls; R held sets the
         * blades on fire (Thera's Bane), or aims the spear (Arms of Sparta); L+○
         * Hyperion Charge; L+✕ casts the magic picked with the D-pad (→ Eye of
         * Atlantis, ← Scourge of Erinys, ↑ Horn of Boreas); D-pad ↓ swaps
         * weapons.
         *
         * Checked in the game (tools/pspemu.py, the boat's first fight): □, △
         * and ○ attack; L, then ○ while L is held, is Hyperion Charge (both at
         * once is just ○); with L held, □ △ ✕ do nothing before Kratos has
         * magic; □ □ △ (Spirit of Hercules) and □ held (Cyclone of Chaos)
         * each come out as their own combo; L+R with the stick rolls; the Mash
         * pill escapes the first grab.
         */
        val GOD_OF_WAR = PspProfile(
            labels = mapOf('A' to "Light", 'B' to "Jump", 'C' to "Heavy", 'D' to "Grab"),
            system = listOf(Move("L", HOLD_L), Move("R", HOLD_R), Move("Roll", "EF"), Move("Weapon", "pad:2"), Move("Mash", MASH_LR)),
            blockHoldsL = true,
            shoulderHints = "Block" to "Fire / Aim",
            // Found in the game (tools/pspemu.py): 0 on the title, difficulty,
            // pause, Power Up and "You are dead" screens and in story videos;
            // 4 while Kratos is played on the boat (fights, grabs, prompts),
            // 2-3 for a moment between. The owner's phone showed the D-pad
            // left on in a later area: so any value but 0 is play.
            inPlay = 0xC5D8D1 to 0,
            moves = listOf(
                Move("Hyperion Charge", "E>D"),
                Move("Spirit of Hercules", "A,A,C"),
                Move("Cyclone of Chaos", "hold:A", "dm"),
                Move("Atlas Spark", "pad:6,E>B", "dm"),
                Move("Erinys Void", "pad:4,E>B", "dm"),
                Move("Boreas Freeze", "pad:8,E>B", "dm"),
            ),
        )

        /** The PSP's names for the arcade keys: symbols on the face buttons, L and R for E and F. */
        val FACE_NAMES = mapOf('A' to "□", 'B' to "✕", 'C' to "△", 'D' to "○", 'E' to "L", 'F' to "R")

        /** Pills' commands for "held while pressed": L or R stays down for as long as the finger does. */
        const val HOLD_L = "held:E"
        const val HOLD_R = "held:F"

        /**
         * The game's "alternate L and R quickly" prompts (a grab to escape, a
         * door to push): L, R, L, R… twelve times, about two seconds. Checked
         * in Ghost of Sparta: it escapes the first grab on the boat.
         */
        val MASH_LR: String get() = List(12) { "E,F" }.joinToString(",")

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

/**
 * A PSP game's own steps on the ring and the pills, beyond the arcade
 * commands: "pad:6" taps the D-pad right, "hold:A" holds a button (Cyclone of
 * Chaos is □ held), "BE" presses buttons together; steps go one after another
 * ("pad:6,BE": pick a magic, then cast it). Gives the PSP key codes held on
 * each frame, for the game screen to play.
 */
object PspSteps {
    const val TAP = 4
    const val GAP = 3
    const val HOLD = 45

    fun isSteps(cmd: String) = cmd.split(',').any { it.startsWith("pad:") || it.startsWith("hold:") || '>' in it }

    /** The key codes held on each frame, in order (an empty set lets everything go). */
    fun frames(cmd: String): List<Set<Int>> = buildList {
        for (step in cmd.split(',')) {
            // "E>D": E held first, then D with it (God of War: L, then ○ while L is
            // held, is Hyperion Charge; both at once is just ○, checked in the game).
            if ('>' in step) {
                val (first, then) = step.split('>', limit = 2)
                repeat(TAP * 2) { add(keys(first)) }
                repeat(TAP + 2) { add(keys(first) + keys(then)) }
                repeat(GAP) { add(emptySet()) }
                continue
            }
            val (keys, frames) = when {
                step.startsWith("pad:") -> setOfNotNull(dpad(step.removePrefix("pad:").toIntOrNull())) to TAP + 2
                step.startsWith("hold:") -> keys(step.removePrefix("hold:")) to HOLD
                else -> keys(step) to TAP
            }
            repeat(frames) { add(keys) }
            repeat(GAP) { add(emptySet()) }
        }
    }

    private fun keys(buttons: String): Set<Int> =
        buttons.mapNotNull { c -> Key.entries.firstOrNull { it.name == c.toString() }?.let(PspProfile::keyCode) }.toSet()

    private fun dpad(dir: Int?): Int? = when (dir) {
        8 -> android.view.KeyEvent.KEYCODE_DPAD_UP
        2 -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        4 -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        6 -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null
    }
}
