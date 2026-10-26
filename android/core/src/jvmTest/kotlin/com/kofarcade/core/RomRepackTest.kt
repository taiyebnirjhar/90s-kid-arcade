package com.kofarcade.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RomRepackTest {
    @Test
    fun programSimmsSplitByteByByteGraphicsInHalves() {
        val program = splitSimm(1, 0, ByteArray(8) { it.toByte() })
        assertEquals(listOf(0, 4), program.getValue(0).map { it.toInt() })
        assertEquals(listOf(3, 7), program.getValue(3).map { it.toInt() })
        val graphics = splitSimm(3, 1, ByteArray(8) { it.toByte() })
        // Lanes: even bytes 0 2 4 6, odd 1 3 5 7; each lane's halves in turn.
        assertEquals(listOf(0, 2), graphics.getValue(4).map { it.toInt() })
        assertEquals(listOf(1, 3), graphics.getValue(5).map { it.toInt() })
        assertEquals(listOf(4, 6), graphics.getValue(6).map { it.toInt() })
        assertEquals(listOf(5, 7), graphics.getValue(7).map { it.toInt() })
    }

    @Test
    fun anOrdinaryZipIsLeftAlone() {
        val zip = File.createTempFile("plain", ".zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write(1); z.closeEntry()
        }
        val out = File.createTempFile("out", ".zip").apply { delete() }
        assertEquals(false, repackCps3(zip, out))
        assertEquals(false, out.exists())
    }

    /** With a real old-style 3rd Strike dump at $SFIII3_OLD (not committed), the result checks out. */
    @Test
    fun anOldThirdStrikeDumpBecomesFbneos() {
        val old = System.getenv("SFIII3_OLD")?.let(::File)?.takeIf { it.isFile } ?: return
        val out = File.createTempFile("sfiii3n", ".zip").apply { delete() }
        assertTrue(repackCps3(old, out))
        val items = java.util.zip.ZipFile(out).use { z ->
            z.entries().asSequence().map { ZipItem(it.name, it.size, it.crc) }.toList()
        }
        val check = checkRom(items)
        assertIs<RomCheck.Game>(check)
        assertEquals("sfiii3n", check.set.name)
        out.delete()
    }
}
