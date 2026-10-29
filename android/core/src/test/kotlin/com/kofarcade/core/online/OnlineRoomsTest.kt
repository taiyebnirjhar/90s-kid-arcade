package com.kofarcade.core.online

import com.kofarcade.core.net.FakeNet
import com.kofarcade.core.net.Path
import com.kofarcade.core.net.Refused
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.RoomListener
import com.kofarcade.core.net.RtcNet
import com.kofarcade.core.net.startingDelay
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Internet rooms end to end over the simulated internet: a room made on a
 * fake of the server (the same rules as the database's), joined by its
 * code, phones connected through signaling and WebRTC.
 */
class OnlineRoomsTest {
    /** The database's room rules, in memory. */
    private class FakeServer : RoomServer {
        class R(val info: RoomInfo, val members: MutableList<String> = mutableListOf(), var open: Boolean = true, var touched: Int = 0)
        val rooms = ConcurrentHashMap<String, R>()
        private var codes = 0
        var who: () -> String = { error("who?") }

        override fun me() = who()

        @Synchronized
        override fun create(set: String, rom: Long, bios: Long, maxPlayers: Int): String {
            val code = "K7Q2M" + "ABCDEFGHJK"[codes++]
            rooms[code] = R(RoomInfo(code, set, rom, bios, maxPlayers, me(), "Host", 1), mutableListOf(me()))
            return code
        }

        override fun find(code: String): RoomInfo? {
            val r = rooms[code.uppercase().filter { it.isLetterOrDigit() }]?.takeIf { it.open } ?: return null
            return r.info.copy(members = r.members.size)
        }

        @Synchronized
        override fun join(code: String) {
            val r = rooms[code]?.takeIf { it.open } ?: throw IllegalStateException("No open room")
            if (me() !in r.members) r.members += me()
        }

        override fun members(code: String) = rooms[code]?.members?.toList().orEmpty()

        override fun touch(code: String) {
            rooms[code]?.let { it.touched++ }
        }

        @Synchronized
        override fun leave(code: String) {
            val r = rooms[code] ?: return
            r.members -= me()
            if (r.members.isEmpty()) r.open = false
        }
    }

    private val net = RtcNet(FakeNet { Path(latencyMs = 60, jitterMs = 10) })
    private val server = FakeServer()
    private val opened = ArrayList<OnlineRoom>()

    @AfterTest
    fun closeAll() = opened.forEach { runCatching { it.close() } }

    /** One phone: its own player id and view of the server. */
    private fun phone(id: String): OnlineRooms {
        val mine = object : RoomServer by server {
            override fun me() = id
            override fun create(set: String, rom: Long, bios: Long, maxPlayers: Int) = synchronized(server) { server.who = { id }; server.create(set, rom, bios, maxPlayers) }
            override fun join(code: String) = synchronized(server) { server.who = { id }; server.join(code) }
            override fun leave(code: String) = synchronized(server) { server.who = { id }; server.leave(code) }
        }
        return OnlineRooms(mine, net.factory(id), { _, me -> net.hub.join(me) }, { { emptyList() } })
    }

    private class Quiet : RoomListener {
        override fun captureState() = byteArrayOf(1)
        override fun snapshot() = 0 to byteArrayOf(1)
        override fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?, startFrame: Int) {}
        override fun onRemote(player: Int, start: Int, masks: IntArray) {}
        override fun onDesync(frame: Int) {}
        override fun onClosed(reason: String) {}
        override fun onLost(reason: String) {}
        override fun onNews(message: String) {}
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun OnlineRoom.started() = also {
        opened += it
        room.start(Quiet())
    }

    @Test
    fun aRoomIsMadeAndJoinedByItsCode() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        assertEquals(host.code, host.room.key, "the room's key is its code")
        val info = phone("bbb").find(host.code.lowercase().chunked(3).joinToString("-"))
        assertEquals("kof97", info.set)
        assertEquals(11L, info.rom)
        val guest = phone("bbb").join(info, "Rafi", "kof97", rom = 11, bios = 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        assertEquals(listOf("aaa", "bbb"), server.members(host.code))
        assertTrue(guest.room.watching)
    }

    @Test
    fun aGameStartsAtTheDelayForThePing() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        val guest = phone("bbb").join(phone("bbb").find(host.code), "Rafi", "kof97", 11, 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        Thread.sleep(500) // a round trip measured (about 120-140 ms here)
        guest.room.requestSeat()
        waitFor("playing") { guest.room.localPlayer == 1 && host.room.epoch >= 2 && guest.room.epoch >= 2 }
        assertTrue(host.room.delay >= 3 && guest.room.delay >= 3, "delays ${host.room.delay} / ${guest.room.delay}")
    }

    @Test
    fun noRoomForThatCode() {
        assertFailsWith<CantJoin> { phone("bbb").find("ZZZ999") }
    }

    @Test
    fun aDifferentRomIsRefusedWithTheReason() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        val info = phone("bbb").find(host.code)
        val e = assertFailsWith<Refused> { phone("bbb").join(info, "Rafi", "kof97", rom = 99, bios = 22) }
        assertTrue("differs" in e.message.orEmpty(), e.message)
        assertEquals(listOf("aaa"), server.members(host.code), "turned away: not left in the room")
    }

    @Test
    fun leavingClosesTheRoomWhenTheLastOneGoes() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        val guest = phone("bbb").join(phone("bbb").find(host.code), "Rafi", "kof97", 11, 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        guest.close()
        assertEquals(listOf("aaa"), server.members(host.code))
        host.close()
        assertFailsWith<CantJoin> { phone("ccc").find(host.code) }
    }

    @Test
    fun aLateComerFindsTheRoomAfterItsMakerLeft() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        val rafi = phone("bbb").join(phone("bbb").find(host.code), "Rafi", "kof97", 11, 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        // The maker leaves: Rafi takes the room over.
        host.close()
        waitFor("Rafi runs it") { rafi.room.isOwner }
        // Someone with the code still gets in: the server says Rafi's there.
        val late = phone("ccc").join(phone("ccc").find(host.code), "Mim", "kof97", 11, 22).started()
        waitFor("Mim watching") { rafi.room.watcherCount == 1 }
        assertTrue(late.room.watching)
    }
}
