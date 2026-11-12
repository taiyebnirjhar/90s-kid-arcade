package com.kofarcade

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class PspGamesTest {
    private val tmp: File = Files.createTempDirectory("psp").toFile()
    private val game = Random(1).nextBytes(3_000_000)

    private fun import(file: File, dir: File = File(tmp, "games")) = FileInputStream(file).use { fis ->
        PspGames.import(file.name, dir, open = { FileInputStream(file) }, channel = { fis.channel })
    }

    @Test
    fun aSevenZipDownloadIsUnpackedToItsGame() {
        val download = File(tmp, "God of War_ Ghost of Sparta.7z")
        SevenZOutputFile(download).use { z ->
            z.putArchiveEntry(z.createArchiveEntry(File(tmp, "readme.txt").apply { writeText("hi") }, "readme.txt"))
            z.write("hi".toByteArray()); z.closeArchiveEntry()
            val iso = File(tmp, "x.iso").apply { writeBytes(game) }
            z.putArchiveEntry(z.createArchiveEntry(iso, "God of War - Ghost of Sparta (USA).iso"))
            z.write(game); z.closeArchiveEntry()
        }
        val out = import(download)
        assertEquals("God of War - Ghost of Sparta (USA).iso", out.name)
        assertArrayEquals(game, out.readBytes())
        assertFalse("no half-written file left", File(out.parentFile, out.name + ".part").exists())
    }

    @Test
    fun aZipDownloadIsUnpackedAndAPlainImageCopied() {
        val zip = File(tmp, "game.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("folder/Game (USA).cso")); z.write(game); z.closeEntry()
        }
        assertArrayEquals(game, import(zip).readBytes())
        val chd = File(tmp, "Other (USA).chd").apply { writeBytes(game) }
        assertEquals("Other (USA).chd", import(chd).name)
        assertEquals(listOf("Game (USA).cso", "Other (USA).chd"), PspGames.list(File(tmp, "games")).map { it.name })
    }

    @Test
    fun somethingElseIsRefusedWithAReason() {
        val pdf = File(tmp, "manual.pdf").apply { writeText("x") }
        val e = runCatching { import(pdf) }.exceptionOrNull()
        assertTrue(e is PspGames.NotAGame)
        val zip = File(tmp, "empty.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(1); z.closeEntry() }
        assertTrue(runCatching { import(zip) }.exceptionOrNull() is PspGames.NotAGame)
    }

    @Test
    fun theTitleComesFromTheFileName() {
        assertEquals("God of War  Ghost of Sparta (USA)", PspGames.title(File("God of War_ Ghost of Sparta (USA).iso")))
    }

    @Test
    fun theSpeedMeterCountsSlowSecondsAndStalls() {
        val m = SpeedMeter()
        var t = 0L
        // 10 seconds at 60 frames a second.
        repeat(600) { m.frame(t); t += 1000 / 60 + if (it % 3 == 0) 1 else 0 }
        // Then a second at 40, and a 700 ms stall.
        repeat(40) { m.frame(t); t += 25 }
        t += 700
        repeat(120) { m.frame(t); t += 17 }
        assertTrue(m.seconds >= 11)
        assertTrue("the slow second and the stall's second", m.slowSeconds in 1..2)
        assertTrue(m.longestStallMs >= 700)
        assertTrue(m.fullSpeedPercent in 80..95)
    }

    @Test
    fun theFastSettingsKeepThePspsOwnResolution() {
        val o = PspSpeed.options()
        assertEquals("480x272", o["ppsspp_internal_resolution"])
        assertEquals("disabled", o["ppsspp_frameskip"])
        assertEquals("disabled", o["ppsspp_cache_iso"])
        assertEquals("enabled", PspSpeed.options(PspSpeed.FrameSkip.AUTO)["ppsspp_auto_frameskip"])
    }

    @Test
    fun needForSpeedLocksTheGasOn() {
        val p = PspProfile.forTitle("Need for Speed - Most Wanted - 5-1-0 (USA)")
        assertEquals(PspProfile.NEED_FOR_SPEED, p)
        assertEquals("Gas", p.labels['B'])
        // ✕ (accelerate) is held the whole race: Auto gas locks it on.
        assertTrue(p.system.any { it.cmd == PspProfile.LATCH_CROSS })
        assertTrue(PspProfile.LATCH_CROSS in PspProfile.LATCHES)
        assertTrue(p.system.any { it.cmd == PspProfile.HOLD_R && it.name == "E-brake" })
        assertNotNull(PspTitle.ALL.first { it.id == "nfs" })
    }

    @Test
    fun chainsOfOlympusCastsMagicWithRHeld() {
        val p = PspProfile.forTitle("God of War - Chains of Olympus (USA)")
        assertEquals(PspProfile.CHAINS_OF_OLYMPUS, p)
        // Magic is R held with a face button: R is held while pressed, not locked on.
        assertTrue(p.system.any { it.cmd == PspProfile.HOLD_R })
        assertTrue(p.system.none { it.cmd == PspProfile.LATCH_R })
        assertEquals("F>C", p.moves.first { it.name == "Efreet" }.cmd)
        // Ghost of Sparta's layout and settings (the three extra moves taken off again, 2026-11-12).
        assertEquals(6, p.moves.size)
        assertEquals(PspProfile.GOD_OF_WAR.layout, p.layout)
        assertEquals(false, p.fasterEffectsOk)
        assertEquals(PspProfile.GOD_OF_WAR, PspProfile.forTitle("God of War - Ghost of Sparta (USA)"))
    }

    @Test
    fun godOfWarGetsItsOwnButtonsOnTheArcadeLayout() {
        val p = PspProfile.forTitle("God of War - Ghost of Sparta (USA)")
        assertEquals("Light", p.labels['A'])
        assertEquals("Heavy", p.labels['C'])
        assertTrue("Block holds L", p.blockHoldsL)
        assertTrue("SELECT opens its pause menu", p.select)
        assertEquals(6, p.moves.size)
        // R stays held between taps: every combo on fire (Thera's Bane).
        assertEquals(PspProfile.LATCH_R, p.system.first { it.name == "R" }.cmd)
        // From the game's move list: Cyclone of Chaos is L + □.
        assertEquals("E>A", p.moves.first { it.name == "Cyclone of Chaos" }.cmd)
        // Spaced presses: checked in the game, quick ones cut the combo short.
        val spirit = PspSteps.frames(p.moves.first { it.name == "Plume of Prometheus" }.cmd)
        assertEquals(3 * (PspSteps.TAP + PspSteps.GAP) + 2 * 10, spirit.size)
        assertEquals("pad:6,E>B", p.moves.first { it.name == "Atlas Spark" }.cmd)
        // Checked in the game: L first, then ○ with it (both at once is just ○).
        assertEquals("E>D", p.moves.first { it.name == "Hyperion Charge" }.cmd)
        assertEquals("○", PspProfile.FACE_NAMES['D'])
        assertEquals("EF", p.system.first { it.name == "Roll" }.cmd)
        val base = PspProfile.forTitle("Need for Speed Most Wanted 5-1-0")
        assertEquals(PspProfile(), base)
        assertEquals(listOf("L", "R"), base.system.map { it.name })
        assertEquals(listOf(PspProfile.HOLD_L, PspProfile.HOLD_R), base.system.map { it.cmd })
        assertEquals(listOf("L", "R", "Roll", "Weapon", "Mash LR", "Mash ○"), p.system.map { it.name })
        // The arcade keys on the PSP's buttons: square, cross, triangle, circle, L, R, SELECT.
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_Y, PspProfile.keyCode(com.kofarcade.core.Key.A))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_B, PspProfile.keyCode(com.kofarcade.core.Key.B))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_R1, PspProfile.keyCode(com.kofarcade.core.Key.F))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, PspProfile.keyCode(com.kofarcade.core.Key.COIN))
        assertEquals(null, PspProfile.keyCode(com.kofarcade.core.Key.UP))
    }

    @Test
    fun theDefaultsNeverSkipAndCountStalls() {
        // Off by default: steadier on the owner's phone than Auto (his log, 2026-11-07).
        val o = PspSpeed.options(PspSettings())
        assertEquals("disabled", o["ppsspp_auto_frameskip"])
        assertEquals("disabled", o["ppsspp_frameskip"])
        assertEquals("480x272", o["ppsspp_internal_resolution"])
        assertEquals("960x544", PspSpeed.options(PspSettings(resolution = 2))["ppsspp_internal_resolution"])
        val m = SpeedMeter()
        var t = 0L
        repeat(60) { m.frame(t); t += 16 }
        t += 400
        m.frame(t)
        assertEquals(1, m.stalls.size)
        assertTrue(m.stalls.single().second >= 400)
    }

    @Test
    fun pspStepsPickAMagicThenCastIt() {
        val f = PspSteps.frames("pad:6,E>B")
        assertEquals(setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), f.first())
        assertTrue(f.any { it == setOf(android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BUTTON_L1) })
        assertEquals(emptySet<Int>(), f.last())
        val hold = PspSteps.frames("hold:A")
        assertEquals(PspSteps.HOLD, hold.count { it == setOf(android.view.KeyEvent.KEYCODE_BUTTON_Y) })
        assertTrue(PspSteps.isSteps("hold:A"))
        assertFalse(PspSteps.isSteps("A,A,C"))
        // L held alone first, then L with ○.
        val l = android.view.KeyEvent.KEYCODE_BUTTON_L1
        val charge = PspSteps.frames("E>D")
        assertEquals(setOf(l), charge.first())
        assertTrue(charge.indexOfFirst { it == setOf(l, android.view.KeyEvent.KEYCODE_BUTTON_A) } >= PspSteps.TAP)
        assertTrue(PspSteps.isSteps("E>D"))
    }

    @Test
    fun theGameLogShowsTheTimelineStallsAndEvents() {
        var t = 0L
        val log = GameLog { t }
        val m = SpeedMeter()
        // 3 seconds at full speed, 2 slow, then full speed again with a stall.
        repeat(180) { m.frame(t); t += 16 + (it % 3).coerceAtMost(1) }
        repeat(80) { m.frame(t); t += 25 }
        t += 400
        repeat(120) { m.frame(t); t += 16 + (it % 3).coerceAtMost(1) }
        log.event("Paused (pause)")
        val text = log.text(listOf("Phone: test"), m)
        assertTrue(text.startsWith("Phone: test"))
        assertTrue(text, text.contains("full speed"))
        assertTrue(text, text.contains("slow: "))
        assertTrue(text, text.contains("Stalls over 250 ms: 1"))
        assertTrue(text, text.contains("Paused (pause)"))
        assertEquals(listOf("0:00-0:01 full speed", "0:02 slow: 40 fps", "0:03-0:04 full speed"), GameLog.timeline(listOf(60, 60, 40, 60, 59)))
    }

    @Test
    fun anArcadeGamesLogCountsSlowByItsOwnRateAndAddsTheRoomsLog() {
        // A 54 fps board is full speed for an arcade game, slow for a PSP one.
        assertEquals(listOf("0:00-0:01 full speed"), GameLog.timeline(listOf(54, 55), SpeedMeter.ARCADE_SLOW_BELOW))
        assertEquals(listOf("0:00-0:01 slow: 54, 55 fps"), GameLog.timeline(listOf(54, 55)))
        // Faster than the game's own speed (a story scene like fast-forward) shows too.
        assertEquals(listOf("0:00 full speed", "0:01-0:02 fast: 90, 89 fps"), GameLog.timeline(listOf(60, 90, 89)))
        val log = GameLog { 0L }
        val text = log.text(listOf("Game: KOF"), SpeedMeter(SpeedMeter.ARCADE_SLOW_BELOW), listOf("Connection log:" to "12:00:01.000  Joined", "Empty:" to ""))
        assertTrue(text, text.contains("Connection log:\n12:00:01.000  Joined"))
        assertFalse(text, text.contains("Empty:"))
    }

    @Test
    fun opacityGoesFromFaintToSolidAndTheDefaultLooksAsBefore() {
        assertEquals(0.55f + 0.45f * DEFAULT_OPACITY, viewAlpha(DEFAULT_OPACITY), 0.001f)
        assertEquals(0.15f, viewAlpha(0.1f), 0.001f)
        assertEquals(1f, viewAlpha(1f), 0.001f)
        var last = 0f
        for (i in 10..100 step 5) {
            val a = viewAlpha(i / 100f)
            assertTrue("$i%: $a", a > last)
            last = a
        }
    }

    @Test
    fun storyVideosPlayAtTheGamesOwnSpeed() {
        // Without it, a frame ran on to the game's next picture: story videos 1.5x fast.
        assertEquals("enabled", PspSpeed.options(PspSettings())["ppsspp_frame_duplication"])
    }

    @Test
    fun timeAwayFromTheGameIsNeitherSlowNorAStall() {
        val m = SpeedMeter()
        var t = 0L
        repeat(120) { m.frame(t); t += 16 }
        m.hold(true)
        repeat(100) { m.frame(t); t += 16 } // a menu's redraws: not counted
        t += 600_000 // ten minutes in the background
        m.hold(false)
        repeat(120) { m.frame(t); t += 16 }
        assertEquals(0, m.slowSeconds)
        assertTrue(m.stalls.isEmpty())
        assertTrue(m.longestStallMs < 100)
    }

    @Test
    fun mashAlternatesLAndRAsTheGameAsks() {
        // Checked in Ghost of Sparta: L R L R, 4 frames held and 3 apart, escapes the boat's first grab
        // (L ○ R ○ didn't); ○ ○ ○ is a pill of its own.
        val l = PspProfile.keyCode(com.kofarcade.core.Key.E)!!
        val r = PspProfile.keyCode(com.kofarcade.core.Key.F)!!
        val circle = PspProfile.keyCode(com.kofarcade.core.Key.D)!!
        val lr = PspSteps.frames(PspProfile.MASH_LR).filter { it.isNotEmpty() }.map { it.single() }
        val lrPresses = lr.filterIndexed { i, k -> i == 0 || lr[i - 1] != k }
        assertEquals(40, lrPresses.size)
        assertTrue(lrPresses.zipWithNext().all { (a, b) -> a != b })
        assertEquals(setOf(l, r), lrPresses.toSet())
        val o = PspSteps.frames(PspProfile.MASH_O)
        assertEquals(setOf(circle), o.filter { it.isNotEmpty() }.map { it.single() }.toSet())
        assertEquals(36, o.zipWithNext().count { (a, b) -> a.isEmpty() && b.isNotEmpty() } + 1)
        val gow = PspProfile.GOD_OF_WAR.system.map { it.cmd }
        assertTrue(PspProfile.MASH_LR in gow && PspProfile.MASH_O in gow)
    }

    @Test
    fun theDPadIsTheOwnersSwitchOnly() {
        // The game's byte dropped to 0 in play on the phone: no automatic switch.
        assertEquals(null, PspProfile.GOD_OF_WAR.inPlay)
        // A game without one keeps the D-PAD switch.
        assertEquals(null, PspProfile().inPlay)
    }

    @Test
    fun assassinsCreedHasItsOwnControls() {
        val p = PspProfile.forTitle("Assassin's Creed - Bloodlines (USA) (En,Fr,De,Es,It)")
        assertEquals(PspProfile.ASSASSINS_CREED, p)
        // From the game's own button panel: △ Target, □ Attack, ✕ Blend (Dodge in a fight); R runs (blocks in a fight); L the camera.
        assertEquals("Target", p.labels['C'])
        assertEquals("Attack", p.labels['A'])
        assertEquals("Blend/Dodge", p.labels['B'])
        assertEquals("Camera" to "Run / Block", p.shoulderHints)
        // Sprint holds R and ✕ for one thumb; Tackle is R, then ○.
        assertEquals("held:FB", p.system.first { it.name == "Sprint" }.cmd)
        assertEquals("F>D", p.moves.first { it.name == "Tackle" }.cmd)
        // On Home, found by its file name.
        val ac = PspTitle.ALL.first { it.id == "acb" }
        assertEquals(java.io.File("x/Assassin's Creed - Bloodlines (USA) (En,Fr,De,Es,It).iso"), ac.copyIn(listOf(java.io.File("x/Assassin's Creed - Bloodlines (USA) (En,Fr,De,Es,It).iso"))))
    }

    @Test
    fun autoIsPpssppsOwnFrameSkippingAndNothingElse() {
        // Auto: skip up to two pictures, only when behind; the resolution and picture never change.
        val auto = PspSpeed.options(PspSettings(resolution = 2, auto = true))
        assertEquals("enabled", auto["ppsspp_auto_frameskip"])
        assertEquals("2", auto["ppsspp_frameskip"])
        assertEquals("960x544", auto["ppsspp_internal_resolution"])
        // At 1x it doesn't skip (his phone keeps full speed there, and skipping hurt the sound).
        assertEquals("disabled", PspSpeed.options(PspSettings(resolution = 1, auto = true))["ppsspp_frameskip"])
        // Off: the frame skip as picked.
        val off = PspSpeed.options(PspSettings(resolution = 2, auto = false))
        assertEquals("disabled", off["ppsspp_auto_frameskip"])
        assertEquals("disabled", off["ppsspp_frameskip"])
    }

    @Test
    fun compatibilityIsPpssppsSafeDrawing() {
        // A friend's phone (Galaxy A71) stayed black: the switch to try.
        val fast = PspSpeed.options(PspSettings())
        assertEquals("enabled", fast["ppsspp_skip_gpu_readbacks"])
        assertEquals("Up to 1", fast["ppsspp_inflight_frames"])
        val safe = PspSpeed.options(PspSettings(compatible = true))
        assertEquals("disabled", safe["ppsspp_skip_gpu_readbacks"])
        assertEquals("Up to 2", safe["ppsspp_inflight_frames"])
    }

    @Test
    fun godOfWarNeverSkipsBufferEffects() {
        assertEquals(false, PspProfile.GOD_OF_WAR.fasterEffectsOk)
        assertEquals("Up to 1", PspSpeed.options(PspSettings())["ppsspp_inflight_frames"])
    }

}
