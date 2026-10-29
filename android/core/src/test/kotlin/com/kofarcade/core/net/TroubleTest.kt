package com.kofarcade.core.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TroubleTest {
    @Test
    fun wiFiThatKeepsPhonesApartIsExplained() {
        // As Android says it (the owner's report, 2026-10-29).
        val android = ConnectException(
            "failed to connect to /10.229.32.178 (port 7845) from /10.229.32.72 (port 44552) after 5000ms: " +
                "isConnected failed: EHOSTUNREACH (No route to host)",
        )
        assertTrue("hotspot" in explainConnectionTrouble(android)!!)
        assertTrue("hotspot" in explainConnectionTrouble(NoRouteToHostException("No route to host"))!!)
        // Wrapped, as joining wraps what it can't read.
        assertTrue("hotspot" in explainConnectionTrouble(IOException("Couldn't join", android))!!)
    }

    @Test
    fun otherTroublesAreExplainedOrLeftAlone() {
        assertTrue("Nobody is hosting" in explainConnectionTrouble(ConnectException("Connection refused"))!!)
        assertTrue("didn't answer" in explainConnectionTrouble(SocketTimeoutException("connect timed out"))!!)
        assertTrue("isn't on a Wi-Fi" in explainConnectionTrouble(ConnectException("connect failed: ENETUNREACH (Network is unreachable)"))!!)
        assertNull(explainConnectionTrouble(IOException("Bad game state")))
    }
}
