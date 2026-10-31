package com.kofarcade

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** The whole UI grows and shrinks with the screen, so it looks the same on every phone and tablet. */
class UiScaleTest {
    @Test
    fun theDesignPhoneIsOneToOne() {
        assertEquals(1f, uiScale(411, 914), 0.001f)
        assertEquals(1f, uiScale(914, 411), 0.001f) // turned sideways: the same
    }

    @Test
    fun smallerPhonesShrinkAndTabletsGrow() {
        assertTrue(uiScale(360, 780) < 0.9f)
        assertTrue(uiScale(800, 1280) > 1.3f)
        assertEquals(0.8f, uiScale(240, 320), 0.001f) // never too small to press
        assertEquals(1.5f, uiScale(2000, 3000), 0.001f) // never giant
    }

    @Test
    fun unknownSizeLeavesItAlone() {
        assertEquals(1f, uiScale(0, 0), 0f)
    }
}
