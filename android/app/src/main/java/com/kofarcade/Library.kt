package com.kofarcade

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Your private ROM library: a Cloudflare Worker (server/worker.js) that serves
 * an R2 bucket to anyone with the key. GET /list lists the files, GET /<name>
 * downloads one; every request sends "Authorization: Bearer <key>".
 */
class Library(url: String, private val key: String) {
    companion object {
        /** Our library. Only the key unlocks it; the app asks for that. */
        const val DEFAULT_URL = "https://arcade-roms.taiyebnirjhor.workers.dev"

        /** Tries per request (the first, then again after 1 s and 2 s). */
        private const val TRIES = 3
        private const val RETRY_MS = 1_000L
    }

    data class Entry(val name: String, val size: Long)

    class WrongKey : IOException("The library didn't accept the key")

    private val base = url.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }

    fun list(): List<Entry> = open("list").use { conn ->
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val files = json.getJSONArray("files")
        List(files.length()) { files.getJSONObject(it) }
            .map { Entry(it.getString("name"), it.optLong("size")) }
            .filter { it.name.endsWith(".zip", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    /** Downloads [name] to [to], reporting the bytes so far to [progress]; stops (throws) once [cancelled]. */
    fun download(name: String, to: File, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit) {
        open(URLEncoder.encode(name, "UTF-8").replace("+", "%20")).use { conn ->
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw java.io.InterruptedIOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
        }
    }

    /**
     * Opens [path]. A hiccup on the library's side (a 5xx: Cloudflare's storage
     * now and then fails a request) or a dropped connection is asked again,
     * a few times, a moment apart, before it's an error.
     */
    private fun open(path: String): Closeable {
        var last: IOException? = null
        for (attempt in 0 until TRIES) {
            if (attempt > 0) Thread.sleep(RETRY_MS * attempt)
            val conn = URL("$base/$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                last = e
                continue
            }
            when {
                code == 200 -> return Closeable(conn)
                code == 401 -> {
                    conn.disconnect()
                    throw WrongKey()
                }
                code >= 500 -> {
                    conn.disconnect()
                    last = BusyLibrary(code)
                }
                else -> {
                    conn.disconnect()
                    throw IOException("The library answered $code")
                }
            }
        }
        throw last ?: IOException("The library didn't answer")
    }

    /** The library's server had trouble (a 5xx), even after asking again. */
    class BusyLibrary(code: Int) : IOException("The library's server had a hiccup ($code). Try again in a moment.")

    /** An open connection, closed with use { }. */
    class Closeable(private val conn: HttpURLConnection) : java.io.Closeable {
        val inputStream get() = conn.inputStream
        override fun close() = conn.disconnect()
    }
}
