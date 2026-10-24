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
    private val localhost = InetAddress.getLoopbackAddress()

    /** Plays [frames] frames on a link like the game screen would, with a fake emulator. */
    private class FakeGame(val link: Link, val frames: Int) : LinkListener {
        val ran = ArrayList<FrameInputs>()
        val done = CountDownLatch(1)
        @Volatile var closedReason: String? = null

        override fun onFrames(frames: List<FrameInputs>) {
            synchronized(ran) { ran += frames }
            if (ran.size >= this.frames) done.countDown()
        }

        override fun onDesync(frame: Int) = Unit

        override fun onClosed(reason: String) {
            closedReason = reason
            done.countDown()
        }

        fun play() {
            link.start(this)
            thread(isDaemon = true) {
                while (done.count > 0) {
                    val emulated = synchronized(ran) { ran.size }
                    var f = link.lockstep.nextLocal
                    link.sample(emulated) { (f++ * 13 + link.localPlayer) and 0xFFF }
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
        assertEquals(a.ran.take(120), b.ran.take(120))
        a.ran.take(120).forEach {
            assertEquals((it.frame * 13) and 0xFFF, it.p1)
            assertEquals((it.frame * 13 + 1) and 0xFFF, it.p2)
        }

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
