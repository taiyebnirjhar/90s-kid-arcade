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
        /** Each partner's inputs as received, by player. */
        val remotes = link.peers.associate { it.player to ArrayList<Int>() }
        /** The first partner's (two players: the partner's). */
        val remote: ArrayList<Int> get() = remotes.values.first()
        val done = CountDownLatch(1)
        @Volatile var closedReason: String? = null
        @Volatile var running = true
        @Volatile var emulated = 0
        @Volatile var partnerPaused: Boolean? = null
        @Volatile var done2 = CountDownLatch(1)

        // A rematch: back to frame 0 with fresh inputs, as the game screen does.
        fun restart() {
            synchronized(remotes) {
                link.restart()
                local.clear()
                remotes.values.forEach { it.clear() }
                emulated = 0
            }
        }

        override fun onPartnerPaused(player: Int, paused: Boolean) {
            partnerPaused = paused
        }

        override fun onRematch(player: Int) = restart()

        override fun onRemote(player: Int, start: Int, masks: IntArray) {
            synchronized(remotes) {
                val got = remotes.getValue(player)
                assertEquals(got.size, start)
                got += masks.toList()
                val all = remotes.values.minOf { it.size } >= frames
                if (all) done.countDown()
                if (link.epoch > 0 && all) done2.countDown()
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
                    // Rollback emulator: runs ahead of the slowest partner by up to 8 frames.
                    synchronized(remotes) {
                        val known = remotes.values.minOf { it.size }
                        if (emulated < local.size && emulated < known + 8) emulated++
                        var f = link.lockstep.nextLocal
                        val (_, masks) = link.sample(emulated) { (f++ * 13 + link.localPlayer) and 0xFFF }
                        local += masks.toList()
                        link.updateAdvantage(emulated)
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
        synchronized(a.remotes) {
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
        synchronized(a.remotes) { synchronized(b.remotes) {
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
    fun inputsAreSentOnlyFromTheNetworkThread() {
        // Android kills an app that uses the network on its main thread, and
        // the game calls sample()/flush() from there.
        val senders = java.util.Collections.synchronizedSet(HashSet<String>())
        val udp = object : java.net.DatagramSocket(0) {
            override fun send(p: java.net.DatagramPacket) {
                senders += Thread.currentThread().name
                super.send(p)
            }
        }
        val server = java.net.ServerSocket(0)
        val client = java.net.Socket(localhost, server.localPort)
        val accepted = server.accept()
        val link = Link(
            listOf(Line(client, 1, "peer")), udp, listOf(Peer(1, "peer", java.net.InetSocketAddress(localhost, 9))),
            localPlayer = 0, startDelay = 1, session = 1,
        )
        link.start(object : LinkListener {
            override fun onRemote(player: Int, start: Int, masks: IntArray) = Unit
            override fun onDesync(frame: Int) = Unit
            override fun onClosed(reason: String) = Unit
        })
        val caller = Thread.currentThread().name
        repeat(5) { f -> link.sample(f) { 1 } }
        Thread.sleep(200)
        assertTrue(senders.isNotEmpty())
        assertTrue(caller !in senders, "sent on $senders")
        assertEquals(setOf("net-send"), senders.toSet())
        link.close(sendBye = false)
        accepted.close(); server.close()
    }

    @Test
    fun threePhonesPlayTheSameFrames() {
        val host = NetHost("Host", "dino", "Cadillacs and Dinosaurs", rom = 5, bios = 0, maxPlayers = 3, port = 0)
        val joinedNames = ArrayList<String>()
        val accepting = thread { repeat(2) { joinedNames += host.acceptNext { null }!! } }
        val lobby1 = joinLobby(localhost, host.port, "Guest A", "dino", rom = 5, bios = 0)
        val lobby2 = joinLobby(localhost, host.port, "Guest B", "dino", rom = 5, bios = 0)
        accepting.join(5000)
        assertEquals(listOf("Guest A", "Guest B"), joinedNames)
        assertEquals(1, lobby1.player)
        assertEquals(2, lobby2.player)
        assertTrue(host.full)
        // A fourth phone is turned away (the host keeps answering until it starts).
        val stillAccepting = thread { host.acceptNext { null } }
        val e = assertFailsWith<Refused> { joinLobby(localhost, host.port, "Late", "dino", rom = 5, bios = 0) }
        assertTrue("full" in e.message!!, e.message)

        var g1: Link? = null
        var g2: Link? = null
        val w1 = thread { g1 = lobby1.awaitStart() }
        val w2 = thread { g2 = lobby2.awaitStart() }
        val h = host.start()
        w1.join(5000); w2.join(5000)
        stillAccepting.join(5000)
        val a = g1!!
        val b = g2!!
        assertEquals(3, h.players)
        assertEquals(listOf(1, 2), h.peers.map { it.player })
        assertEquals(listOf(0, 2), a.peers.map { it.player })
        assertEquals("Host & Guest B", a.peerName)

        val state = ByteArray(1000) { it.toByte() }
        val loads = listOf(a, b).map { g -> thread { assertTrue(state.contentEquals(g.receiveState())); g.sendReady() } }
        h.sendState(state)
        h.awaitReady()
        loads.forEach { it.join(5000) }

        val games = listOf(FakeGame(h, 120), FakeGame(a, 120), FakeGame(b, 120))
        games.forEach { it.play() }
        assertTrue(games.all { it.done.await(20, TimeUnit.SECONDS) })
        games.forEach { assertEquals(null, it.closedReason) }
        // Every phone got every partner's inputs exactly as that partner sampled them.
        for (g in games) synchronized(g.remotes) {
            for ((player, got) in g.remotes) {
                val sender = games.first { it.link.localPlayer == player }
                synchronized(sender.remotes) { assertEquals(sender.local.take(120), got.take(120)) }
            }
        }

        // A guest's pause reaches the host and, through it, the other guest.
        a.sendPause(true)
        waitFor { games[0].partnerPaused == true && games[2].partnerPaused == true }
        assertTrue(h.partnerPaused)
        assertEquals("Guest A", b.pausedName)
        a.sendPause(false)
        waitFor { games[0].partnerPaused == false && games[2].partnerPaused == false }

        // One guest leaving ends the game for everyone.
        games.forEach { it.running = false }
        b.close()
        waitFor { games[0].closedReason != null && games[1].closedReason != null }
        assertTrue("Guest B" in games[0].closedReason!!, games[0].closedReason)
        h.close(); a.close(); host.close()
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
