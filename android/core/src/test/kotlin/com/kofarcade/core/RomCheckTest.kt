package com.kofarcade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun recognisesPenguinBrothersFromAMameZip() {
        // MAME 0.139's penbros.zip: its own file names, FBNeo's CRCs. No BIOS needed.
        val zip = listOf(
            ZipItem("u06.bin", 0x80000, 0x7bbdffac), ZipItem("u07.bin", 0x80000, 0xd50cda5f),
            ZipItem("u18.bin", 0x200000, 0xde4e65e2), ZipItem("u38.bin", 0x400000, 0x4247b39e),
            ZipItem("u39.bin", 0x400000, 0xf9f07faf), ZipItem("u40.bin", 0x400000, 0xdc9e0a96),
        )
        val check = checkRom(zip)
        assertIs<RomCheck.Game>(check)
        assertEquals("penbros", check.set.name)
        val game = assertNotNull(gameForSet(check.set.name))
        assertEquals("penbros", game.id)
        assertFalse(game.neoGeo)
        // Hong Tian Lei shares only the sound chip: it's the same game, its own version.
        val ablast = checkRom(items(set("ablast").files))
        assertIs<RomCheck.Game>(ablast)
        assertEquals("ablast", ablast.set.name)
        assertEquals("penbros", gameForSet("ablast")?.id)
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
    fun hacksWithTheirOwnCardAreNotTheOriginal() {
        // kof97pls is a version of kof97, but '97 Plus has its own card.
        assertEquals("kof97plus", gameForSet("kof97pls")?.id)
        assertEquals("kof2002mp", gameForSet("kf2k2mp2")?.id)
        assertEquals("kof97", gameForSet("kof97h")?.id)
        val kof97 = GAMES.first { it.id == "kof97" }
        assertEquals(null, installedSet(kof97, listOf("kof97pls")))
        assertEquals("kof97h", installedSet(kof97, listOf("kof97h", "kof97pls")))
        assertEquals("kof97pls", installedSet(GAMES.first { it.id == "kof97plus" }, listOf("kof97", "kof97pls")))
    }

    @Test
    fun cadillacsAndDinosaursNeedsNoBios() {
        val check = checkRom(items(set("dino").files))
        assertIs<RomCheck.Game>(check)
        assertEquals("dino", check.set.name)
        val dino = assertNotNull(gameForSet("dino"))
        assertEquals("Cadillacs and Dinosaurs", dino.fullTitle)
        assertFalse(dino.neoGeo)
        assertEquals(listOf('A', 'B'), dino.faceButtons)
        assertEquals("KOF 2002 Magic Plus", GAMES.first { it.id == "kof2002mp" }.fullTitle)
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
