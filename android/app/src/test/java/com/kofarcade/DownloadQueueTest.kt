package com.kofarcade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The library's download queue, without a phone: files come one after
 * another (the BIOS first), failures are tried again, a file only counts as
 * on the phone once it came whole and passed the check, Stop stops it all,
 * and nothing depends on a screen watching.
 */
class DownloadQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetched = CopyOnWriteArrayList<String>()
    private val installedFiles = CopyOnWriteArrayList<String>()
    private val failuresLeft = ConcurrentHashMap<String, Int>()
    /** Files whose zip isn't a whole game (the check says so). */
    private val broken = mutableSetOf<String>()
    /** Held until released: a download in progress, to stop or add to. */
    @Volatile private var hold: CountDownLatch? = null

    @After
    fun stop() = scope.cancel()

    private fun queue() = DownloadQueue(
        scope,
        fetch = { entry, to, cancelled, progress ->
            fetched += entry.name
            hold?.let { latch ->
                progress(entry.size / 2)
                while (!latch.await(10, TimeUnit.MILLISECONDS)) {
                    if (cancelled()) throw java.io.InterruptedIOException("Cancelled")
                }
            }
            val left = failuresLeft[entry.name] ?: 0
            if (left > 0) {
                failuresLeft[entry.name] = left - 1
                throw Library.Incomplete(entry.size / 3, entry.size)
            }
            to.writeBytes(ByteArray(entry.size.toInt()))
            progress(entry.size)
        },
        scratch = { File.createTempFile("queue", ".part").apply { deleteOnExit() } },
        install = { entry, file ->
            assertEquals("installed a file that isn't whole", entry.size, file.length())
            if (entry.name in broken) {
                "Missing 2 files" to false
            } else {
                installedFiles += entry.name
                "Added ${entry.name}" to true
            }
        },
        retryMs = 5,
    )

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < end)
            Thread.sleep(5)
        }
    }

    private val kof = Library.Entry("kof97.zip", 1000)
    private val ms = Library.Entry("mslug.zip", 2000)
    private val bios = Library.Entry("neogeo.zip", 500)

    @Test
    fun everyFileComesInTurnTheBiosFirst() {
        val q = queue()
        q.enqueue(listOf(kof, ms, bios))
        assertTrue(q.active.value)
        until("all done") { !q.active.value }
        assertEquals(listOf("neogeo.zip", "kof97.zip", "mslug.zip"), fetched.toList())
        assertEquals(3, q.installed.value)
        for (e in listOf(kof, ms, bios)) {
            val d = q.state.value.getValue(e.name)
            assertTrue("${e.name}: $d", d.ok && !d.retry && !d.queued && d.result != null)
        }
    }

    @Test
    fun aDroppedDownloadIsTriedAgain() {
        failuresLeft[kof.name] = 2
        val q = queue()
        q.enqueue(listOf(kof))
        until("done") { !q.active.value }
        assertEquals(3, fetched.size)
        assertTrue(q.state.value.getValue(kof.name).ok)
    }

    @Test
    fun aFileThatNeverComesWholeIsNeverOnThePhone() {
        failuresLeft[kof.name] = 99
        val q = queue()
        q.enqueue(listOf(kof, ms))
        until("done") { !q.active.value }
        val d = q.state.value.getValue(kof.name)
        assertTrue(d.retry && !d.ok)
        assertTrue(d.result!!, "stopped at" in d.result!!)
        assertFalse(kof.name in installedFiles)
        // The one after it still came.
        assertTrue(q.state.value.getValue(ms.name).ok)
        assertEquals(1, q.installed.value)
    }

    @Test
    fun aBrokenZipSaysSoAndIsntCountedAsOnThePhone() {
        broken += kof.name
        val q = queue()
        q.enqueue(listOf(kof))
        until("done") { !q.active.value }
        val d = q.state.value.getValue(kof.name)
        assertFalse(d.ok)
        assertFalse(d.retry)
        assertEquals(0, q.installed.value)
    }

    @Test
    fun filesAddedWhileOthersDownloadWaitTheirTurnAndComeOnce() {
        hold = CountDownLatch(1)
        val q = queue()
        q.enqueue(listOf(kof))
        until("downloading") { q.state.value[kof.name]?.done == 500L }
        q.enqueue(listOf(ms, kof))
        assertTrue(q.state.value.getValue(ms.name).queued)
        assertEquals(2, q.left)
        hold!!.countDown()
        until("done") { !q.active.value }
        assertEquals(listOf("kof97.zip", "mslug.zip"), fetched.toList())
    }

    @Test
    fun stopStopsEverythingAndItCanStartAgain() {
        hold = CountDownLatch(1)
        val q = queue()
        q.enqueue(listOf(kof, ms))
        until("downloading") { q.state.value[kof.name]?.done == 500L }
        q.cancelAll()
        until("stopped") { !q.active.value }
        for (e in listOf(kof, ms)) {
            val d = q.state.value.getValue(e.name)
            assertTrue("${e.name}: $d", d.retry && !d.ok)
        }
        assertTrue(installedFiles.isEmpty())
        // Started again: it comes.
        hold = null
        q.enqueue(listOf(kof))
        until("done again") { q.state.value[kof.name]?.ok == true }
        assertFalse(q.active.value)
    }

    @Test
    fun aWrongKeyIsntTriedAgainAndAgain() {
        val q = DownloadQueue(
            scope,
            fetch = { e, _, _, _ ->
                fetched += e.name
                throw Library.WrongKey()
            },
            scratch = { File.createTempFile("queue", ".part").apply { deleteOnExit() } },
            install = { _, _ -> throw IOException("not reached") },
            retryMs = 5,
        )
        q.enqueue(listOf(kof))
        until("done") { !q.active.value }
        assertEquals(1, fetched.size)
        assertTrue(q.state.value.getValue(kof.name).retry)
    }

    @Test
    fun aPhoneThatsFullFailsThatFileNotTheQueue() {
        var calls = 0
        val q = DownloadQueue(
            scope,
            fetch = { e, to, _, _ -> to.writeBytes(ByteArray(e.size.toInt())) },
            scratch = {
                if (calls++ < 3) throw IOException("No space left on device")
                File.createTempFile("queue", ".part").apply { deleteOnExit() }
            },
            install = { e, _ -> "Added ${e.name}" to true },
            retryMs = 5,
        )
        q.enqueue(listOf(kof, ms))
        until("done") { !q.active.value }
        assertTrue(q.state.value.getValue(kof.name).retry)
        assertTrue(q.state.value.getValue(ms.name).ok)
    }
}
