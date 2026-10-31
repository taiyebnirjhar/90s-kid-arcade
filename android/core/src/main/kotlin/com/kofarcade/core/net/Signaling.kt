package com.kofarcade.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What two phones say to each other to set up a peer connection: an offer,
 * its answer, and the candidates, all addressed to one phone of the room.
 * A connection is known by its [gen], which the offerer counts up from the
 * clock's seconds (so it's higher after the offerer starts over): anything
 * for an older one is ignored. A hello's [gen] is the last connection the
 * answerer had.
 */
@Serializable
data class Signal(
    val kind: String,
    val from: String,
    val to: String,
    val gen: Int = 0,
    /** On hellos and answers: the sender's transport (new each time its app starts), so a phone that started over is told apart. */
    val instance: String? = null,
    val sdpType: String? = null,
    val sdp: String? = null,
    val mid: String? = null,
    val index: Int = 0,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The answerer asks the offerer to connect (or connect again). */
        const val HELLO = "hello"
        const val OFFER = "offer"
        const val ANSWER = "answer"
        const val CANDIDATE = "candidate"
        /** The answerer asks for an ICE restart (its network changed). */
        const val RESTART = "restart"
        /** This phone is leaving: drop the connection. */
        const val BYE = "bye"
        /** The answerer asks for a new offer on the same connection (its voice changed). */
        const val AGAIN = "again"

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): Signal? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Carries [Signal]s between a room's phones: the room's Supabase Realtime
 * channel in the app, an in-memory hub in tests. Each phone sees only
 * what's addressed to it.
 */
interface Signaling {
    /** This phone's id in the room. */
    val self: String

    /** Sends [signal] (to signal.to). Throws when it can't be sent now. */
    fun send(signal: Signal)

    /** Where signals for this phone go; called on the signaling's own thread. */
    fun listen(handler: (Signal) -> Unit)

    fun close()
}
