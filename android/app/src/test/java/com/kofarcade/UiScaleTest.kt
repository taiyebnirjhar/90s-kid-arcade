package com.kofarcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole UI grows and shrinks with the screen, so it looks the same on every phone and tablet. */
class UiScaleTest {
    // The design's units across the short side, and along the long one, for a screen.
    private fun design(w: Int, h: Int): Pair<Float, Float> = uiScale(w, h).let { minOf(w, h) / it to maxOf(w, h) / it }

    @Test
    fun aPhoneTurnedSidewaysKeepsItsScale() {
        val k = 411 / DESIGN_SHORT_DP
        assertEquals(k, uiScale(411, 914), 0.001f)
        assertEquals(k, uiScale(914, 411), 0.001f) // turned sideways: the same
    }

    @Test
    fun everyPhoneHasTheDesignPhonesWidth() {
        // Small, common and big phones, upright: text and buttons the same share of the width.
        for ((w, h) in listOf(360 to 780, 384 to 854, 393 to 873, 412 to 915, 430 to 932)) {
            assertEquals("$w x $h", DESIGN_SHORT_DP, design(w, h).first, 0.5f)
        }
    }

    @Test
    fun tabletsAreThePhoneGrownWithRoomToSpare() {
        // A 10" tablet either way round: the short side within 18% of a phone's (a 4:3 one is squarer), the long side never under 640.
        for ((w, h) in listOf(800 to 1280, 1280 to 800, 744 to 1133, 1024 to 1366)) {
            val (short, long) = design(w, h)
            assertTrue("$w x $h: $short", short in DESIGN_SHORT_DP - 1..DESIGN_SHORT_DP * 1.18f)
            assertTrue("$w x $h: $long", long >= 639.9f)
        }
        // So a tablet sideways is the md breakpoint, a phone sideways lg.
        assertTrue(design(1280, 800).second.let { it >= Breakpoints.md.value && it < Breakpoints.lg.value })
        assertTrue(design(914, 411).second >= Breakpoints.lg.value)
        assertTrue(design(411, 914).first.let { it >= Breakpoints.sm.value && it < Breakpoints.md.value })
    }

    @Test
    fun neverTooSmallToPress() {
        assertEquals(0.8f, uiScale(240, 320), 0.001f)
    }

    @Test
    fun unknownSizeLeavesItAlone() {
        assertEquals(1f, uiScale(0, 0), 0f)
    }
}
