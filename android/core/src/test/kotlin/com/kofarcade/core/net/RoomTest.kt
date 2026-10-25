package com.kofarcade.core.net

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomTest {
    private val localhost = InetAddress.getLoopbackAddress()
    private val open = ArrayList<Room>()
    private val games = ArrayList<FakeGame>()

    @AfterTest
    fun cleanUp() {
        games.forEach { it.running = false }
        open.forEach { runCatching { it.close() } }
    }

    private fun waitFor(what: String = "condition", condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < until, "timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun host(set: String = "kof97", seats: Int = 2) =
        Room.host("Host", set, "Game", rom = 1, bios = 2, maxPlayers = seats, port = 0, beaconPort = -1).also { open += it }

    private fun join(room: Room, name: String, set: String = "kof97", rom: Long = 1) =
        Room.join(localhost, port(room), name, set, rom = rom, bios = 2, listenPort = 0, beaconPort = -1).also { open += it }

    private fun port(room: Room): Int {
        val f = Room::class.java.getDeclaredField("server").apply { isAccessible = true }
        return (f.get(room) as java.net.ServerSocket).localPort
    }

    /** A phone's game as the game screen runs it, with a fake emulator. */
    private inner class FakeGame(val room: Room, @Volatile var accept: Boolean = true) : RoomListener {
        private val lock = Any()
        @Volatile var epoch = -1
        val local = ArrayList<Int>()
        val remotes = HashMap<Int, ArrayList<Int>>()
        @Volatile var emulated = 0
        @Volatile var running = true
        @Volatile var closedReason: String? = null
        @Volatile var error: String? = null
        @Volatile var partnerPaused: Boolean? = null
        @Volatile var loaded: ByteArray? = null
        val news = CopyOnWriteArrayList<String>()

        init {
            games += this
        }

        override fun captureState(): ByteArray = byteArrayOf(7, room.localPlayer.toByte())

        override fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?) = synchronized(lock) {
            this.epoch = epoch
            local.clear()
            remotes.clear()
            for (p in 0 until 4) if (activeMask and (1 shl p) != 0 && p != room.localPlayer) remotes[p] = ArrayList()
            emulated = 0
            if (state != null) loaded = state
        }

        override fun onRemote(player: Int, start: Int, masks: IntArray) = synchronized(lock) {
            val got = remotes.getOrPut(player) { ArrayList() }
            if (got.size != start) error = "player $player: got ${got.size}, sent from $start"
            got += masks.toList()
            Unit
        }

        override fun onDesync(frame: Int) {
            error = "desync at $frame"
        }

        override fun onClosed(reason: String) {
            closedReason = reason
        }

        override fun onPartnerPaused(player: Int, paused: Boolean) {
            partnerPaused = paused
        }

        override fun onJoinRequest(name: String, answer: (Boolean) -> Unit) = answer(accept)

        override fun onNews(message: String) {
            news += message
        }

        fun play() {
            room.start(this)
            thread(isDaemon = true) {
                while (running) {
                    val e = epoch
                    val known = synchronized(lock) { remotes.values.minOfOrNull { it.size } ?: Int.MAX_VALUE }
                    // Rollback emulator: runs up to 8 frames past the slowest partner.
                    if (emulated < local.size && emulated.toLong() < known.toLong() + 8) emulated++
                    var f = room.lockstep.nextLocal
                    val s = room.sample(emulated) { (f++ * 13 + room.localPlayer) and 0xFFF }
                    synchronized(lock) { if (s.epoch == epoch && e == epoch) local += s.masks.toList() }
                    room.updateAdvantage(emulated)
                    Thread.sleep(16)
                }
            }
        }

        /** Frames this game has of [player]'s inputs, in the current game. */
        fun framesOf(player: Int) = synchronized(lock) { remotes[player]?.size ?: 0 }

        /** [player]'s inputs as received match what that player sampled (in the same game). */
        fun matches(player: Int, other: FakeGame, frames: Int): Boolean = synchronized(lock) {
            val got = remotes[player]?.take(frames) ?: return false
            got.size == frames && got.withIndex().all { (i, m) -> m == ((i * 13 + other.room.localPlayer) and 0xFFF) }
        }
    }

    @Test
    fun aGuestJoinsARunningRoomAndBothPlayTheSameFrames() {
        val h = host()
        h.startState = byteArrayOf(1, 2, 3)
        val a = FakeGame(h).apply { play() }
        // Alone, the host's game runs on its own.
        waitFor("host seated") { a.epoch == 1 && a.emulated > 10 }
        assertEquals(1, h.seats.size)

        val g = join(h, "Guest")
        assertEquals(1, g.localPlayer)
        assertEquals(h.code, g.code)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(g.startState))
        val b = FakeGame(g).apply { play() }
        // Seated together from the host's game as it stood.
        waitFor("both seated") { a.epoch == 2 && b.epoch == 2 }
        assertTrue(byteArrayOf(7, 0).contentEquals(b.loaded))
        assertEquals(setOf(0, 1), h.seats.keys)
        assertEquals(setOf(0, 1), g.seats.keys)
        waitFor("120 frames each way") { a.framesOf(1) >= 120 && b.framesOf(0) >= 120 }
        assertTrue(a.matches(1, b, 120), "host got the guest's inputs")
        assertTrue(b.matches(0, a, 120), "guest got the host's inputs")
        assertTrue(a.news.any { "Guest joined" in it }, a.news.toString())
        assertEquals(null, a.error ?: b.error)

        // Pausing reaches the other phone.
        g.sendPause(true)
        waitFor("pause") { a.partnerPaused == true }
        g.sendPause(false)
        waitFor("resume") { a.partnerPaused == false }
    }

    @Test
    fun aGuestLeavingLeavesTheHostPlayingAndTheSeatFree() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("both seated") { a.epoch == 2 && b.epoch == 2 }

        b.running = false
        g.close()
        waitFor("host alone again") { a.epoch == 3 && h.seats.keys == setOf(0) }
        val before = a.emulated
        waitFor("host plays on") { a.emulated > before + 20 }
        assertEquals(null, a.closedReason)
        assertTrue(a.news.any { "Guest left" in it }, a.news.toString())

        // The seat is free for someone else.
        val g2 = join(h, "Second")
        assertEquals(1, g2.localPlayer)
        val c = FakeGame(g2).apply { play() }
        waitFor("seated with the newcomer") { a.epoch == 4 && c.epoch == 4 }
        waitFor("frames") { a.framesOf(1) >= 60 && c.framesOf(0) >= 60 }
        assertTrue(a.matches(1, c, 60) && c.matches(0, a, 60))
    }

    @Test
    fun whenTheHostLeavesTheNextPlayerTakesTheRoomOver() {
        val h = host("dino", seats = 3)
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g1 = join(h, "Guest A", "dino")
        val b = FakeGame(g1).apply { play() }
        waitFor("A seated") { b.epoch >= 2 }
        val g2 = join(h, "Guest B", "dino")
        val c = FakeGame(g2).apply { play() }
        waitFor("all three seated") { a.epoch == 3 && b.epoch == 3 && c.epoch == 3 }
        assertEquals(setOf(0, 1, 2), g2.seats.keys)

        // The host leaves: Guest A (seat 1, the lowest left) takes over.
        a.running = false
        h.close()
        waitFor("A owns the room") { g1.isOwner && g2.owner == 1 }
        waitFor("seated without the host") { b.epoch == 4 && c.epoch == 4 && g2.seats.keys == setOf(1, 2) }
        // Everyone carries on from the new owner's game.
        assertTrue(byteArrayOf(7, 1).contentEquals(c.loaded))
        waitFor("frames between the two left") { b.framesOf(2) >= 60 && c.framesOf(1) >= 60 }
        assertTrue(b.matches(2, c, 60) && c.matches(1, b, 60))
        assertTrue(b.news.any { "You're the host now" in it }, b.news.toString())
        assertEquals(null, b.closedReason ?: c.closedReason)
        assertEquals(null, b.error ?: c.error)

        // And the new owner can take someone into the free seat.
        val g3 = join(g1, "Guest C", "dino")
        assertEquals(0, g3.localPlayer)
        val d = FakeGame(g3).apply { play() }
        waitFor("seated with C") { b.epoch == 5 && c.epoch == 5 && d.epoch == 5 }
        waitFor("C's frames") { b.framesOf(0) >= 30 && c.framesOf(0) >= 30 }
        assertTrue(c.matches(0, d, 30))
    }

    @Test
    fun theHostCanSayNoAndAFullRoomTurnsPeopleAway() {
        val h = host()
        val a = FakeGame(h, accept = false).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val no = assertFailsWith<Refused> { join(h, "Guest") }
        assertEquals("The host said no.", no.message)

        a.accept = true
        val g = join(h, "Guest")
        FakeGame(g).play()
        waitFor("seated") { h.seats.size == 2 }
        val full = assertFailsWith<Refused> { join(h, "Third") }
        assertTrue("full" in full.message!!, full.message)
        val other = assertFailsWith<Refused> { join(h, "Other", rom = 9) }
        assertTrue("differs" in other.message!!, other.message)
    }

    @Test
    fun aRematchStartsEveryoneFromTheStartState() {
        val h = host()
        h.startState = byteArrayOf(9, 9)
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("both seated") { a.epoch == 2 && b.epoch == 2 }
        g.requestRematch()
        waitFor("rematch") { a.epoch == 3 && b.epoch == 3 }
        assertTrue(byteArrayOf(9, 9).contentEquals(a.loaded))
        assertTrue(byteArrayOf(9, 9).contentEquals(b.loaded))
    }

    @Test
    fun aProbeLearnsTheRoomsGame() {
        val h = Room.host("Host", "kf2k5uni", "KOF 2005", rom = 7, bios = 2, maxPlayers = 2, port = 0, beaconPort = -1).also { open += it }
        FakeGame(h).play()
        val b = probe(localhost, port(h))
        assertEquals("kf2k5uni", b.set)
        assertEquals("KOF 2005", b.title)
        assertEquals(h.code, b.code)
    }

    @Test
    fun roomCodesAreFourEasyCharacters() {
        val codes = (0 until 200).map { roomCode(it * 7919 + 13) }
        assertTrue(codes.all { it.length == 4 && it.none { c -> c in "01OI" } })
        assertTrue(codes.toSet().size > 190)
    }

    @Test
    fun beaconsAreHeard() {
        val heard = CountDownLatch(1)
        var got: Beacon? = null
        BeaconListener(port = 0) { b, _ -> got = b; heard.countDown() }.use { listener ->
            val field = BeaconListener::class.java.getDeclaredField("socket").apply { isAccessible = true }
            val port = (field.get(listener) as java.net.DatagramSocket).localPort
            val beacon = Beacon(PROTOCOL, "Host", "kof97", "KOF '97", 1, 7845, code = "K7Q2")
            val bytes = encode(beacon)
            java.net.DatagramSocket().use { it.send(java.net.DatagramPacket(bytes, bytes.size, localhost, port)) }
            assertTrue(heard.await(5, TimeUnit.SECONDS))
            assertEquals(beacon, got)
        }
    }
}
