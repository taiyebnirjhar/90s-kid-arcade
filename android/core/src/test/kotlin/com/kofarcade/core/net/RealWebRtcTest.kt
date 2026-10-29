package com.kofarcade.core.net

import java.io.EOFException
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WebRtcTransport over real WebRTC (Google's library, its desktop build),
 * two phones in one process on this machine's network: offers, answers and
 * candidates through a signaling hub, ICE, DTLS and SCTP for real. The
 * same library runs on the phones (AndroidRtc).
 */
class RealWebRtcTest {
    private val rtc = DesktopRtc.orNull
    private val hub = SignalHub()

    private fun phone(name: String) = WebRtcTransport(hub.join(name), rtc!!, { emptyList() })

    @Test
    fun twoPhonesTalkOverRealWebRtc() {
        if (rtc == null) return
        val a = phone("alice")
        val b = phone("bob")
        try {
            val listener = b.listen(0)
            var theirs: Link? = null
            val t = thread { theirs = listener.accept() }
            val mine = a.connect(Address(b.self, listener.port), 15_000)
            t.join(15_000)
            val other = assertNotNull(theirs)

            // A game state, cut into pieces and put back together.
            val state = Random(1).nextBytes(500_000)
            mine.send(3.toByte() to state, 4.toByte() to byteArrayOf(1))
            other.timeoutMs = 15_000
            val got = other.receive()
            assertEquals(3.toByte(), got.first)
            assertContentEquals(state, got.second)
            assertEquals(4.toByte(), other.receive().first)

            // Inputs, on the quick channel.
            val inA = a.datagrams()
            val inB = b.datagrams()
            val buf = ByteArray(64)
            var n = -1
            val until = System.currentTimeMillis() + 5_000
            while (n < 0 && System.currentTimeMillis() < until) {
                inB.send(byteArrayOf(7, 7), Address(a.self, inA.port))
                n = inA.receive(buf, 100)
            }
            assertEquals(2, n)

            // Which way: both on this machine.
            val path = a.paths()[b.self]
            println("path: $path")

            mine.close()
            assertTrue(runCatching { other.receive() }.exceptionOrNull() is EOFException)
        } finally {
            a.close()
            b.close()
        }
    }
}
