package com.kofarcade.core.online

import com.kofarcade.core.net.FakeNet
import com.kofarcade.core.net.IceServer
import com.kofarcade.core.net.RtcFactory
import com.kofarcade.core.net.RtcPeer
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // [open]: not closed yet; [listed]: an open room in the database's sense (its code on the list).
        class R(val info: RoomInfo, val members: MutableList<String> = mutableListOf(), var open: Boolean = true, var touched: Int = 0, var listed: Boolean = false)
        val rooms = ConcurrentHashMap<String, R>()
        private var codes = 0
        var who: () -> String = { error("who?") }

        override fun me() = who()

        @Synchronized
        override fun create(set: String, rom: Long, bios: Long, maxPlayers: Int, open: Boolean): String {
            val code = "K7Q2M" + "ABCDEFGHJK"[codes++]
            rooms[code] = R(RoomInfo(code, set, rom, bios, maxPlayers, me(), "Host", 1, relayOnly = open), mutableListOf(me()), listed = open)
            return code
        }

        override fun find(code: String): RoomInfo? {
            val r = rooms[code.uppercase().filter { it.isLetterOrDigit() }]?.takeIf { it.open } ?: return null
            return r.info.copy(members = r.members.size, open = r.listed)
        }

        override fun list(): List<ListedRoom> = rooms.values
            .filter { it.open && me() !in it.members && it.members.size < 8 }
            .sortedByDescending { it.listed }
            .map { ListedRoom(it.info.code.takeIf { _ -> it.listed }, it.info.set, it.info.maxPlayers, it.info.ownerName, 0, it.members.size, it.listed) }

        override fun setOpen(code: String, open: Boolean) {
            val r = rooms[code]?.takeIf { me() in it.members } ?: throw IllegalStateException("Not in room")
            require(!open || r.info.relayOnly) { "made locked" }
            r.listed = open
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

    // Which phones' connections were made relay-only.
    private val relayOnlyPeers = ConcurrentHashMap<String, Boolean>()

    /** One phone: its own player id and view of the server; [relay]: the relay's there (TURN in its ICE servers). */
    private fun phone(id: String, relay: Boolean = true): OnlineRooms {
        val mine = object : RoomServer by server {
            override fun me() = id
            override fun create(set: String, rom: Long, bios: Long, maxPlayers: Int, open: Boolean) =
                synchronized(server) { server.who = { id }; server.create(set, rom, bios, maxPlayers, open) }
            override fun join(code: String) = synchronized(server) { server.who = { id }; server.join(code) }
            override fun leave(code: String) = synchronized(server) { server.who = { id }; server.leave(code) }
            override fun list() = synchronized(server) { server.who = { id }; server.list() }
            override fun setOpen(code: String, open: Boolean) = synchronized(server) { server.who = { id }; server.setOpen(code, open) }
        }
        val inner = net.factory(id)
        val rtc = object : RtcFactory {
            override fun peer(ice: List<IceServer>, events: RtcPeer.Events) = peer(ice, events, false)
            override fun peer(ice: List<IceServer>, events: RtcPeer.Events, relayOnly: Boolean): RtcPeer {
                relayOnlyPeers.merge(id, relayOnly, Boolean::and)
                return inner.peer(ice, events)
            }
        }
        val ice = if (relay) listOf(IceServer(listOf("turn:relay.test:3478"), "u", "p")) else emptyList()
        return OnlineRooms(mine, rtc, { _, me -> net.hub.join(me) }, { { ice } })
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

    @Test
    fun anOpenRoomIsListedWithItsCodeAndEveryoneInItGoesThroughTheRelay() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2, open = true).started()
        assertTrue(host.open && host.canOpen && host.transport.relayOnly)
        val listed = phone("bbb").list().single()
        assertEquals(host.code, listed.code, "an open room's code is on the list")
        assertTrue(listed.open)
        val guest = phone("bbb").join(phone("bbb").find(listed.code!!), "Rafi", "kof97", 11, 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        assertTrue(guest.transport.relayOnly && guest.open)
        assertEquals(mapOf("aaa" to true, "bbb" to true), relayOnlyPeers.toMap(), "both sides of the connection relay-only")
        // The guest's in it now: not suggested to the guest any more.
        assertTrue(phone("bbb").list().isEmpty())
    }

    @Test
    fun aLockedRoomIsListedWithoutItsCodeAndConnectsDirectly() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()
        val listed = phone("bbb").list().single()
        assertNull(listed.code, "a locked room's code isn't on the list")
        assertFalse(listed.open)
        assertFalse(host.canOpen, "a room made locked stays locked")
        val guest = phone("bbb").join(phone("bbb").find(host.code), "Rafi", "kof97", 11, 22).started()
        waitFor("watching") { host.room.watcherCount == 1 }
        assertFalse(guest.transport.relayOnly)
        assertEquals(mapOf("aaa" to false, "bbb" to false), relayOnlyPeers.toMap())
    }

    @Test
    fun anOpenRoomIsLockedAndOpenedAgain() {
        val host = phone("aaa").host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2, open = true).started()
        host.setOpen(false)
        assertFalse(host.open)
        assertNull(phone("bbb").list().single().code)
        host.setOpen(true)
        assertEquals(host.code, phone("bbb").list().single().code)
    }

    @Test
    fun withoutTheRelayNoOpenRoomIsMadeOrJoined() {
        val e = assertFailsWith<CantJoin> { phone("aaa", relay = false).host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2, open = true) }
        assertEquals(OnlineRooms.NO_RELAY_TO_OPEN, e.message)
        assertTrue(server.rooms.values.none { it.open }, "the room made for it is left again")
        // A locked room needs no relay.
        phone("aaa", relay = false).host("Host", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2).started()

        val open = phone("ccc").host("Mim", "kof97", "KOF '97", rom = 11, bios = 22, maxPlayers = 2, open = true).started()
        val info = phone("ddd", relay = false).find(open.code)
        assertEquals(OnlineRooms.NO_RELAY_TO_JOIN, assertFailsWith<CantJoin> { phone("ddd", relay = false).join(info, "Tanu", "kof97", 11, 22) }.message)
        assertEquals(listOf("ccc"), server.members(open.code), "turned away: not left in the room")
    }
}
