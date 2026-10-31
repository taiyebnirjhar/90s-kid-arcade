package com.kofarcade.core.net

import com.kofarcade.core.online.Account
import com.kofarcade.core.online.Friend
import com.kofarcade.core.online.KeyValue
import com.kofarcade.core.online.OnlineRooms
import com.kofarcade.core.online.RealtimeSignaling
import com.kofarcade.core.online.RelayServers
import com.kofarcade.core.online.Supabase
import com.kofarcade.core.online.SupabaseFriends
import com.kofarcade.core.online.SupabaseRooms
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Online play end to end against the real server: two phones in this
 * process, each a guest of the live Supabase project, with real WebRTC (the
 * desktop build of the phones' library). One makes a room, the other finds
 * it by its code and joins through the room's Realtime channel, with relay
 * credentials from the server; they play, chat, turn voice on and off, and
 * become friends. Run by hand (it uses the project), with LIVE_SUPABASE_URL
 * and LIVE_SUPABASE_KEY set (the app's public URL and key); skipped otherwise.
 * Found the way each phone tries (and why it gives up) in the connection log.
 */
class LiveOnlineTest {
    private val url = System.getenv("LIVE_SUPABASE_URL")
    private val key = System.getenv("LIVE_SUPABASE_KEY")
    private val rtc = DesktopRtc.orNull

    private class Memory : KeyValue {
        private val m = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = m[key]
        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }
    }

    private class Device(url: String, key: String, name: String, rtc: RtcFactory) {
        val supa = Supabase(url, key, Memory())
        val account = Account(supa, Memory())
        val profile = account.sync(name)
        val rooms = OnlineRooms(
            SupabaseRooms(supa),
            rtc,
            { code, me -> RealtimeSignaling(supa, code, me) },
            { code -> RelayServers(supa, code).also { it.get() }::get },
        )
        val friends = SupabaseFriends(supa)
    }

    private fun until(what: String, ms: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            assertTrue(System.currentTimeMillis() < end, "timed out: $what")
            Thread.sleep(50)
        }
    }

    private fun playTogether(open: Boolean) {
        val r = rtc ?: return
        val log = StringBuilder()
        val phones = Phones(log = { synchronized(log) { log.appendLine(it) } })
        val a = Device(url!!, key!!, "Live A", r)
        val b = Device(url, key, "Live B", r)
        val made = a.rooms.host("Live A", "kof97", "KOF '97", 1, 2, 2, open = open)
        try {
            val host = Phone(phones, "A") {}.also { it.attach(made.room) }
            val t0 = System.currentTimeMillis()
            val info = b.rooms.find(made.code)
            assertEquals(open, info.open)
            val joined = b.rooms.join(info, "Live B", "kof97", 1, 2)
            println("joined in ${System.currentTimeMillis() - t0} ms; open=$open; paths ${joined.transport.paths()}")
            val guest = Phone(phones, "B") {}.also { it.attach(joined.room) }
            until("watching") { made.room.watcherCount == 1 }
            joined.room.requestSeat()
            until("seated") { joined.room.localPlayer == 1 }
            until("300 frames each way", 60_000) { (host.game?.framesOf(1) ?: 0) >= 300 && (guest.game?.framesOf(0) ?: 0) >= 300 }
            assertTrue(host.game!!.inputsRight(1, joined.room.localId), "the host got the guest's own presses")
            assertTrue(guest.game!!.inputsRight(0, made.room.localId), "the guest got the host's own presses")

            // Chat, through the host.
            assertTrue(joined.room.say("GG", quick = true))
            until("chat") { made.room.chat.any { it.text == "GG" && it.name == "Live B" } }

            // Voice on and off on both (each connection negotiated again): the game goes on.
            val before = host.game!!.framesOf(1)
            made.transport.voice(true)
            joined.transport.voice(true)
            Thread.sleep(2_000)
            joined.transport.voice(false)
            until("play on after voice", 30_000) { host.game!!.framesOf(1) > before + 120 }
            println("ping ${joined.room.pingMs} ms, paths ${made.transport.paths()}")

            // Friends: B adds A by code, A sees the request, accepts; B sees A playing in this room.
            if (!open) {
                b.friends.request(a.profile.friendCode!!)
                until("request seen") { a.friends.list().any { it.status == Friend.Status.INCOMING } }
                val asker = a.friends.list().first { it.status == Friend.Status.INCOMING }
                a.friends.answer(asker.id, true)
                a.friends.presence("kof97", made.code)
                val seen = b.friends.list().first()
                assertEquals(Friend.Status.FRIEND, seen.status)
                assertEquals(made.code, seen.room)
                assertTrue(seen.online)
            }
            phones.closeAll()
        } catch (e: Throwable) {
            println(log)
            throw e
        } finally {
            runCatching { made.close() }
        }
    }

    @Test
    fun aLockedRoomOverTheLiveServer() {
        if (url == null || key == null) return
        playTogether(open = false)
    }

    // An open room shares only private addresses and the relay: it needs a
    // machine on a private network, or one that reaches Cloudflare's relay
    // (LIVE_OPEN=1). A cloud sandbox with a public address and no UDP out
    // has neither, and can't connect, by design.
    @Test
    fun anOpenRoomOverTheLiveServer() {
        if (url == null || key == null || System.getenv("LIVE_OPEN") == null) return
        playTogether(open = true)
    }
}
