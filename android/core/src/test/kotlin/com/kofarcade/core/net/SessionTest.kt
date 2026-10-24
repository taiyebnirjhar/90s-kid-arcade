package com.kofarcade.core.net

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionTest {
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < until, "timed out")
            Thread.sleep(10)
        }
    }

    private val localhost = InetAddress.getLoopbackAddress()

    /** Plays [frames] frames on a link like the game screen would, with a fake emulator. */
    private class FakeGame(val link: Link, val frames: Int) : LinkListener {
        val local = ArrayList<Int>()
        val remote = ArrayList<Int>()
        val done = CountDownLatch(1)
        @Volatile var closedReason: String? = null
        @Volatile var running = true
        @Volatile var emulated = 0
        @Volatile var partnerPaused: Boolean? = null
        @Volatile var done2 = CountDownLatch(1)

        // A rematch: back to frame 0 with fresh inputs, as the game screen does.
        fun restart() {
            synchronized(remote) {
                link.restart()
                local.clear()
                remote.clear()
                emulated = 0
            }
        }

        override fun onPartnerPaused(paused: Boolean) {
            partnerPaused = paused
        }

        override fun onRematch() = restart()

        override fun onRemote(start: Int, masks: IntArray) {
            synchronized(remote) {
                assertEquals(remote.size, start)
                remote += masks.toList()
                if (remote.size >= frames) done.countDown()
                if (link.epoch > 0 && remote.size >= frames) done2.countDown()
            }
        }

        override fun onDesync(frame: Int) = Unit

        override fun onClosed(reason: String) {
            closedReason = reason
            done.countDown()
        }

        fun play() {
            link.start(this)
            thread(isDaemon = true) {
                // Keeps playing (and sending) until told to stop, like a real phone.
                while (running) {
                    // Rollback emulator: runs ahead of the partner by up to 8 frames.
                    synchronized(remote) {
                        val known = remote.size
                        if (emulated < local.size && emulated < known + 8) emulated++
                        var f = link.lockstep.nextLocal
                        val (_, masks) = link.sample(emulated) { (f++ * 13 + link.localPlayer) and 0xFFF }
                        local += masks.toList()
                        link.advantage = emulated - known
                    }
                    Thread.sleep(16)
                }
            }
        }
    }

    @Test
    fun hostAndGuestPlayTheSameFrames() {
        val host = NetHost("Host", "kof97", "KOF '97", rom = 1, bios = 2, delay = 2, port = 0)
        var hostLink: Link? = null
        val t = thread { hostLink = host.accept { null } }
        val guestLink = join(localhost, host.port, "Guest", "kof97", rom = 1, bios = 2)
        t.join(5000)
        val h = hostLink!!
        assertEquals("Guest", h.peerName)
        assertEquals("Host", guestLink.peerName)
        assertEquals(2, guestLink.delay)
        assertEquals(1, Welcome(true).delay)

        // The start state goes host -> guest, then both are ready.
        val state = ByteArray(400_000) { (it % 251).toByte() }
        val got = thread { assertTrue(state.contentEquals(guestLink.receiveState())); guestLink.sendReady() }
        h.sendState(state)
        h.awaitReady()
        got.join(5000)

        val a = FakeGame(h, 120)
        val b = FakeGame(guestLink, 120)
        a.play()
        b.play()
        assertTrue(a.done.await(20, TimeUnit.SECONDS) && b.done.await(20, TimeUnit.SECONDS))
        assertEquals(null, a.closedReason)
        synchronized(a.remote) {
        // Each side got exactly what the other sampled, frame by frame.
        assertEquals(b.local.take(120), a.remote.take(120))
        assertEquals(a.local.take(120), b.remote.take(120))
        a.remote.take(120).forEachIndexed { frame, m -> assertEquals((frame * 13 + 1) and 0xFFF, m) }
        assertTrue(a.link.partnerAdvantage in -10..10)
        }

        // Pausing reaches the partner.
        h.sendPause(true)
        waitFor { b.partnerPaused == true }
        h.sendPause(false)
        waitFor { b.partnerPaused == false }

        // A rematch: both restart from frame 0 and play the same inputs again.
        guestLink.sendRematch()
        b.restart()
        assertTrue(a.done2.await(20, TimeUnit.SECONDS) && b.done2.await(20, TimeUnit.SECONDS))
        assertEquals(1, h.epoch)
        assertEquals(1, guestLink.epoch)
        synchronized(a.remote) { synchronized(b.local) {
            assertEquals(b.local.take(120), a.remote.take(120))
            a.remote.take(120).forEachIndexed { frame, m -> assertEquals((frame * 13 + 1) and 0xFFF, m) }
        } }
        a.running = false
        b.running = false

        // Leaving tells the other side.
        val left = CountDownLatch(1)
        var reason = ""
        guestLink.close()
        thread { while (a.closedReason == null) Thread.sleep(20); reason = a.closedReason!!; left.countDown() }
        assertTrue(left.await(5, TimeUnit.SECONDS))
        assertTrue("left" in reason || "disconnected" in reason, reason)
        h.close(); host.close()
    }

    @Test
    fun aDifferentRomIsRefused() {
        val host = NetHost("Host", "kof97", "KOF '97", rom = 1, bios = 2, port = 0)
        thread(isDaemon = true) { runCatching { host.accept { null } } }
        val e = assertFailsWith<Refused> { join(localhost, host.port, "Guest", "kof97", rom = 99, bios = 2) }
        assertTrue("differs" in e.message!!)
        host.close()
    }

    @Test
    fun theHostCanSayNo() {
        val host = NetHost("Host", "kof97", "KOF '97", rom = 1, bios = 2, port = 0)
        thread(isDaemon = true) { runCatching { host.accept { "Not now" } } }
        val e = assertFailsWith<Refused> { join(localhost, host.port, "Guest", "kof97", rom = 1, bios = 2) }
        assertEquals("Not now", e.message)
        host.close()
    }

    @Test
    fun aProbeLearnsTheHostsGame() {
        val host = NetHost("Host", "kf2k5uni", "KOF 2005", rom = 7, bios = 2, port = 0)
        thread(isDaemon = true) { runCatching { host.accept { null } } }
        val b = probe(localhost, host.port)
        assertEquals("kf2k5uni", b.set)
        assertEquals("KOF 2005", b.title)
        assertEquals(7L, b.rom)
        assertEquals("Host", b.name)
        // The host is still waiting for a real join.
        val link = join(localhost, host.port, "Guest", "kf2k5uni", rom = 7, bios = 2)
        assertEquals(1, link.localPlayer)
        link.close(sendBye = false); host.close()
    }

    @Test
    fun beaconsAreHeard() {
        val heard = CountDownLatch(1)
        var got: Beacon? = null
        BeaconListener(port = 0) { b, _ -> got = b; heard.countDown() }.use { listener ->
            // Send straight to the listener's port on this machine.
            val field = BeaconListener::class.java.getDeclaredField("socket").apply { isAccessible = true }
            val port = (field.get(listener) as java.net.DatagramSocket).localPort
            val beacon = Beacon(PROTOCOL, "Host", "kof97", "KOF '97", 1, 7845)
            val bytes = encode(beacon)
            java.net.DatagramSocket().use { it.send(java.net.DatagramPacket(bytes, bytes.size, localhost, port)) }
            assertTrue(heard.await(5, TimeUnit.SECONDS))
            assertEquals(beacon, got)
        }
    }
}
