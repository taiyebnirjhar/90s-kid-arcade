package com.kofarcade.core.online

import com.kofarcade.core.net.IceServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The ICE servers for a room's connections: the relay's (Cloudflare TURN,
 * from the turn-credentials function, an hour each) plus public STUN.
 *
 * When the relay is paused (D8: this month's use passed 900 GB) or can't be
 * asked, connections go on with STUN alone: nearby and direct ones still
 * work, and [note] says why the relay is missing.
 */
class RelayServers(
    private val supa: Supabase,
    private val room: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var servers: List<IceServer>? = null
    private var until = 0L

    /** Why there's no relay right now (null: there is, or it wasn't asked yet). */
    @Volatile var note: String? = null
        private set

    @Synchronized
    fun get(): List<IceServer> {
        servers?.takeIf { now() < until }?.let { return it }
        return try {
            val o = supa.function("turn-credentials", buildJsonObject { put("room", room) }).jsonObject
            val got = o["iceServers"]!!.jsonArray.map { e ->
                val s = e.jsonObject
                val urls = s["urls"]!!.let { u -> runCatching { u.jsonArray.map { it.jsonPrimitive.content } }.getOrElse { listOf(u.jsonPrimitive.content) } }
                IceServer(urls, s["username"]?.jsonPrimitive?.contentOrNull, s["credential"]?.jsonPrimitive?.contentOrNull)
            }
            val ttl = o["ttl"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
            // Asked again 5 minutes before they run out.
            until = now() + (ttl - 300).coerceAtLeast(60) * 1000
            servers = got + STUN
            note = null
            servers!!
        } catch (e: OnlineError) {
            note = when (e) {
                is OnlineError.Paused -> e.message
                else -> explainOnline(e)
            }
            // Asked again in a minute; meanwhile, direct connections only.
            until = now() + 60_000
            servers = STUN
            STUN
        }
    }

    companion object {
        /** Public STUN, free: finds a phone's internet address for a direct connection. */
        val STUN = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")))
    }
}
