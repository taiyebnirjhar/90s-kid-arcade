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
            PspTitle(
                "acb", "Assassin's Creed", "Bloodlines", "bloodlines",
                "Named_Titles/Assassin's Creed - Bloodlines (USA) (En,Fr,De,Es,It).png",
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
    /** The picture follows what the phone can do ([PspGuard]): down in heavy scenes, back up when smooth. */
    val auto: Boolean = true,
) {
    /** How the PSP's picture is stretched to the screen (a shader after the game, nearly free). */
    enum class Picture(val label: String) { SHARP("Sharp"), SMOOTH("Smooth"), ENHANCED("Enhanced edges") }
}

/**
 * Auto (on unless the player turns it off): the picture follows what the
 * phone can do. When the last 10 seconds mostly ran slow it steps down, the
 * cheapest look first (Enhanced edges to Smooth, then 2x to 1x; never
 * faster effects, which blanks some games' screens: God of War); after a steady stretch at full speed it steps back up, one step
 * at a time, never past what the player chose. The owner's phone (Adreno 610)
 * ran God of War's calm places at 2x Sharp at full speed and its big fights
 * at 30-40 fps; Enhanced edges at 20-38 fps (1.0.303's log).
 */
object PspGuard {
    const val WINDOW = 10
    const val SLOW = 50
    /** Full speed, for stepping back up. */
    const val FULL = 57
    /** Seconds at full speed before a step back up. */
    const val STEADY = 30

    /** The settings to step down to, and what to tell the player; null if it's fast enough or nothing's left to drop. */
    fun stepDown(s: PspSettings, perSecond: List<Int>): Pair<PspSettings, String>? {
        if (perSecond.size < WINDOW) return null
        val last = perSecond.takeLast(WINDOW)
        if (last.count { it < SLOW } < 7 || last.average() >= 45) return null
        return when {
            s.picture == PspSettings.Picture.ENHANCED -> s.copy(picture = PspSettings.Picture.SMOOTH) to "Heavy scene: picture to Smooth for now"
            s.resolution >= 2 -> s.copy(resolution = 1) to "Heavy scene: sharpness 1x for now"
            else -> null
        }
    }

    /**
     * One step back toward [chosen] once the last [steady] seconds all ran at
     * full speed (the reverse order: sharpness first, the look the player
     * notices most); null if it's already there or not steady yet.
     */
    fun stepUp(s: PspSettings, chosen: PspSettings, perSecond: List<Int>, steady: Int = STEADY): Pair<PspSettings, String>? {
        if (perSecond.size < steady || perSecond.takeLast(steady).any { it < FULL }) return null
        return when {
            s.resolution < chosen.resolution -> s.copy(resolution = chosen.resolution) to "Running smooth: sharpness back to ${chosen.resolution}x"
            s.picture != chosen.picture -> s.copy(picture = chosen.picture) to "Running smooth: picture back to ${chosen.picture.label}"
            else -> null
        }
    }
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
        // One frame queued for the graphics chip, not two: a press shows a frame
        // sooner (the owner, 1.0.316: "few frame behind" at 2x).
        "ppsspp_inflight_frames" to "Up to 1",
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
    /**
     * Whether "Faster effects" (PPSSPP skipping buffer effects) works in this
     * game. God of War draws through those buffers: with it on the screen goes
     * blank (the owner's phone, 1.0.316), so it's never used there.
     */
    val fasterEffectsOk: Boolean = true,
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
         * magic; □ □ △ (Plume of Prometheus) and L + □ (Cyclone of Chaos, from level 2)
         * each come out as their own combo; L+R with the stick rolls; the Mash
         * pill escapes the first grab.
         */
        val GOD_OF_WAR = PspProfile(
            labels = mapOf('A' to "Light", 'B' to "Jump", 'C' to "Heavy", 'D' to "Grab"),
            // R stays held from one tap to the next (the owner, 2026-11-07: "Need
            // combo attack including press R for fire"): with Thera's Bane on,
            // every combo below comes out on fire; with the spear, it aims (□ throws).
            system = listOf(Move("L", HOLD_L), Move("R", LATCH_R), Move("Roll", "EF"), Move("Weapon", "pad:2"), Move("Mash LR", MASH_LR), Move("Mash ○", MASH_O)),
            blockHoldsL = true,
            shoulderHints = "Block" to "Fire / Aim",
            fasterEffectsOk = false,
            // No inPlay: 0x08C5D8D1 read 0 in every menu and 4 in play on the
            // boat (tools/pspemu.py), but on the owner's phone it dropped to 0
            // in the middle of play too (1.0.292's log), flipping the stick to
            // the D-pad. The owner: "If this cant be solved. Make dpad manual
            // like before". The D-PAD switch is the only way again.
            // From the game's own move list (the owner's screenshots, 2026-11-07:
            // Cyclone of Chaos is L + □; hold □ is Olympic Slash, hold △ Olympic
            // Ascension, ○ on a foe in the air Orion's Harpoon) and checked in
            // the game (tools/pspemu.py, the boat): Hyperion Charge comes out;
            // □ □ △ needs its presses about 14 frames apart (2 hits at the
            // arcade's quick timing, 4 and the △ slam spaced); □ ×6 likewise
            // (5 hits quick, 8 spaced). Cyclone of Chaos comes at blade level 2,
            // after the boat. "Hyperion Rush" (□ during the charge) did nothing:
            // gone. The game's Moves screen (read in the game, 2026-11-07) names
            // □ □ △ Plume of Prometheus and lists, all checked on the boat: Hold
            // □ Olympic Slash, Hold △ Olympic Ascension, ○ on a foe in the air
            // Orion's Harpoon, ✕ in the air Icarus Lift, L + ○ in the air
            // (after a double jump) Hyperion Charge, tap L Athena's Revenge.
            // Those are the face buttons as they are; the six pills are the ones
            // that need several buttons. Its Magic group is locked this early,
            // so the magic pills (the owner's list) wait to be seen on his phone.
            moves = listOf(
                Move("Hyperion Charge", "E>D"),
                Move("Cyclone of Chaos", "E>A"),
                Move("Plume of Prometheus", "A,wait:10,A,wait:10,C"),
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

        /** A pill that keeps R held from one tap to the next (God of War's Thera's Bane, or the spear's aim). */
        const val LATCH_R = "latch:F"

        /**
         * The game's mashing prompts, one pill each (the owner, 2026-11-07:
         * "Press l+r continuously should be one mash. If needed u can create
         * another"). Mash L/R: "alternate L and R quickly" (a grab to escape),
         * L R L R… about 4.7 s. Checked in Ghost of Sparta on the boat's first
         * grab: L R escapes it; the old L ○ R ○ didn't (too slow between an L
         * and the next R), nor ○ alone.
         */
        val MASH_LR: String get() = List(20) { "E,F" }.joinToString(",")

        /** Mash ○: "press ○ quickly" (a chain to pull, a door to lift), ○ ○ ○… about 4.2 s. */
        val MASH_O: String get() = List(36) { "D" }.joinToString(",")

        /** Every mash pill: played frame by frame (their timing is the one checked in the game). */
        val MASHES: Set<String> get() = setOf(MASH_LR, MASH_O)

        /** The profile for a game, from its title. */
        /**
         * Assassin's Creed: Bloodlines, from the game's own tutorial and its
         * button panel (top right), read in the game (tools/pspemu.py):
         * the stick walks; R held is High Profile (the stick runs; ✕ then
         * sprints and climbs, ○ tackles); L held turns the camera with the
         * four buttons (a tap centres it); △ targets, □ attacks, ✕ held
         * blends (Low Profile). The game's first questions (install, save)
         * want the D-pad: the D-PAD switch.
         */
        val ASSASSINS_CREED = PspProfile(
            // The game's own button panel (read in the game, 2026-11-07): out of a
            // fight ✕ blends and △ targets (Talk near someone); in a fight R blocks,
            // ✕ dodges, △ disengages, ○ grabs; with R held ✕ sprints, ○ tackles.
            labels = mapOf('A' to "Attack", 'B' to "Blend/Dodge", 'C' to "Target", 'D' to "Grab"),
            system = listOf(
                Move("L", HOLD_L), Move("R", HOLD_R),
                // R and ✕ together, for one thumb: the stick steers.
                Move("Sprint", "held:FB"),
            ),
            shoulderHints = "Camera" to "Run / Block",
            moves = listOf(
                Move("Tackle", "F>D"),
            ),
        )

        fun forTitle(title: String): PspProfile = when {
            title.contains("god of war", ignoreCase = true) -> GOD_OF_WAR
            title.contains("assassin", ignoreCase = true) -> ASSASSINS_CREED
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
 * commands: "pad:6" taps the D-pad right, "hold:A" holds a button (Olympic
 * Slash is □ held), "wait:10" lets go for ten frames, "BE" presses buttons together; steps go one after another
 * ("pad:6,BE": pick a magic, then cast it). Gives the PSP key codes held on
 * each frame, for the game screen to play.
 */
object PspSteps {
    const val TAP = 4
    const val GAP = 3
    const val HOLD = 45

    fun isSteps(cmd: String) = cmd.split(',').any { it.startsWith("pad:") || it.startsWith("hold:") || '>' in it || isWait(it) }

    /** "wait:10": ten frames with nothing pressed (God of War takes a combo's next press only once the last swing's on). */
    private fun isWait(step: String) = step.startsWith("wait:")

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
            if (isWait(step)) {
                repeat(step.removePrefix("wait:").toIntOrNull() ?: 0) { add(emptySet()) }
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
