package com.kofarcade.core.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A friend, or a request one way or the other. */
data class Friend(
    /** Their player id. */
    val id: String,
    val name: String,
    val icon: Int,
    val status: Status,
    val online: Boolean = false,
    /** The game they're playing (its set), or null on the menus. */
    val playing: String? = null,
    /** Their internet room's code while they're in one (friends join any of their rooms). */
    val room: String? = null,
    /** Their room has space for another phone. */
    val roomSpace: Boolean = false,
) {
    enum class Status { FRIEND, INCOMING, OUTGOING }
}

/** An invite to a friend's room. */
data class Invite(val id: Long, val from: String, val name: String, val icon: Int, val room: String, val set: String, val atMs: Long)

/** A player this phone blocked. */
data class Blocked(val id: String, val name: String, val icon: Int)

/** What asking someone to be friends did. */
enum class Asked { ASKED, FRIENDS }

/** The server's side of friends (20261101000100_friends.sql), or a fake in tests. */
interface FriendServer {
    /** Asks the player with [code] (their friend code) to be friends. */
    fun request(code: String): Asked
    /** Asks the player [id] (met in a room) to be friends. */
    fun requestById(id: String): Asked
    fun answer(id: String, accept: Boolean)
    fun remove(id: String)
    fun block(id: String)
    fun unblock(id: String)
    fun blocked(): List<Blocked>
    /** Friends and requests: incoming requests first, then friends online, then the rest. */
    fun list(): List<Friend>
    /** What this phone is doing now: the game ([set], null on the menus) and its internet [room]. */
    fun presence(set: String?, room: String?)
    fun offline()
    fun invite(id: String, room: String)
    fun invites(): List<Invite>
    fun dismiss(invite: Long)
}

/** [FriendServer] on Supabase. */
class SupabaseFriends(private val supa: Supabase) : FriendServer {
    private fun asked(r: kotlinx.serialization.json.JsonElement) =
        if (r.jsonPrimitive.contentOrNull == "friends") Asked.FRIENDS else Asked.ASKED

    override fun request(code: String): Asked = asked(supa.rpc("request_friend", buildJsonObject { put("friend_code", code) }))

    override fun requestById(id: String): Asked = asked(supa.rpc("request_friend_by_id", buildJsonObject { put("player", id) }))

    override fun answer(id: String, accept: Boolean) {
        supa.rpc("answer_friend", buildJsonObject {
            put("player", id)
            put("accept", accept)
        })
    }

    override fun remove(id: String) {
        supa.rpc("remove_friend", buildJsonObject { put("player", id) })
    }

    override fun block(id: String) {
        supa.rpc("block_player", buildJsonObject { put("player", id) })
    }

    override fun unblock(id: String) {
        supa.rpc("unblock_player", buildJsonObject { put("player", id) })
    }

    override fun blocked(): List<Blocked> = rows(supa.rpc("list_blocked", buildJsonObject {})).map { r ->
        Blocked(r.str("id")!!, r.str("name") ?: "Player", r.int("icon"))
    }

    override fun list(): List<Friend> = rows(supa.rpc("list_friends", buildJsonObject {})).map { r ->
        Friend(
            id = r.str("id")!!,
            name = r.str("name") ?: "Player",
            icon = r.int("icon"),
            status = when (r.str("status")) {
                "friend" -> Friend.Status.FRIEND
                "incoming" -> Friend.Status.INCOMING
                else -> Friend.Status.OUTGOING
            },
            online = r["online"]?.jsonPrimitive?.booleanOrNull ?: false,
            playing = r.str("game_set"),
            room = r.str("room"),
            roomSpace = r["room_space"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override fun presence(set: String?, room: String?) {
        supa.rpc("set_presence", buildJsonObject {
            put("game_set", set)
            put("room_code", room)
        })
    }

    override fun offline() {
        supa.rpc("go_offline", buildJsonObject {})
    }

    override fun invite(id: String, room: String) {
        supa.rpc("invite_friend", buildJsonObject {
            put("player", id)
            put("room_code", room)
        })
    }

    override fun invites(): List<Invite> = rows(supa.rpc("my_invites", buildJsonObject {})).map { r ->
        Invite(
            id = r["id"]!!.jsonPrimitive.long,
            from = r.str("sender")!!,
            name = r.str("name") ?: "A friend",
            icon = r.int("icon"),
            room = r.str("room")!!,
            set = r.str("game_set") ?: "",
            atMs = runCatching { java.time.OffsetDateTime.parse(r.str("created_at")).toInstant().toEpochMilli() }.getOrDefault(0L),
        )
    }

    override fun dismiss(invite: Long) {
        supa.rpc("dismiss_invite", buildJsonObject { put("invite", invite) })
    }

    private fun rows(e: kotlinx.serialization.json.JsonElement): List<JsonObject> = (e as? JsonArray).orEmpty().map { it.jsonObject }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
}

/** A friend code as shown: "K7Q2-MX8P". */
fun prettyFriendCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }.let { if (it.length == 8) it.take(4) + "-" + it.drop(4) else it }
