package com.kofarcade.core.net

import java.util.concurrent.CompletableFuture

// What WebRtcTransport needs of a WebRTC library, so the transport's logic
// lives here, in plain Kotlin, and runs on any of them: Google's WebRTC on
// Android (the app's adapter), a desktop build in the tests, or a simulated
// one. One peer connection per pair of phones, with two data channels set
// up in advance on both sides (negotiated, fixed ids): RELIABLE (ordered,
// for links) and QUICK (unordered, no resends, for inputs).

/** A STUN or TURN server, as WebRTC takes it. */
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** An offer or answer. */
data class Sdp(val type: String, val sdp: String)

/** A way this phone might be reached (ICE candidate). */
data class Candidate(val mid: String?, val index: Int, val sdp: String)

enum class PeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** Which way a pair of phones ended up connected (ICE's chosen pair), and how quick it is. */
data class PathInfo(
    /** "nearby" (both on one network), "direct" (through the internet, phone to phone) or "relay". */
    val kind: String,
    val rttMs: Int?,
)

interface RtcPeer {
    /** What a peer connection tells: from the library's threads, so none of these may block. */
    interface Events {
        fun onCandidate(candidate: Candidate)
        fun onState(state: PeerState)
        /** Channel [channel] ([RELIABLE] or [QUICK]) is open both ways. */
        fun onOpen(channel: Int)
        fun onMessage(channel: Int, data: ByteArray)
    }

    /** Makes an offer and sets it as local (an ICE restart's, when [restart]). */
    fun offer(restart: Boolean): CompletableFuture<Sdp>

    /** Takes an offer and gives the answer, set as local. */
    fun answer(offer: Sdp): CompletableFuture<Sdp>

    /** Takes the answer to this side's offer. */
    fun accept(answer: Sdp): CompletableFuture<Unit>

    fun addCandidate(candidate: Candidate)

    /** Sends on [channel]; false if it isn't open (or is closing). */
    fun send(channel: Int, data: ByteArray): Boolean

    /** Bytes queued on [channel], not yet sent. */
    fun buffered(channel: Int): Long

    /** Which way it's connected, once it is. */
    fun path(): CompletableFuture<PathInfo?>

    fun close()

    companion object {
        const val RELIABLE = 0
        const val QUICK = 1
    }
}

/** Makes peer connections: one WebRTC library. */
fun interface RtcFactory {
    fun peer(ice: List<IceServer>, events: RtcPeer.Events): RtcPeer

    /**
     * [relayOnly]: connected only through the relay (TURN), so neither phone
     * learns the other's address (an open room's strangers). A library's
     * adapter must override this; the simulated ones have no addresses.
     */
    fun peer(ice: List<IceServer>, events: RtcPeer.Events, relayOnly: Boolean): RtcPeer = peer(ice, events)
}
