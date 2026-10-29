package com.kofarcade.core.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a failed connection to a room means, in words a player can act on;
 * null if it's nothing we know. Android's own words ("isConnected failed:
 * EHOSTUNREACH (No route to host)") say nothing to a player.
 */
fun explainConnectionTrouble(e: Throwable): String? {
    var t: Throwable? = e
    while (t != null) {
        val m = t.message.orEmpty()
        when {
            // The Wi-Fi keeps its phones apart ("client isolation"), as office,
            // campus, hotel and café Wi-Fi often do.
            t is NoRouteToHostException || "EHOSTUNREACH" in m || "No route to host" in m ->
                return "This Wi-Fi doesn't let phones reach each other (office, campus and public Wi-Fi often don't). " +
                    "Play on a hotspot instead: one phone turns its hotspot on, and the others join it."
            "ENETUNREACH" in m || "Network is unreachable" in m ->
                return "This phone isn't on a Wi-Fi network. Join the host's Wi-Fi or hotspot first."
            t is ConnectException && ("ECONNREFUSED" in m || "refused" in m) ->
                return "Nobody is hosting there any more. Check the key, or ask the host to open the room again."
            t is SocketTimeoutException || "ETIMEDOUT" in m || "timed out" in m ->
                return "The host's phone didn't answer. Check that both phones are on the same Wi-Fi or hotspot, " +
                    "and that the host's screen is on."
            t is UnknownHostException -> return "That address can't be found. Check the key."
        }
        t = t.cause?.takeIf { it !== t }
    }
    return null
}
