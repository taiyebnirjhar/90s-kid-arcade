package com.kofarcade

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * The PSP library: a public Google Drive folder (the owner's, shared by
 * link). Its files are listed from Drive's own folder view and downloaded
 * from drive.usercontent.google.com, answering Drive's "can't scan for
 * viruses, download anyway?" step for big files, and picking a dropped
 * download up where it stopped (Drive serves byte ranges: checked).
 *
 * A file is a [Library.Entry] named [ENTRY_PREFIX] + its Drive id + ":" + its
 * name, so the app's one download queue (background service, resume,
 * notification) carries it like an arcade game.
 */
object DriveFolder {
    const val ENTRY_PREFIX = "psp:"
    private const val DROPS = 5

    /** The folder's id in a link ("…/folders/<id>?usp=…"), or null if it isn't one. */
    fun folderId(link: String): String? =
        Regex("""/folders/([A-Za-z0-9_-]{10,})""").find(link)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(link)?.groupValues?.get(1)

    /** A queue entry's Drive id and file name, or null for an arcade library entry. */
    fun parseEntry(name: String): Pair<String, String>? {
        if (!name.startsWith(ENTRY_PREFIX)) return null
        val rest = name.removePrefix(ENTRY_PREFIX)
        val i = rest.indexOf(':')
        return if (i <= 0) null else rest.substring(0, i) to rest.substring(i + 1)
    }

    fun entryName(id: String, file: String) = "$ENTRY_PREFIX$id:$file"

    /** The folder's files (Drive's folder view: names and ids), PSP games and their archives only. */
    fun list(link: String): List<Library.Entry> {
        val id = folderId(link) ?: throw IOException("That isn't a Google Drive folder link")
        val html = get("https://drive.google.com/embeddedfolderview?id=$id").use { it.inputStream.bufferedReader().readText() }
        return parseFolder(html)
            .filter { (_, name) -> isPspFile(name) }
            .map { (fileId, name) -> Library.Entry(entryName(fileId, name), -1) }
            .sortedBy { it.name.substringAfterLast(':').lowercase() }
    }

    /** A PSP game, or an archive that may hold one. */
    fun isPspFile(name: String) = PspGames.isGame(name) || name.lowercase().let { it.endsWith(".7z") || it.endsWith(".zip") }

    /** (id, name) of each file in Drive's folder view page. */
    fun parseFolder(html: String): List<Pair<String, String>> =
        Regex("""<a href="https://drive\.google\.com/file/d/([A-Za-z0-9_-]+)/[^"]*"[^>]*>.*?<div class="flip-entry-title">([^<]*)</div>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { it.groupValues[1] to unescape(it.groupValues[2]) }
            .toList()

    /** The confirm step's form fields (Drive's page for files too big to scan), as query parameters. */
    fun parseConfirm(html: String): String? {
        if (!html.contains("download-form") && !html.contains("name=\"confirm\"")) return null
        val fields = Regex("""<input type="hidden" name="([a-z]+)" value="([^"]*)"""").findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (fields.none { it.first == "confirm" }) return null
        return fields.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(unescape(v), "UTF-8")}" }
    }

    /**
     * Downloads Drive file [id] to [to], reporting bytes so far; a dropped
     * connection carries on from where it stopped (a Range request), up to
     * [DROPS] times. Throws if it can't, or once [cancelled].
     */
    fun download(id: String, to: File, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}) {
        var url = "https://drive.usercontent.google.com/download?id=$id&export=download"
        // Big files first answer with a page asking to confirm: its form says where the file is.
        get(url).use { first ->
            val type = first.contentType.orEmpty()
            if (type.startsWith("text/html")) {
                val query = parseConfirm(first.inputStream.bufferedReader().readText())
                    ?: throw IOException("Google Drive didn't give the file (is the folder shared with anyone who has the link?)")
                url = "https://drive.usercontent.google.com/download?$query"
            }
        }
        to.delete()
        var drops = 0
        var total = -1L
        while (true) {
            if (cancelled()) throw java.io.InterruptedIOException("Cancelled")
            val have = if (to.exists()) to.length() else 0L
            if (total in 0..have) return
            try {
                val conn = open(url, if (have > 0) have else null)
                conn.use { c ->
                    val code = c.responseCode
                    if (code != 200 && code != 206) throw IOException("Google Drive answered $code")
                    if (c.contentType.orEmpty().startsWith("text/html")) {
                        throw IOException("Google Drive is limiting this file for now (too many downloads today): try again later")
                    }
                    val from = if (code == 206) have else 0L
                    total = from + c.contentLengthLong.coerceAtLeast(0)
                    RandomAccessFile(to, "rw").use { out ->
                        out.setLength(from)
                        out.seek(from)
                        val buf = ByteArray(256 * 1024)
                        var got = from
                        c.inputStream.use { input ->
                            while (true) {
                                if (cancelled()) throw java.io.InterruptedIOException("Cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                progress(got)
                            }
                        }
                    }
                }
                if (to.length() >= total && total > 0) return
                throw IOException("The download stopped short")
            } catch (e: java.io.InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                if (++drops > DROPS) throw e
                Thread.sleep(1_000L * drops)
            }
        }
    }

    private fun get(url: String) = open(url, null)

    private fun open(url: String, from: Long?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (from != null) setRequestProperty("Range", "bytes=$from-")
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun unescape(s: String) = s.replace("&#39;", "'").replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
