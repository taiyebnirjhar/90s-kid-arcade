package com.kofarcade.core

// Fighter faces from the game itself: the games draw each fighter's face
// beside the life bars (Double Dragon: big ones on its select screen), so the
// app crops it from the picture the first time a fighter appears and keeps it.
// No character art ships with the app.
//
// Each game's box and the HUD pixels that must be there (so a super's flash,
// an intro or a menu is never taken for a face) were measured in FBNeo with
// tools/emu.py over fights with different fighters: P1's side; P2's is the
// mirror image.

/** One frame of the game: [width] x [height] pixels, ARGB. */
class Picture(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size >= width * height) { "${pixels.size} pixels for $width x $height" }
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x]
}

/** A pixel of the HUD that is always this colour (0xRRGGBB) while the faces show. */
class Anchor(val x: Int, val y: Int, val rgb: Int)

/** Where a game draws P1's face (P2's is mirrored), on a [width]-pixel-wide picture. */
class PortraitSpec(
    val width: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val anchors: List<Anchor>,
    /** The faces are on the select screen (the fighter under each cursor), not in the fight. */
    val selectScreen: Boolean = false,
) {
    /** The HUD around [side]'s face (0 = P1, 1 = P2) is all there. */
    fun showing(frame: Picture, side: Int): Boolean {
        if (frame.width != width || frame.height < y + h) return false
        return anchors.all { a ->
            val px = if (side == 0) a.x else width - 1 - a.x
            a.y < frame.height && close(frame.at(px, a.y), a.rgb)
        }
    }

    /** [side]'s face, w x h ARGB, turned to face right (P2's is mirrored). */
    fun crop(frame: Picture, side: Int): IntArray {
        val left = if (side == 0) x else width - x - w
        return IntArray(w * h) { i ->
            val cx = i % w
            frame.at(left + if (side == 0) cx else w - 1 - cx, y + i / w)
        }
    }

    private fun close(argb: Int, rgb: Int): Boolean {
        for (shift in intArrayOf(16, 8, 0)) {
            if (Math.abs((argb shr shift and 0xFF) - (rgb shr shift and 0xFF)) > TOLERANCE) return false
        }
        return true
    }

    companion object {
        /** Per channel: the emulator's 16-bit colours come out a little differently by converter. */
        const val TOLERANCE = 24
    }
}

private fun anchors(vararg a: Int) = List(a.size / 3) { Anchor(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) }

/** The face in its framed box beside the life bar. */
private val KOF97_FACES = PortraitSpec(
    304, 1, 9, 30, 28,
    anchors(32, 16, 0xFFFAFF, 0, 38, 0xFFFAFF, 32, 32, 0xFF0000, 13, 38, 0xFFFAFF, 22, 38, 0xFFFAFF),
)

/** 2002 and the hacks on it (Magic Plus, 2005): a bigger face, top corner. */
private val KOF2002_FACES = PortraitSpec(
    304, 0, 4, 32, 40,
    anchors(
        36, 0, 0xFFFAFF, 0, 47, 0xFFFAFF, 37, 38, 0xFFFAFF, 20, 47, 0xFFFAFF,
        37, 17, 0xFFFAFF, 31, 47, 0xFFFAFF, 10, 46, 0xFFFAFF, 37, 8, 0xFFFAFF,
    ),
)

/** Under the life bar, in the name plate. */
private val SF3_FACES = PortraitSpec(
    384, 4, 25, 30, 21,
    anchors(
        6, 17, 0xF6FAF6, 38, 43, 0xD5E2E6, 33, 17, 0xF6FAF6, 43, 28, 0xD5E2EE,
        19, 23, 0xA4AAC5, 43, 17, 0xF6FAF6, 35, 34, 0xD5E2E6, 35, 25, 0xD5E2E6,
    ),
)

/** The point fighter's face at the end of the life bar. */
private val MVSC_FACES = PortraitSpec(
    384, 1, 15, 30, 30,
    anchors(0, 14, 0x00EEFF, 29, 14, 0x00EEFF, 2, 45, 0x00EEFF, 35, 17, 0x0099CD, 32, 31, 0x00AACE, 22, 49, 0xFFFFFF),
)

/** The select screen's big face of the fighter under the cursor (the fight shows none). */
private val DOUBLE_DRAGON_FACES = PortraitSpec(
    320, 36, 16, 62, 62,
    anchors(
        130, 140, 0xEEEEEE, 21, 204, 0xE6CA62, 237, 204, 0xE6CA62, 164, 205, 0xEEEEEE,
        58, 142, 0xE6CA62, 92, 204, 0xE6CA62, 17, 159, 0xE6CA62,
    ),
    selectScreen = true,
)

/** Where a roster's game draws its faces, or null if it has none to take. */
fun portraitSpec(roster: String?): PortraitSpec? = when (roster) {
    "kof97", "kof97plus" -> KOF97_FACES
    "kof2002", "kof2005", "kof2002mp" -> KOF2002_FACES
    "sfiii3" -> SF3_FACES
    "mvsc" -> MVSC_FACES
    "doubledr" -> DOUBLE_DRAGON_FACES
    else -> null
}
