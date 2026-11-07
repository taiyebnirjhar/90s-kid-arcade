package com.kofarcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The PSP library on Google Drive: reading Drive's pages (made-up ones here), and a live check when asked. */
class DriveFolderTest {
    @Test
    fun aFolderLinkGivesItsId() {
        assertEquals("1AbCdEfGhIjKlMnOp", DriveFolder.folderId("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing"))
        assertNull(DriveFolder.folderId("https://example.com/nothing"))
    }

    @Test
    fun theFolderViewListsItsFiles() {
        val html = """
            <div class="flip-entry"><a href="https://drive.google.com/file/d/1aaaaaaaaaaaaaaaa/view?usp=drive_web" target="_blank"><div class="flip-entry-info"><div class="flip-entry-title">Assassin&#39;s Creed_ Bloodlines.zip.7z</div></div></a></div>
            <div class="flip-entry"><a href="https://drive.google.com/file/d/1bbbbbbbbbbbbbbbb/view?usp=drive_web" target="_blank"><div class="flip-entry-title">notes.txt</div></a></div>
        """.trimIndent()
        val files = DriveFolder.parseFolder(html)
        assertEquals(listOf("1aaaaaaaaaaaaaaaa" to "Assassin's Creed_ Bloodlines.zip.7z", "1bbbbbbbbbbbbbbbb" to "notes.txt"), files)
        assertTrue(DriveFolder.isPspFile(files[0].second))
        assertTrue(!DriveFolder.isPspFile(files[1].second))
    }

    @Test
    fun aBigFilesConfirmPageGivesWhereItIs() {
        val html = """<form id="download-form" action="https://drive.usercontent.google.com/download" method="get"><input type="submit" value="Download anyway"/><input type="hidden" name="id" value="1aaaa"><input type="hidden" name="export" value="download"><input type="hidden" name="confirm" value="t"><input type="hidden" name="uuid" value="u-1"></form>"""
        assertEquals("id=1aaaa&export=download&confirm=t&uuid=u-1", DriveFolder.parseConfirm(html))
        assertNull(DriveFolder.parseConfirm("<html>nothing</html>"))
    }

    @Test
    fun anEntryCarriesItsDriveIdAndName() {
        val name = DriveFolder.entryName("1aaaa", "God of War - Chains of Olympus (USA).7z")
        assertEquals("1aaaa" to "God of War - Chains of Olympus (USA).7z", DriveFolder.parseEntry(name))
        assertNull(DriveFolder.parseEntry("kof97.zip"))
    }

    /** DRIVE_FOLDER=<a public folder link>: lists it and downloads its smallest game whole. */
    @Test
    fun liveFolder() {
        val link = System.getenv("DRIVE_FOLDER")
        assumeTrue(link != null)
        val games = DriveFolder.list(link!!)
        println("games: " + games.map { it.name.substringAfterLast(':') })
        assertTrue(games.isNotEmpty())
        val pick = System.getenv("DRIVE_PICK")?.let { p -> games.first { p in it.name } } ?: games.first()
        val (id, _) = DriveFolder.parseEntry(pick.name)!!
        val to = java.io.File.createTempFile("drive", ".part")
        var last = 0L
        DriveFolder.download(id, to) { last = it }
        println("downloaded ${to.length()} bytes (progress said $last)")
        assertTrue(to.length() > 1_000_000)
        assertEquals(to.length(), last)
        to.delete()
    }
}
