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
class Library(url: String, private val key: String, private val retryMs: Long = RETRY_MS) {
    companion object {
        /** Our library. Only the key unlocks it; the app asks for that. */
        const val DEFAULT_URL = "https://arcade-roms.taiyebnirjhor.workers.dev"

        /** Tries per request (the first, then again after 1 s and 2 s). */
        private const val TRIES = 3
        private const val RETRY_MS = 1_000L
        /** Times a download that drops partway is picked up again before it fails. */
        private const val DROPS = 5
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

    /** A download that ended short of the file's size (the connection dropped), even after resuming. */
    class Incomplete(got: Long, size: Long) : IOException("The download stopped at ${got * 100 / size.coerceAtLeast(1)}%: the connection dropped")

    /**
     * Downloads [name] to [to], reporting the bytes so far to [progress]; stops
     * (throws) once [cancelled]. A connection that drops partway (a phone
     * changing networks, the screen off) is picked up where it stopped when the
     * library allows (a Range request), else the file starts over; [size] (the
     * listed size), or else the server's Content-Length, is what a whole file
     * is, and anything short of it throws [Incomplete], never passes as done.
     */
    fun download(name: String, to: File, size: Long = -1, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit) {
        val path = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        var total = 0L
        var expected = size
        var drops = 0
        to.delete()
        while (true) {
            if (cancelled()) throw java.io.InterruptedIOException("Cancelled")
            try {
                open(path, from = total).use { conn ->
                    // Picked up where it stopped (206), or the whole file again (200).
                    if (total > 0 && conn.code != 206) {
                        total = 0
                        to.delete()
                    }
                    if (expected <= 0 && conn.code == 200) expected = conn.length
                    java.io.FileOutputStream(to, total > 0).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
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
            } catch (e: java.io.InterruptedIOException) {
                if (cancelled()) throw e
                // A read that timed out: the connection's gone, picked up below.
                if (++drops > DROPS) throw e
                Thread.sleep(retryMs * drops)
                continue
            } catch (e: IOException) {
                if (e is WrongKey || e is BusyLibrary || ++drops > DROPS) throw e
                Thread.sleep(retryMs * drops)
                continue
            }
            // The stream ended: whole, or cut short (no error said so).
            if (expected <= 0 || total == expected) return
            if (total > expected) throw IOException("The download came out bigger than the file ($total of $expected bytes)")
            if (++drops > DROPS) throw Incomplete(total, expected)
            Thread.sleep(retryMs * drops)
        }
    }

    /**
     * Opens [path]. A hiccup on the library's side (a 5xx: Cloudflare's storage
     * now and then fails a request) or a dropped connection is asked again,
     * a few times, a moment apart, before it's an error.
     */
    private fun open(path: String, from: Long = 0): Closeable {
        var last: IOException? = null
        for (attempt in 0 until TRIES) {
            if (attempt > 0) Thread.sleep(retryMs * attempt)
            val conn = URL("$base/$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                last = e
                continue
            }
            when {
                code == 200 || code == 206 -> return Closeable(conn, code)
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
    class Closeable(private val conn: HttpURLConnection, val code: Int = 200) : java.io.Closeable {
        val inputStream get() = conn.inputStream
        /** The body's length (Content-Length), or -1. */
        val length: Long get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }
}
