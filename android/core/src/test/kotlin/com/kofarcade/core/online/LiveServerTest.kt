package com.kofarcade.core.online

import com.kofarcade.core.net.Signal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Against the real project, only when asked (KOF_LIVE=1): two guests, a
 * room, its signaling channel, relay servers. Leaves two guests and a room
 * that closes itself after 10 quiet minutes.
 */
class LiveServerTest {
    private val live = System.getenv("KOF_LIVE") == "1"

    private class Mem : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }
    }

    private fun guest() = Supabase(URL, KEY, Mem())

    @Test
    fun twoGuestsSignalThroughTheirRoom() {
        if (!live) return
        val host = guest()
        val friend = guest()
        val room = host.rpc("create_room", buildJsonObject {
            put("game_set", "kof97")
            put("rom", 1)
            put("bios", 0)
            put("max_players", 2)
        }).jsonObject["code"]!!.jsonPrimitive.content
        friend.rpc("join_room", buildJsonObject { put("room_code", room) })

        val a = RealtimeSignaling(host, room, host.userId!!)
        val b = RealtimeSignaling(friend, room, friend.userId!!)
        val got = LinkedBlockingQueue<Signal>()
        b.listen { got.put(it) }
        val until = System.currentTimeMillis() + 15_000
        while (!(a.ready && b.ready) && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue(a.ready && b.ready, "joined: ${a.problem} ${b.problem}")
        a.send(Signal(Signal.HELLO, host.userId!!, friend.userId!!, gen = 7))
        val s = got.poll(10, TimeUnit.SECONDS)
        assertNotNull(s, "no signal came")
        assertEquals(7, s.gen)

        // A stranger can't join the room's channel.
        val stranger = guest()
        val c = RealtimeSignaling(stranger, room, stranger.userId ?: stranger.session().user)
        Thread.sleep(5_000)
        assertTrue(!c.ready, "a stranger joined")
        println("stranger refused: ${c.problem}")

        // Relay servers for a member.
        val relay = RelayServers(host, room)
        val servers = relay.get()
        println("relay: ${servers.flatMap { it.urls }} note=${relay.note}")
        assertTrue(servers.isNotEmpty())

        listOf(a, b, c).forEach { it.close() }
        host.rpc("leave_room", buildJsonObject { put("room_code", room) })
        friend.rpc("leave_room", buildJsonObject { put("room_code", room) })
    }

    private companion object {
        const val URL = "https://ysbskjtvccdbhnfuccso.supabase.co"
        const val KEY = "sb_publishable_ZdWmdg-2_f5_rvnzhiviYQ_VSUnZPIE"
    }
}
