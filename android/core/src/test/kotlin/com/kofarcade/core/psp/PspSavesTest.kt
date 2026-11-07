package com.kofarcade.core.psp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PspSavesTest {
    private val tmp = Files.createTempDirectory("pspsaves").toFile()

    // A tiny ISO 9660 image with UMD_DATA.BIN at its root, as a PSP disc has.
    private fun iso(umd: String): File {
        val f = File(tmp, "game.iso")
        val img = ByteArray(2048 * 20)
        fun le32(at: Int, v: Int) { for (k in 0..3) img[at + k] = (v shr (8 * k)).toByte() }
        val pvd = 16 * 2048
        img[pvd] = 1
        "CD001".toByteArray().copyInto(img, pvd + 1)
        le32(pvd + 156 + 2, 18) // root directory at sector 18
        le32(pvd + 156 + 10, 2048)
        val root = 18 * 2048
        val name = "UMD_DATA.BIN;1".toByteArray()
        img[root] = (33 + name.size + 1).toByte()
        le32(root + 2, 19)
        img[root + 32] = name.size.toByte()
        name.copyInto(img, root + 33)
        umd.toByteArray().copyInto(img, 19 * 2048)
        f.writeBytes(img)
        return f
    }

    @Test
    fun theDiscIdComesFromUmdData() {
        assertEquals("UCUS98737", PspSaves.discId(iso("UCUS-98737|0001G|0001|G")))
        assertNull(PspSaves.discId(iso("not a disc")))
        assertNull(PspSaves.discId(File(tmp, "game.cso").apply { writeBytes(ByteArray(100)) }))
    }

    @Test
    fun aSaveGoesOutAndComesBackWithWhereItWasLeft() {
        val savedata = File(tmp, "a/SAVEDATA")
        File(savedata, "UCUS98737DATA00").apply { mkdirs(); File(this, "DATA.BIN").writeText("progress") }
        File(savedata, "ULUS10566SAVE").apply { mkdirs(); File(this, "x").writeText("another game") }
        val place = File(tmp, "a/god.iso.auto.state").apply { writeText("here") }
        val out = ByteArrayOutputStream()
        val info = PspSaves.export(out, "God of War", "UCUS98737", savedata, place)
        assertEquals(listOf("UCUS98737DATA00"), info.folders)
        assertTrue(info.hasPlace)

        // Another phone: an older save of its own there, kept aside.
        val theirs = File(tmp, "b/SAVEDATA")
        File(theirs, "UCUS98737DATA00").apply { mkdirs(); File(this, "DATA.BIN").writeText("old") }
        val theirPlace = File(tmp, "b/god.iso.auto.state").apply { writeText("old place") }
        val backup = File(tmp, "b/backup")
        val got = PspSaves.import(ByteArrayInputStream(out.toByteArray()), "UCUS98737", theirs, theirPlace, backup)
        assertEquals("God of War", got.title)
        assertEquals("progress", File(theirs, "UCUS98737DATA00/DATA.BIN").readText())
        assertEquals("here", theirPlace.readText())
        assertEquals("old", File(backup, "savedata/UCUS98737DATA00/DATA.BIN").readText())
        assertEquals("old place", File(backup, "god.iso.auto.state").readText())
        assertFalse(File(theirs, "ULUS10566SAVE").exists())
    }

    @Test
    fun anotherGamesSaveOrAStrangeFileIsRefused() {
        val savedata = File(tmp, "c/SAVEDATA")
        File(savedata, "UCUS98737DATA00").apply { mkdirs(); File(this, "DATA.BIN").writeText("p") }
        val out = ByteArrayOutputStream()
        PspSaves.export(out, "God of War", "UCUS98737", savedata, null)
        val e = assertFailsWith<IOException> {
            PspSaves.import(ByteArrayInputStream(out.toByteArray()), "ULUS10566", File(tmp, "d/S"), File(tmp, "d/p"), File(tmp, "d/b"))
        }
        assertTrue("God of War" in e.message!!)
        // A zip that isn't ours, and one reaching outside its folder.
        val evil = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z -> z.putNextEntry(ZipEntry("../../escape.txt")); z.write(1); z.closeEntry() }
        }
        assertFailsWith<IOException> { PspSaves.import(ByteArrayInputStream(evil.toByteArray()), null, File(tmp, "e/S"), File(tmp, "e/p"), File(tmp, "e/b")) }
        assertFalse(File(tmp, "escape.txt").exists())
        // Nothing saved yet: says so.
        assertFailsWith<IOException> { PspSaves.export(ByteArrayOutputStream(), "Game", "X", File(tmp, "empty"), null) }
    }
}
