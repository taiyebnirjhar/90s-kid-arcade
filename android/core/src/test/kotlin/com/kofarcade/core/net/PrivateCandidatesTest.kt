package com.kofarcade.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an open room's phone tells strangers: local and relay addresses, never its internet one. */
class PrivateCandidatesTest {
    @Test
    fun aLocalAddressIsSharedSoPhonesOnOneWifiConnectDirectly() {
        val c = "candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0"
        assertEquals(c, PrivateCandidates.candidate(c))
        assertEquals("a=$c", PrivateCandidates.candidate("a=$c"))
        val mdns = "candidate:1 1 udp 2122260223 2f0c9a7e-1b7e-4d0e-9b3c-2a1f.local 50000 typ host generation 0"
        assertEquals(mdns, PrivateCandidates.candidate(mdns))
    }

    @Test
    fun theInternetAddressStaysHome() {
        assertNull(PrivateCandidates.candidate("candidate:2 1 udp 1686052607 203.0.113.9 41000 typ srflx raddr 192.168.1.23 rport 50000 generation 0"))
        assertNull(PrivateCandidates.candidate("candidate:4 1 udp 1 203.0.113.9 41000 typ prflx raddr 0.0.0.0 rport 0"))
        // A public host address: a phone's IPv6 on mobile data, or a public IPv4 straight on the phone.
        assertNull(PrivateCandidates.candidate("candidate:5 1 udp 2122262783 2001:db8::1234 50001 typ host generation 0"))
        assertNull(PrivateCandidates.candidate("candidate:6 1 udp 2122260223 100.72.3.4 50000 typ host generation 0"))
        assertNull(PrivateCandidates.candidate("nonsense"))
    }

    @Test
    fun theRelaysCandidateGoesWithoutTheAddressItCameFrom() {
        assertEquals(
            "candidate:3 1 udp 41885439 198.51.100.7 3478 typ relay raddr 0.0.0.0 rport 0 generation 0",
            PrivateCandidates.candidate("candidate:3 1 udp 41885439 198.51.100.7 3478 typ relay raddr 203.0.113.9 rport 41000 generation 0"),
        )
    }

    @Test
    fun anOfferOrAnswerLosesItsInternetAddressToo() {
        val sdp = listOf(
            "v=0",
            "m=application 41000 UDP/DTLS/SCTP webrtc-datachannel",
            "c=IN IP4 203.0.113.9",
            "a=rtcp:9 IN IP4 203.0.113.9",
            "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0",
            "a=candidate:2 1 udp 1686052607 203.0.113.9 41000 typ srflx raddr 192.168.1.23 rport 50000 generation 0",
            "a=candidate:3 1 udp 41885439 198.51.100.7 3478 typ relay raddr 203.0.113.9 rport 41000 generation 0",
            "a=end-of-candidates",
            "",
        ).joinToString("\r\n")
        val out = PrivateCandidates.sdp(sdp)
        assertFalse("203.0.113.9" in out, out)
        assertTrue("c=IN IP4 0.0.0.0\r\n" in out && "a=rtcp:9 IN IP4 0.0.0.0\r\n" in out, out)
        assertTrue("192.168.1.23 50000 typ host" in out && "typ relay raddr 0.0.0.0 rport 0" in out, out)
        assertTrue(out.endsWith("a=end-of-candidates\r\n"), out)
    }

    @Test
    fun whatCountsAsLocal() {
        for (a in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.43.1", "169.254.1.1", "abc.local", "0.0.0.0")) assertTrue(PrivateCandidates.localOnly(a), a)
        for (a in listOf("172.32.0.1", "8.8.8.8", "100.64.0.1", "2001:db8::1", "fe80::1", "")) assertFalse(PrivateCandidates.localOnly(a), a)
    }
}
