package com.kofarcade.core

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortraitsTest {
    /** A frame with [spec]'s HUD drawn on both sides and a distinct colour per pixel elsewhere. */
    private fun hud(spec: PortraitSpec, height: Int = 224): Picture {
        val px = IntArray(spec.width * height) { 0xFF000000.toInt() or (it * 7919 and 0xFFFFFF) }
        for (a in spec.anchors) {
            px[a.y * spec.width + a.x] = 0xFF000000.toInt() or a.rgb
            px[a.y * spec.width + spec.width - 1 - a.x] = 0xFF000000.toInt() or a.rgb
        }
        return Picture(spec.width, height, px)
    }

    @Test
    fun theFightingGamesHaveFaces() {
        for (roster in listOf("kof97", "kof97plus", "kof2002", "kof2005", "kof2002mp", "sfiii3", "mvsc", "doubledr")) {
            assertNotNull(portraitSpec(roster), roster)
        }
        assertNull(portraitSpec(null))
        assertTrue(portraitSpec("doubledr")!!.selectScreen)
    }

    @Test
    fun facesAreTakenOnlyWhileTheHudShows() {
        val spec = portraitSpec("kof97")!!
        val frame = hud(spec)
        assertTrue(spec.showing(frame, 0))
        assertTrue(spec.showing(frame, 1))
        // A super's flash darkens the screen: no face.
        val dark = Picture(frame.width, frame.height, IntArray(frame.pixels.size) { frame.pixels[it] and 0xFF3F3F3F.toInt() })
        assertFalse(spec.showing(dark, 0))
        // Another game's picture size: no face.
        assertFalse(spec.showing(Picture(320, 224, IntArray(320 * 224)), 0))
    }

    @Test
    fun colourConvertersMayDifferALittle() {
        val spec = portraitSpec("mvsc")!!
        val frame = hud(spec)
        val a = spec.anchors.first()
        frame.pixels[a.y * frame.width + a.x] = 0xFF000000.toInt() or (a.rgb xor 0x0F0F0F)
        assertTrue(spec.showing(frame, 0))
    }

    @Test
    fun doubleDragonTellsAFightByItsHud() {
        val hud = fightHud("doubledr")!!
        assertNull(fightHud("kof97"))
        val px = IntArray(320 * 224)
        assertFalse(shows(Picture(320, 224, px), hud, 320))
        for (a in hud) px[a.y * 320 + a.x] = 0xFF000000.toInt() or a.rgb
        assertTrue(shows(Picture(320, 224, px), hud, 320))
        assertFalse(shows(Picture(320, 224, px), hud, 304))
    }

    @Test
    fun p2sFaceIsMirroredToFaceRight() {
        val spec = portraitSpec("sfiii3")!!
        val frame = hud(spec)
        val p1 = spec.crop(frame, 0)
        val p2 = spec.crop(frame, 1)
        assertEquals(spec.w * spec.h, p1.size)
        assertEquals(frame.at(spec.x, spec.y), p1[0])
        assertEquals(frame.at(spec.width - 1 - spec.x, spec.y), p2[0])
        assertEquals(frame.at(spec.width - spec.x - spec.w, spec.y + spec.h - 1), p2[p2.size - 1])
        assertContentEquals(p1, spec.crop(frame, 0))
    }

    /**
     * The real games, where screenshots are at hand ($PORTRAIT_SHOTS: PNGs
     * named <set>_*.png from tools/emu.py; not in the repo): every fight shot
     * shows both faces, and no shot of another game passes as one.
     */
    @Test
    fun realScreenshots() {
        val dir = System.getenv("PORTRAIT_SHOTS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val sets = mapOf(
            "kof97" to "kof97", "kof97pls" to "kof97plus", "kof2002" to "kof2002", "kf2k5uni" to "kof2005",
            "kf2k2mp2" to "kof2002mp", "sfiii3n" to "sfiii3", "mvsc" to "mvsc", "doubledr" to "doubledr",
        )
        var checked = 0
        for (file in dir.listFiles { f -> f.name.endsWith("_f1.png") }!!) {
            val img = ImageIO.read(file)
            val frame = Picture(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
            val own = sets[file.name.substringBefore("_")] ?: continue
            for ((_, roster) in sets) {
                val spec = portraitSpec(roster)!!
                val same = spec === portraitSpec(own)
                for (side in 0..1) assertEquals(same, spec.showing(frame, side), "${file.name} as $roster, side $side")
            }
            checked++
        }
        assertTrue(checked > 0, "no screenshots in $dir")
        // Double Dragon's fights (dd_3-11: several moments of one) show its HUD; its select screen doesn't.
        val dd = fightHud("doubledr")!!
        fun pic(name: String) = ImageIO.read(File(dir, name)).let { Picture(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) }
        for (i in 3..11) File(dir, "dd_$i.png").takeIf { it.exists() }?.let { assertTrue(shows(pic(it.name), dd, 320), it.name) }
        File(dir, "doubledr_f1.png").takeIf { it.exists() }?.let { assertFalse(shows(pic(it.name), dd, 320), it.name) }
    }
}
