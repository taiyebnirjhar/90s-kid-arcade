package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectTest {
    /** A RAM window filled in through 68000 addresses, stored the way FBNeo does. */
    private class FakeRam {
        val buf = ByteArray(Ram.LENGTH)
        fun set(addr: Int, value: Int) {
            buf[(addr - Ram.START) xor 1] = value.toByte()
        }
        fun ram() = Ram(Ram.START, buf)
    }

    @Test
    fun bytesAreSwappedWithinWords() {
        val fake = FakeRam()
        fake.buf[0x171 - 1] = 0x1B // stored little endian: 0x108171 lives at index 0x170
        val ram = fake.ram()
        assertEquals(0x1B, ram.byte(0x108171))
        assertEquals(0x001B, ram.word(0x108170))
    }

    @Test
    fun everyDetectedIdIsInItsRoster() {
        for (name in listOf("kof97", "kof97plus", "kof2002", "kof2005")) {
            val roster = loadRoster(name)!!
            val spec = detectSpec(name)!!
            val ids = spec.ids.filterNotNull() + spec.alternates.values
            for (id in ids) assertTrue(roster.character(id) != null, "$name has no $id")
        }
    }

    @Test
    fun kof97TeamActiveAndFacing() {
        val fake = FakeRam()
        // Team: Iori, Kyo ('94 version), Terry.
        fake.set(0x10A84B, 0x1B)
        fake.set(0x10A84C, 0x00)
        fake.set(0x10A84D, 0x03)
        fake.set(0x10A857, 0b010)
        // In a round, Terry fighting, facing left, standing right of P2.
        fake.set(0x10A840, 1)
        fake.set(0x108171, 0x03)
        fake.set(0x108131, 0)
        fake.set(0x108118, 0x01)
        fake.set(0x108119, 0x40)
        fake.set(0x108319, 0x80)
        val d = detect(detectSpec("kof97")!!, fake.ram())
        assertEquals(listOf("iori", "kyo94", "terry"), d.team)
        assertTrue(d.inRound)
        assertEquals("terry", d.active)
        assertEquals(2, d.activeSlot)
        assertTrue(d.facingLeft)
        assertEquals(0x140, d.x)
        assertEquals(0x80, d.otherX)
    }

    @Test
    fun kof2002MasksRoulettePicksAndReadsPlayerTwo() {
        val fake = FakeRam()
        val p2 = 0x26
        fake.set(0x10A804 + p2, 0x80 or 0x24) // Kula, picked by roulette
        fake.set(0x10A805 + p2, 0x1E) // K'
        fake.set(0x10A806 + p2, 0x31) // not in KOF 2002
        fake.set(0x108371, 0x1E)
        fake.set(0x108331, 1)
        val d = detect(detectSpec("kof2002")!!, fake.ram(), player = 1)
        assertEquals(listOf("kula", "kdash", null), d.team)
        assertFalse(d.teamKnown)
        assertEquals("kdash", d.active)
        assertFalse(d.facingLeft)
        assertFalse(d.inRound)
    }

    @Test
    fun aFighterOutsideTheTeamIsNotTrusted() {
        val fake = FakeRam()
        fake.set(0x10A804, 0x00)
        fake.set(0x10A805, 0x01)
        fake.set(0x10A806, 0x02)
        fake.set(0x108171, 0x15)
        val d = detect(detectSpec("kof2002")!!, fake.ram())
        assertNull(d.active)
        assertNull(d.activeSlot)
        assertEquals(0x15, d.activeRaw)
    }

    @Test
    fun onlyKofHasDetection() {
        assertNull(detectSpec(null))
        assertNull(detectSpec("mslug"))
    }
}
