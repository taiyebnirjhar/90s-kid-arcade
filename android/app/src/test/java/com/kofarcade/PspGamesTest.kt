package com.kofarcade

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun godOfWarGetsItsOwnButtonsOnTheArcadeLayout() {
        val p = PspProfile.forTitle("God of War - Ghost of Sparta (USA)")
        assertEquals("Light", p.labels['A'])
        assertEquals("Heavy", p.labels['C'])
        assertTrue("Block holds L", p.blockHoldsL)
        assertTrue("SELECT opens its pause menu", p.select)
        assertEquals(6, p.moves.size)
        assertEquals("pad:6,BE", p.moves.first { it.name == "Atlas Spark" }.cmd)
        assertEquals("○", PspProfile.FACE_NAMES['D'])
        assertEquals("EF", p.system.first { it.name == "Roll" }.cmd)
        val base = PspProfile.forTitle("Assassin's Creed Bloodlines")
        assertEquals(PspProfile(), base)
        assertEquals(listOf("L", "R"), base.system.map { it.name })
        // The arcade keys on the PSP's buttons: square, cross, triangle, circle, L, R, SELECT.
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_Y, PspProfile.keyCode(com.kofarcade.core.Key.A))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_B, PspProfile.keyCode(com.kofarcade.core.Key.B))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_R1, PspProfile.keyCode(com.kofarcade.core.Key.F))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, PspProfile.keyCode(com.kofarcade.core.Key.COIN))
        assertEquals(null, PspProfile.keyCode(com.kofarcade.core.Key.UP))
    }

    @Test
    fun theDefaultsSkipOnlyWhenBehindAndCountStalls() {
        val o = PspSpeed.options(PspSettings())
        assertEquals("enabled", o["ppsspp_auto_frameskip"])
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
        val f = PspSteps.frames("pad:6,BE")
        assertEquals(setOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), f.first())
        assertTrue(f.any { it == setOf(android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BUTTON_L1) })
        assertEquals(emptySet<Int>(), f.last())
        val hold = PspSteps.frames("hold:A")
        assertEquals(PspSteps.HOLD, hold.count { it == setOf(android.view.KeyEvent.KEYCODE_BUTTON_Y) })
        assertTrue(PspSteps.isSteps("hold:A"))
        assertFalse(PspSteps.isSteps("A,A,C"))
    }
}
