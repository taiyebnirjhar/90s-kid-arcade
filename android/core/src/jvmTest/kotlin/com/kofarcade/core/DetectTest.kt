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
        for (name in listOf("kof97", "kof97plus", "kof2002", "kof2005", "kof2002mp", "doubledr", "sfiii3", "mvsc")) {
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
    fun kof2005TellsTheOrochiTeamFromTheRegularFighters() {
        val fake = FakeRam()
        // Team: Yashiro, Shermie, Mature; Yashiro on screen.
        fake.set(0x10A804, 0x1B)
        fake.set(0x10A805, 0x1C)
        fake.set(0x10A806, 0x16)
        fake.set(0x108171, 0x1B)
        assertEquals("yashiro", detect(detectSpec("kof2005")!!, fake.ram()).active)
        // Picked from the Orochi box: the fighter block says so.
        fake.set(0x108108, 1)
        val d = detect(detectSpec("kof2005")!!, fake.ram())
        assertEquals("oyashiro", d.active)
        assertEquals(listOf("oyashiro", "shermie", "mature"), d.team)
        // KOF 2002 has no such flag to read.
        assertEquals("yashiro", detect(detectSpec("kof2002")!!, fake.ram()).active)
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
    fun doubleDragonFacesTheOtherFighter() {
        val buf = ByteArray(0x200)
        fun set(addr: Int, v: Int) { buf[(addr - 0x100400) xor 1] = v.toByte() }
        set(0x100445, 0x03) // Jimmy, transformed
        set(0x100545, 0x05) // Abobo
        set(0x100431, 0x90) // P1 X 0x90, P2 X 0x40: P1 is on the right
        set(0x100531, 0x40)
        val d = detect(detectSpec("doubledr")!!, Ram(0x100400, buf))
        assertEquals(listOf("jimmy"), d.team)
        assertEquals("jimmy", d.active)
        assertTrue(d.inRound)
        assertTrue(d.facingLeft)
    }

    @Test
    fun streetFighterThreeReadsThirtyTwoBitWordsAndTheSuperArt() {
        // CPS-3 RAM as FBNeo keeps it: 32-bit words, bytes reversed (xor 3).
        val windows = detectSpec("sfiii3")!!.windows.map { (start, length) -> start to ByteArray(length) }
        fun set(addr: Int, v: Int) {
            val (start, buf) = windows.first { (start, buf) -> addr - start in buf.indices }
            buf[(addr - start) xor 3] = v.toByte()
        }
        set(0x02011387, 1) // Alex
        set(0x0201138B, 2) // Super Art III
        set(0x020154A7, 2) // round on
        set(0x0206902D, 1) // on screen: Alex
        set(0x02068C76, 1) // facing right
        val d = detect(detectSpec("sfiii3")!!, Ram(windows, swap = 3))
        assertEquals(listOf("alex"), d.team)
        assertEquals("alex", d.active)
        assertTrue(d.inRound)
        assertFalse(d.facingLeft)
        assertEquals(3, d.superArt)
        assertEquals(3, Move("Stun Gun Headbutt", "236236+A", "dm", "Super Art III").superArt)
        assertEquals(1, Move("Hyper Bomb", "63214789+A", "dm", "Super Art I; close").superArt)
        assertNull(Move("Flash Chop", "236+A").superArt)
    }

    @Test
    fun marvelVsCapcomFollowsTheTag() {
        val buf = ByteArray(0x1000)
        fun set(addr: Int, v: Int) { buf[(addr - 0xFF3000) xor 1] = v.toByte() }
        set(0xFF3053, 0x06) // Hulk
        set(0xFF3853, 0x0E) // Spider-Man, his partner
        set(0xFF3000, 0) // Hulk tagged out: Spider-Man is on screen
        set(0xFF384B, 1) // facing right
        set(0xFF3400, 1)
        val rest = ByteArray(0x10).also { it[0 xor 1] = 8 } // 0xFF4000: fighting
        val d = detect(detectSpec("mvsc")!!, Ram(listOf(0xFF3000 to buf, 0xFF4000 to rest)))
        assertEquals(listOf("hulk", "spiderman"), d.team)
        assertEquals("spiderman", d.active)
        assertEquals(1, d.activeSlot)
        assertTrue(d.inRound)
        assertFalse(d.facingLeft)
    }

    @Test
    fun onlyFightersWithAMapHaveDetection() {
        assertNull(detectSpec(null))
        assertNull(detectSpec("mslug"))
    }
}
