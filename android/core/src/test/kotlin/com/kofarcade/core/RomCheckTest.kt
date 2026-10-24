package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RomCheckTest {
    private fun items(files: List<RomFile>) = files.map { ZipItem(it.name, it.size, it.crc.toLong(16)) }

    private fun set(name: String) = romSet(name)!!

    @Test
    fun everyGameHasItsSets() {
        for (game in GAMES) for (s in game.sets) assertTrue(romSet(s) != null, "no ROM data for $s")
    }

    @Test
    fun recognisesTheBios() {
        val bios = BIOS_SET.files
        assertEquals(RomCheck.Bios, checkRom(items(bios)))
        val partial = checkRom(items(bios.drop(1)))
        assertIs<RomCheck.Incomplete>(partial)
        assertEquals(listOf(bios.first()), partial.missing)
    }

    @Test
    fun recognisesAVersionWhateverTheZipIsCalled() {
        // Files named differently from FBNeo's names still match by CRC.
        val zip = set("sengoku3a").required.mapIndexed { i, f -> ZipItem("file$i.bin", f.size, f.crc.toLong(16)) }
        val check = checkRom(zip)
        assertIs<RomCheck.Game>(check)
        assertEquals("sengoku3a", check.set.name)
        assertEquals("sengoku3", gameForSet(check.set.name)?.id)
    }

    @Test
    fun saysWhatIsMissing() {
        val files = set("sengoku3").required.filterNot { it.name == "261-m1.m1" }
        val check = checkRom(items(files))
        assertIs<RomCheck.Incomplete>(check)
        assertEquals("sengoku3", check.set.base)
        assertTrue(check.missing.any { it.crc == "7d501c39" }, "missing: ${check.missing}")
        assertEquals("261-m1.m1 (sound program)", describe(check.missing.first { it.crc == "7d501c39" }))
    }

    @Test
    fun aSplitCloneUsesItsParentsZip() {
        // sengoku3a shares 9 of its 10 files with sengoku3; a "split" zip holds only the one that differs.
        val parentCrcs = set("sengoku3").files.map { it.crc }.toSet()
        val own = set("sengoku3a").required.filterNot { it.crc in parentCrcs }
        assertEquals(1, own.size)
        assertIs<RomCheck.Incomplete>(checkRom(items(own)))
        val withParent = checkRom(items(own)) { if (it == "sengoku3") items(set("sengoku3").files) else null }
        assertIs<RomCheck.Game>(withParent)
        assertEquals("sengoku3a", withParent.set.name)
        assertTrue(withParent.parentNeeded)
    }

    @Test
    fun theMainVersionWinsOverEqualMatches() {
        val check = checkRom(items(set("kof97").files))
        assertIs<RomCheck.Game>(check)
        assertEquals("kof97", check.set.name)
    }

    @Test
    fun installedSetFindsOtherVersions() {
        val sengoku = GAMES.first { it.id == "sengoku3" }
        assertEquals("sengoku3", installedSet(sengoku, listOf("sengoku3a", "sengoku3")))
        assertEquals("sengoku3a", installedSet(sengoku, listOf("kof97", "sengoku3a")))
        assertEquals(null, installedSet(sengoku, listOf("kof97")))
        val kof2005 = GAMES.first { it.id == "kof2005" }
        assertEquals("kof10thd", installedSet(kof2005, listOf("kof10thd")))
    }

    @Test
    fun unknownZips() {
        assertEquals(RomCheck.Unknown, checkRom(listOf(ZipItem("readme.txt", 10, 0x1234))))
    }

    @Test
    fun fingerprintsIgnoreNamesAndOrder() {
        val a = listOf(ZipItem("a", 1, 0x11), ZipItem("b", 1, 0x22))
        val b = listOf(ZipItem("y", 1, 0x22), ZipItem("x", 1, 0x11))
        assertEquals(romFingerprint(a), romFingerprint(b))
        assertTrue(romFingerprint(a) != romFingerprint(a.take(1)))
    }
}
