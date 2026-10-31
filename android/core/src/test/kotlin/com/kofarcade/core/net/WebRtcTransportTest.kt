package com.kofarcade.core.net

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The internet transport's own rules, over simulated WebRTC (the room tests run over it too). */
class WebRtcTransportTest {
    private val net = RtcNet(FakeNet { Path(latencyMs = 20, jitterMs = 10) })
    private fun phone() = net.phone() as WebRtcTransport

    private fun pair(): Triple<WebRtcTransport, WebRtcTransport, Pair<Link, Link>> {
        val a = phone()
        val b = phone()
        val listener = b.listen(0)
        var theirs: Link? = null
        val t = thread { theirs = listener.accept() }
        val mine = a.connect(Address(b.self, listener.port), 5_000)
        t.join(5_000)
        return Triple(a, b, mine to theirs!!)
    }

    @Test
    fun linksCarryMessagesInOrderBothWays() {
        val (_, _, links) = pair()
        val (mine, theirs) = links
        for (i in 0 until 50) mine.send(i.toByte(), byteArrayOf(i.toByte()))
        theirs.timeoutMs = 5_000
        for (i in 0 until 50) assertEquals(i.toByte(), theirs.receive().first)
        theirs.send(9, byteArrayOf(1, 2, 3))
        mine.timeoutMs = 5_000
        assertContentEquals(byteArrayOf(1, 2, 3), mine.receive().second)
    }

    @Test
    fun voiceIsNegotiatedAgainWhenEitherPhoneTurnsItOnOrOffAndTheLinksCarryOn() {
        val (a, b, links) = pair()
        fun until(what: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 10_000
            while (!check()) {
                assertTrue(System.currentTimeMillis() < end, "timed out: $what")
                Thread.sleep(20)
            }
        }
        assertTrue(a.voiceLevels().isEmpty() && b.voiceLevels().isEmpty(), "no voice until a mic is on")
        // The answerer's mic on: it asks the offerer for a new offer, and answers with its voice.
        b.voice(true)
        until("b heard on a") { b.self in a.voiceLevels() }
        assertTrue(a.self !in b.voiceLevels())
        // The offerer's too: it offers again itself.
        a.voice(true)
        until("a heard on b") { a.self in b.voiceLevels() }
        b.voice(false)
        until("b quiet") { b.self !in a.voiceLevels() }
        assertTrue(a.self in b.voiceLevels())
        // The game's links never noticed.
        links.first.send(3, byteArrayOf(9))
        links.second.timeoutMs = 5_000
        assertEquals(9, links.second.receive().second[0].toInt())
    }

    @Test
    fun eitherPhoneCanConnectFirst() {
        // phone2 > phone1: phone2 asks phone1 to offer.
        val a = phone()
        val b = phone()
        val listener = a.listen(0)
        thread { listener.accept().send(1, byteArrayOf(42)) }
        val link = b.connect(Address(a.self, listener.port), 5_000)
        link.timeoutMs = 5_000
        assertEquals(42, link.receive().second[0].toInt())
    }

    @Test
    fun aGameStateIsCutIntoPiecesAndPutBackTogether() {
        val (_, _, links) = pair()
        val state = Random(3).nextBytes(700_000)
        links.first.send(7.toByte() to state, 8.toByte() to byteArrayOf(1))
        links.second.timeoutMs = 10_000
        val got = links.second.receive()
        assertEquals(7.toByte(), got.first)
        assertContentEquals(state, got.second)
        assertEquals(8.toByte(), links.second.receive().first)
    }

    @Test
    fun closingEndsTheOtherSideAfterWhatWasSent() {
        val (_, _, links) = pair()
        links.first.send(1, byteArrayOf(5))
        links.first.close()
        links.second.timeoutMs = 5_000
        assertEquals(1.toByte(), links.second.receive().first)
        assertFailsWith<EOFException> { links.second.receive() }
    }

    @Test
    fun manyLinksShareOneConnection() {
        val a = phone()
        val b = phone()
        val listener = b.listen(0)
        val accepted = java.util.concurrent.LinkedBlockingQueue<Link>()
        thread { repeat(3) { accepted.put(listener.accept()) } }
        val links = (1..3).map { a.connect(Address(b.self, listener.port), 5_000) }
        links.forEachIndexed { i, l -> l.send(i.toByte()) }
        val got = (1..3).map { accepted.take().apply { timeoutMs = 5_000 }.receive().first }.sorted()
        assertEquals(listOf<Byte>(0, 1, 2), got)
    }

    @Test
    fun nobodyListeningIsRefused() {
        val a = phone()
        val b = phone()
        b.listen(0).close()
        assertFailsWith<ConnectException> { a.connect(Address(b.self, 1), 5_000) }
    }

    @Test
    fun aPhoneNobodyCanReachTimesOut() {
        val a = phone()
        assertFailsWith<SocketTimeoutException> { a.connect(Address("phone99", 1), 1_000) }
    }

    @Test
    fun datagramsGoToTheirPort() {
        val a = phone()
        val b = phone()
        val inA = a.datagrams()
        val inB = b.datagrams()
        val buf = ByteArray(64)
        // The first ones set the connection up (and may be lost meanwhile, as datagrams can).
        val until = System.currentTimeMillis() + 5_000
        var n = -1
        while (n < 0 && System.currentTimeMillis() < until) {
            inA.send(byteArrayOf(1, 2, 3), Address(b.self, inB.port))
            n = inB.receive(buf, 100)
        }
        assertEquals(3, n)
        assertContentEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        // And back, from the phone that answered.
        n = -1
        while (n < 0 && System.currentTimeMillis() < until + 5_000) {
            inB.send(byteArrayOf(4, 5), Address(a.self, inA.port))
            n = inA.receive(buf, 100)
        }
        assertEquals(2, n)
    }

    @Test
    fun aLinkWaitsOutAShortOutage() {
        val (a, b, links) = pair()
        val path = net.path(a.self, b.self)
        path.down = true
        links.first.send(1, byteArrayOf(9))
        Thread.sleep(1_500)
        path.down = false
        links.second.timeoutMs = 5_000
        assertEquals(9, links.second.receive().second[0].toInt())
    }

    @Test
    fun aPhoneThatMovesNetworkIsFoundAgain() {
        val (a, _, links) = pair()
        // Wi-Fi to 4G: the old way is gone; an ICE restart finds the new one.
        net.move(a.self)
        links.first.send(1, byteArrayOf(4))
        links.second.timeoutMs = 10_000
        assertEquals(4, links.second.receive().second[0].toInt())
    }

    @Test
    fun aPhoneThatStartsOverConnectsAgain() {
        val a = phone()
        val b = phone()
        val listener = a.listen(0)
        thread { while (true) runCatching { listener.accept().send(1, byteArrayOf(1)) }.onFailure { return@thread } }
        b.connect(Address(a.self, listener.port), 5_000).apply { timeoutMs = 5_000 }.receive()
        // b's app restarts: a new transport for the same player.
        b.close()
        val again = net.transport(b.self)
        val link = again.connect(Address(a.self, listener.port), 8_000)
        link.timeoutMs = 5_000
        assertEquals(1, link.receive().second[0].toInt())
        listener.close()
    }

    @Test
    fun aPhoneThatCrashedConnectsAgain() {
        val a = phone()
        val b = phone()
        val listener = a.listen(0)
        thread { while (true) runCatching { listener.accept().send(1, byteArrayOf(1)) }.onFailure { return@thread } }
        b.connect(Address(a.self, listener.port), 5_000).apply { timeoutMs = 5_000 }.receive()
        // b's app is killed: no goodbye. It starts again.
        val again = net.transport(b.self)
        val link = again.connect(Address(a.self, listener.port), 8_000)
        link.timeoutMs = 5_000
        assertEquals(1, link.receive().second[0].toInt())
        listener.close()
    }

    @Test
    fun theOffererCrashedAndConnectsAgainAtOnce() {
        val a = phone()
        val b = phone()
        val listener = b.listen(0)
        thread { while (true) runCatching { listener.accept().send(1, byteArrayOf(1)) }.onFailure { return@thread } }
        a.connect(Address(b.self, listener.port), 5_000).apply { timeoutMs = 5_000 }.receive()
        // a (the offerer) is killed and back within the same second.
        val again = net.transport(a.self)
        val link = again.connect(Address(b.self, listener.port), 8_000)
        link.timeoutMs = 5_000
        assertEquals(1, link.receive().second[0].toInt())
        listener.close()
    }

    @Test
    fun leavingTellsTheOthers() {
        val (a, _, links) = pair()
        a.close()
        links.second.timeoutMs = 5_000
        assertFailsWith<java.io.IOException> { links.second.receive() }
        assertTrue(links.first.isClosed || runCatching { links.first.send(1) }.isFailure)
    }
}
