package com.kofarcade

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Library.download against a real HTTP server on this machine that drops the
 * connection partway, as a phone's network does: a cut file is picked up
 * (Range) or started over, and never passes as whole.
 */
class LibraryDownloadTest {
    private lateinit var server: java.net.ServerSocket
    private val file = ByteArray(700_000) { (it * 31 + 7).toByte() }
    private val ranges = CopyOnWriteArrayList<String?>()
    private val requests = AtomicInteger()

    /** How the server behaves: cut the first [cuts] answers after [cutAt] bytes; honour Range or not. */
    @Volatile private var cuts = 0
    private var cutAt = 250_000
    @Volatile private var honourRange = false

    // A small HTTP/1.1 server on this machine (one request a connection), enough for the library's GETs.
    @Before
    fun start() {
        server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { answer(socket) }; runCatching { socket.close() } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun answer(socket: java.net.Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val n = requests.incrementAndGet()
        val out = socket.getOutputStream()
        if (headers["authorization"] != "Bearer key") {
            out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val range = headers["range"]
        ranges += range
        val from = if (honourRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val body = file.copyOfRange(from, file.size)
        val status = if (from > 0) "206 Partial Content" else "200 OK"
        out.write("HTTP/1.1 $status\r\nContent-Type: application/zip\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        // The connection drops partway: what's sent stops short of the length promised.
        out.write(body, 0, if (n <= cuts) minOf(cutAt, body.size) else body.size)
        out.flush()
    }

    @After
    fun stop() = server.close()

    private fun library(key: String = "key") = Library("http://127.0.0.1:${server.localPort}", key, retryMs = 10)

    private fun tmp() = File.createTempFile("lib", ".part").apply { deleteOnExit() }

    @Test
    fun aWholeFileComesWhole() {
        val to = tmp()
        var last = 0L
        library().download("kof97.zip", to, file.size.toLong()) { last = it }
        assertArrayEquals(file, to.readBytes())
        assertEquals(file.size.toLong(), last)
    }

    @Test
    fun aDroppedConnectionStartsOverWhenTheServerCantResume() {
        cuts = 2
        val to = tmp()
        library().download("kof97.zip", to, file.size.toLong()) {}
        assertArrayEquals(file, to.readBytes())
        assertEquals(3, requests.get())
    }

    @Test
    fun aDroppedConnectionIsPickedUpWhereItStopped() {
        cuts = 1
        honourRange = true
        val to = tmp()
        library().download("kof97.zip", to, file.size.toLong()) {}
        assertArrayEquals(file, to.readBytes())
        // The second ask was for the rest only.
        assertEquals(listOf(null, "bytes=$cutAt-"), ranges.toList())
    }

    @Test
    fun withoutTheListedSizeTheServersLengthSaysWhatsWhole() {
        cuts = 1
        val to = tmp()
        library().download("kof97.zip", to, size = -1) {}
        assertArrayEquals(file, to.readBytes())
    }

    @Test
    fun aFileThatNeverComesWholeFailsInsteadOfPassing() {
        cuts = 100
        val to = tmp()
        try {
            library().download("kof97.zip", to, file.size.toLong()) {}
            fail("a cut file passed as whole")
        } catch (e: Library.Incomplete) {
            assertTrue(e.message!!, "stopped at" in e.message!!)
        }
    }

    @Test
    fun aWrongKeyIsSaidAtOnce() {
        try {
            library("nope").download("kof97.zip", tmp(), file.size.toLong()) {}
            fail("downloaded with a wrong key")
        } catch (e: Library.WrongKey) {
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun cancellingStopsIt() {
        val to = tmp()
        var stop = false
        try {
            library().download("kof97.zip", to, file.size.toLong(), cancelled = { stop }) { if (it > 100_000) stop = true }
            fail("not cancelled")
        } catch (e: java.io.InterruptedIOException) {
            assertTrue(to.length() < file.size)
        }
    }
}
