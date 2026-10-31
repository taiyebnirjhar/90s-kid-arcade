package com.kofarcade.core.online

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a player is reported (the server's report reasons). */
enum class ReportReason(val key: String, val label: String) {
    CHAT("chat", "What they wrote"),
    VOICE("voice", "What they said on voice"),
    NAME("name", "Their name"),
    PLAY("play", "How they play"),
    OTHER("other", "Something else"),
}

/**
 * Reporting a player to the owner (report_player, 20261101000000_reports.sql):
 * read in Supabase's dashboard, never shown back in the app.
 */
class Reports(private val supa: Supabase) {
    /** Reports [player] (their player id) for [reason], in [room] (its code), with what they [said]. */
    fun report(player: String, reason: ReportReason, room: String? = null, said: String? = null) {
        supa.rpc("report_player", buildJsonObject {
            put("player", player)
            put("reason", reason.key)
            put("room_code", room)
            put("said", said?.take(2000))
        })
    }
}
