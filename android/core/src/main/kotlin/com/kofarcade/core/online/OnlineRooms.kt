package com.kofarcade.core.online

import com.kofarcade.core.net.Address
import com.kofarcade.core.net.IceServer
import com.kofarcade.core.net.NetLog
import com.kofarcade.core.net.Redirected
import com.kofarcade.core.net.Refused
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.RtcFactory
import com.kofarcade.core.net.Signaling
import com.kofarcade.core.net.WebRtcTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An open internet room, as the server lists it (find_room). */
data class RoomInfo(
    val code: String,
    val set: String,
    val rom: Long,
    val bios: Long,
    val maxPlayers: Int,
    /** The player who made it. */
    val owner: String,
    val ownerName: String?,
    val members: Int,
    /** Listed with its code: anyone may join. */
    val open: Boolean = false,
    /** Made open: every connection goes through the relay (strangers never learn an address). */
    val relayOnly: Boolean = false,
)

/** A room on the Online screen's list (list_rooms): an open room's [code] comes with it, a locked one's is null. */
data class ListedRoom(
    val code: String?,
    val set: String,
    val maxPlayers: Int,
    val ownerName: String?,
    val ownerIcon: Int,
    /** Phones in it, players and watchers. */
    val members: Int,
    val open: Boolean,
)

/** The server's side of internet rooms: the database functions of phase 1.2, or a fake in tests. */
interface RoomServer {
    /** This phone's player id (signs in if needed). */
    fun me(): String
    /** A new room ([open]: listed with its code, and relay-only); its code. */
    fun create(set: String, rom: Long, bios: Long, maxPlayers: Int, open: Boolean): String
    /** The open room with this code (any case, dashes and spaces ignored), or null. */
    fun find(code: String): RoomInfo?
    /** Live rooms with space that this phone isn't in, open ones first. */
    fun list(): List<ListedRoom>
    /** Opens or locks a room this phone is in (a room made locked can't be opened). */
    fun setOpen(code: String, open: Boolean)
    fun join(code: String)
    /** The room's phones, the first in first (its members can see them). */
    fun members(code: String): List<String>
    /** Still here: rooms nobody touches for 10 minutes close. */
    fun touch(code: String)
    fun leave(code: String)
}

/** [RoomServer] on Supabase (server/supabase/migrations). */
class SupabaseRooms(private val supa: Supabase) : RoomServer {
    override fun me(): String = supa.session().user

    override fun create(set: String, rom: Long, bios: Long, maxPlayers: Int, open: Boolean): String =
        supa.rpc("create_room", buildJsonObject {
            put("game_set", set)
            put("rom", rom)
            put("bios", bios)
            put("max_players", maxPlayers)
            put("open", open)
        }).jsonObject["code"]!!.jsonPrimitive.content

    override fun find(code: String): RoomInfo? {
        val rows = supa.rpc("find_room", buildJsonObject { put("room_code", code) }) as? JsonArray ?: return null
        val r = rows.firstOrNull()?.jsonObject ?: return null
        return RoomInfo(
            r["code"]!!.jsonPrimitive.content,
            r["game_set"]!!.jsonPrimitive.content,
            r["rom"]!!.jsonPrimitive.long,
            r["bios"]!!.jsonPrimitive.long,
            r["max_players"]!!.jsonPrimitive.int,
            r["owner"]!!.jsonPrimitive.content,
            r["owner_name"]?.jsonPrimitive?.contentOrNull,
            r["members"]!!.jsonPrimitive.int,
            r["open"]?.jsonPrimitive?.booleanOrNull ?: false,
            r["relay_only"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override fun list(): List<ListedRoom> =
        (supa.rpc("list_rooms", buildJsonObject {}) as? JsonArray).orEmpty().map { e ->
            val r = e.jsonObject
            ListedRoom(
                r["code"]?.jsonPrimitive?.contentOrNull,
                r["game_set"]!!.jsonPrimitive.content,
                r["max_players"]!!.jsonPrimitive.int,
                r["owner_name"]?.jsonPrimitive?.contentOrNull,
                r["owner_icon"]?.jsonPrimitive?.intOrNull ?: 0,
                r["members"]!!.jsonPrimitive.int,
                r["open"]!!.jsonPrimitive.boolean,
            )
        }

    override fun setOpen(code: String, open: Boolean) {
        supa.rpc("set_room_open", buildJsonObject {
            put("room_code", code)
            put("open", open)
        })
    }

    override fun join(code: String) {
        supa.rpc("join_room", buildJsonObject { put("room_code", code) })
    }

    override fun members(code: String): List<String> =
        (supa.select("room_members", mapOf("room" to "eq.$code", "order" to "joined_at.asc"), "member") as JsonArray)
            .map { it.jsonObject["member"]!!.jsonPrimitive.content }

    override fun touch(code: String) {
        supa.rpc("touch_room", buildJsonObject { put("room_code", code) })
    }

    override fun leave(code: String) {
        supa.rpc("leave_room", buildJsonObject { put("room_code", code) })
    }
}

/** Why joining by code didn't start, in plain words (before any connection is tried). */
class CantJoin(message: String) : Exception(message)

/**
 * An internet room on this phone: the [room] (the same room logic as on
 * Wi-Fi), its code, and what keeps it going: the WebRTC transport, the
 * server's record (touched every minute while open). [close] leaves it all.
 */
class OnlineRoom internal constructor(
    val room: Room,
    val code: String,
    val transport: WebRtcTransport,
    private val server: RoomServer,
    /** Listed with its code: anyone may join (as this phone last knew it). */
    open: Boolean = false,
) {
    @Volatile var open = open
        private set

    /** Made open: it can be locked and opened again; a room made locked stays locked. */
    val canOpen: Boolean get() = transport.relayOnly

    private val keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "room-keepalive").apply { isDaemon = true } }
    @Volatile private var closed = false

    init {
        keepAlive.scheduleAtFixedRate({
            runCatching { server.touch(code) }.onFailure { NetLog.add("room $code: couldn't tell the server it's open: ${it.message}") }
        }, TOUCH_MS, TOUCH_MS, TimeUnit.MILLISECONDS)
    }

    /** Opens or locks the room (on the server: a network call). */
    fun setOpen(open: Boolean) {
        server.setOpen(code, open)
        this.open = open
    }

    /** Leaves: the room, the connections, and the server's record (the last one out closes it). */
    fun close() {
        if (closed) return
        closed = true
        keepAlive.shutdownNow()
        runCatching { room.close() }
        transport.close()
        runCatching { server.leave(code) }
    }

    companion object {
        const val TOUCH_MS = 60_000L
    }
}

/**
 * Internet rooms (phase 1.5): a room is made on the server (its code), and
 * phones find each other through its signaling channel and connect over
 * WebRTC; from there it's the same room as on Wi-Fi (seats, watchers,
 * takeover, rejoin).
 *
 * Every phone takes links on [PORT] of its transport, so whoever runs the
 * room (the one who made it, or whoever took it over) is reached there.
 */
class OnlineRooms(
    private val server: RoomServer,
    private val rtc: RtcFactory,
    /** The room's signaling, for this phone (its player id). */
    private val signaling: (code: String, me: String) -> Signaling,
    /** ICE servers for the room (relay credentials come per room). */
    private val iceServers: (code: String) -> () -> List<IceServer>,
) {
    /**
     * Makes a room for the game and runs it here. [open]: listed with its
     * code for anyone to join, and relay-only (so it needs the relay).
     */
    fun host(name: String, set: String, title: String, rom: Long, bios: Long, maxPlayers: Int, build: String = "", device: String = "", open: Boolean = false): OnlineRoom {
        val me = server.me()
        val code = server.create(set, rom, bios, maxPlayers, open)
        val ice = try {
            iceServers(code).also { if (open && !hasRelay(it())) throw CantJoin(NO_RELAY_TO_OPEN) }
        } catch (e: Exception) {
            runCatching { server.leave(code) }
            throw e
        }
        val transport = WebRtcTransport(signaling(code, me), rtc, ice, code, relayOnly = open)
        try {
            val room = Room.host(name, set, title, rom, bios, maxPlayers, port = PORT, beaconPort = -1, build = build, device = device, transport = transport)
            return OnlineRoom(room, code, transport, server, open)
        } catch (e: Exception) {
            transport.close()
            runCatching { server.leave(code) }
            throw e
        }
    }

    /** The room with [code], to check before joining (its game, who made it); [CantJoin] if there's none. */
    fun find(code: String): RoomInfo = server.find(code) ?: throw CantJoin("No room ${pretty(code)}: check the code. A room ends when everyone has left it.")

    /** The rooms to suggest on the Online screen (open ones first). */
    fun list(): List<ListedRoom> = server.list()

    /**
     * Joins the room with [code] to watch, as on Wi-Fi. Tries whoever runs
     * it: the one who made it, then the others (a room taken over says
     * where its new host is).
     */
    fun join(info: RoomInfo, name: String, set: String, rom: Long, bios: Long, build: String = "", device: String = "", connectMs: Int = 15_000): OnlineRoom {
        val me = server.me()
        server.join(info.code)
        val transport = try {
            val ice = iceServers(info.code)
            if (info.relayOnly && !hasRelay(ice())) throw CantJoin(NO_RELAY_TO_JOIN)
            WebRtcTransport(signaling(info.code, me), rtc, ice, info.code, relayOnly = info.relayOnly)
        } catch (e: Exception) {
            runCatching { server.leave(info.code) }
            throw e
        }
        try {
            val others = (listOf(info.owner) + runCatching { server.members(info.code) }.getOrDefault(emptyList())).distinct().filter { it != me }
            var last: Exception = CantJoin("Nobody's in room ${pretty(info.code)} any more.")
            for (at in others) {
                try {
                    val room = try {
                        Room.join(transport, Address(at, PORT), name, set, rom, bios, listenPort = PORT, beaconPort = -1, build = build, device = device, connectMs = connectMs)
                    } catch (e: Redirected) {
                        Room.join(transport, e.at, name, set, rom, bios, listenPort = PORT, beaconPort = -1, build = build, device = device, connectMs = connectMs)
                    }
                    return OnlineRoom(room, info.code, transport, server, info.open)
                } catch (e: Refused) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last
        } catch (e: Exception) {
            transport.close()
            runCatching { server.leave(info.code) }
            throw e
        }
    }

    companion object {
        /** Where every phone of an internet room takes links. */
        const val PORT = 1

        const val NO_RELAY_TO_OPEN = "Open rooms are off right now: they go through our relay, which keeps players' " +
            "addresses private, and it isn't available. Make a locked room instead: friends join with its code."
        const val NO_RELAY_TO_JOIN = "This open room goes through our relay, which isn't available right now. " +
            "Ask for a locked room's code instead."

        /** Whether [ice] has a relay (TURN) server: an open room's connections need one. */
        fun hasRelay(ice: List<IceServer>): Boolean = ice.any { s -> s.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }

        /** "K7Q2MX" -> "K7Q-2MX", as the app shows codes. */
        fun pretty(code: String): String {
            val c = code.uppercase().filter { it.isLetterOrDigit() }
            return if (c.length == 6) c.substring(0, 3) + "-" + c.substring(3) else c
        }
    }
}
