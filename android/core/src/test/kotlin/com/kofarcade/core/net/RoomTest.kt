package com.kofarcade.core.net

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    private fun join(room: Room, name: String, set: String = "kof97", rom: Long = 1, device: String = "") =
        Room.join(localhost, port(room), name, set, rom = rom, bios = 2, listenPort = 0, beaconPort = -1, device = device).also { open += it }

    private fun port(room: Room): Int {
        val f = Room::class.java.getDeclaredField("server").apply { isAccessible = true }
        return (f.get(room) as java.net.ServerSocket).localPort
    }

    // What a phone presses on frame f (so each phone's inputs can be told apart).
    private fun pressed(f: Int, id: Int) = (f * 13 + id) and 0xFFF or 1

    /** A phone's game as the game screen runs it, with a fake emulator. */
    private inner class FakeGame(val room: Room) : RoomListener {
        private val lock = Any()
        @Volatile var epoch = -1
        @Volatile var startAt = 0
        val local = ArrayList<Int>()
        val remotes = HashMap<Int, ArrayList<Int>>()
        @Volatile var emulated = 0
        @Volatile var running = true
        // Presses nothing.
        @Volatile var idle = false
        @Volatile var closedReason: String? = null
        @Volatile var error: String? = null
        @Volatile var partnerPaused: Boolean? = null
        @Volatile var loaded: ByteArray? = null
        @Volatile var seatings = 0
        val news = CopyOnWriteArrayList<String>()

        init {
            games += this
        }

        override fun captureState(): ByteArray = byteArrayOf(7, room.localId.toByte())

        // The game at its newest frame with every input in.
        override fun snapshot(): Pair<Int, ByteArray> = synchronized(lock) {
            val known = startAt + (remotes.values.minOfOrNull { it.size } ?: Int.MAX_VALUE / 2)
            minOf(emulated, known) to byteArrayOf(8, room.localId.toByte())
        }

        override fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?, startFrame: Int) = synchronized(lock) {
            this.epoch = epoch
            startAt = startFrame
            local.clear()
            remotes.clear()
            for (p in 0 until 4) if (activeMask and (1 shl p) != 0 && p != room.localPlayer) remotes[p] = ArrayList()
            emulated = startFrame
            seatings++
            if (state != null) loaded = state
        }

        override fun onRemote(player: Int, start: Int, masks: IntArray) = synchronized(lock) {
            val got = remotes.getOrPut(player) { ArrayList() }
            if (startAt + got.size != start) error = "player $player: got ${startAt + got.size}, sent from $start"
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

        override fun onNews(message: String) {
            news += message
        }

        fun play() {
            room.start(this)
            thread(isDaemon = true) {
                while (running) {
                    synchronized(lock) {
                        val known = startAt.toLong() + (remotes.values.minOfOrNull { it.size } ?: Int.MAX_VALUE / 2)
                        // Rollback emulator: a player runs up to 8 frames past the
                        // slowest partner; a watcher only on real inputs.
                        if (room.watching) {
                            if (emulated < known) emulated++
                        } else if (emulated < local.size && emulated.toLong() < known + 8) {
                            emulated++
                        }
                    }
                    // Like the game screen: frame, inputs and push under the room's lock.
                    room.sample(
                        emulated = { emulated },
                        input = { if (idle) 0 else pressed(room.lockstep.nextLocal, room.localId) },
                        push = { _, masks -> synchronized(lock) { local += masks.toList() } },
                    )
                    room.updateAdvantage(emulated)
                    Thread.sleep(16)
                }
            }
        }

        /** Frames this game has of [player]'s inputs, in the current game. */
        fun framesOf(player: Int) = synchronized(lock) { remotes[player]?.size ?: 0 }

        /** [player]'s inputs as received match what [other] pressed (in the same game). */
        fun matches(player: Int, other: FakeGame, frames: Int): Boolean = synchronized(lock) {
            val got = remotes[player]?.take(frames) ?: return false
            got.size == frames && got.withIndex().all { (i, m) -> m == pressed(startAt + i, other.room.localId) }
        }
    }

    @Test
    fun aNewcomerWatchesWithoutStoppingAnyoneThenTakesTheFreeSeat() {
        val h = host()
        h.startState = byteArrayOf(1, 2, 3)
        val a = FakeGame(h).apply { play() }
        // Alone, the host's game runs on its own.
        waitFor("host seated and playing") { a.epoch == 1 && a.emulated > 30 }
        assertEquals(setOf(0), h.seats.keys)

        // Straight in, no one asked, nothing restarted: watching.
        val g = join(h, "Guest")
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(g.startState))
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 && h.watcherCount == 1 }
        assertTrue(g.watching)
        assertEquals(1, a.seatings, "the host's game went on")
        assertTrue(b.startAt > 0, "came in where the game was")
        assertTrue(byteArrayOf(8, 0).contentEquals(b.loaded))
        // The watcher gets every input of the host from there.
        waitFor("the watcher's game follows") { b.framesOf(0) >= 60 && b.emulated >= b.startAt + 50 }
        assertTrue(b.matches(0, a, 60))
        assertTrue(a.news.any { "Guest is watching" in it }, a.news.toString())

        // COIN on the watcher's phone: it's player 2.
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && b.epoch == 2 }
        assertEquals(1, g.localPlayer)
        assertEquals(setOf(0, 1), h.seats.keys)
        waitFor("120 frames each way") { a.framesOf(1) >= 120 && b.framesOf(0) >= 120 }
        assertTrue(a.matches(1, b, 120), "host got the guest's inputs")
        assertTrue(b.matches(0, a, 120), "guest got the host's inputs")
        assertTrue(a.news.any { "Guest is player 2" in it }, a.news.toString())
        assertEquals(null, a.error ?: b.error)
        assertEquals(h.key, g.key)

        // Pausing reaches the other player.
        g.sendPause(true)
        waitFor("pause") { a.partnerPaused == true }
        g.sendPause(false)
        waitFor("resume") { a.partnerPaused == false }
    }

    @Test
    fun watchersComingAndGoingNeverStopThePlayers() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("two players") { a.epoch == 2 && b.epoch == 2 }

        val watchers = (1..4).map { join(h, "Watcher $it") }
        val fakes = watchers.map { FakeGame(it).apply { play() } }
        waitFor("four watching") { fakes.all { it.epoch == 2 && it.framesOf(0) >= 30 && it.framesOf(1) >= 30 } }
        assertEquals(4, h.watcherCount)
        fakes.forEach { w -> assertTrue(w.matches(0, a, 30) && w.matches(1, b, 30)) }

        fakes[0].running = false
        watchers[0].close()
        waitFor("one gone") { h.watcherCount == 3 && g.watcherCount == 3 }
        // The players never had a new game.
        assertEquals(2, a.epoch)
        assertEquals(2, b.seatings)
        assertEquals(null, a.error ?: b.error ?: fakes.firstNotNullOfOrNull { it.error })
    }

    @Test
    fun theFirstToAskGetsTheSeatAndTheOthersKeepWatching() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g1 = join(h, "One")
        val b = FakeGame(g1).apply { play() }
        val g2 = join(h, "Two")
        val c = FakeGame(g2).apply { play() }
        waitFor("both watching") { b.epoch == 1 && c.epoch == 1 && h.watcherCount == 2 }

        g1.requestSeat()
        g2.requestSeat()
        waitFor("one seated") { a.epoch == 2 && b.epoch == 2 && c.epoch == 2 }
        Thread.sleep(300)
        assertEquals(2, a.epoch)
        // Whichever asked first plays; the other is told and keeps watching.
        val (player, watcher) = if (g1.watching) c to b else b to c
        assertEquals(1, player.room.localPlayer)
        assertTrue(watcher.room.watching)
        waitFor("told") { watcher.news.any { "All seats are in use" in it } }
        // The watcher sees both players.
        waitFor("watcher gets both") { watcher.framesOf(0) >= 60 && watcher.framesOf(1) >= 60 }
        assertTrue(watcher.matches(0, a, 60) && watcher.matches(1, player, 60))
        assertEquals(null, a.error ?: b.error ?: c.error)
    }

    @Test
    fun aWatcherTakesTheSeatOfAPlayerWhoStoppedPlaying() {
        val h = host()
        h.idleLimit = 60
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g1 = join(h, "Loser")
        val b = FakeGame(g1).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g1.requestSeat()
        waitFor("seated") { a.epoch == 2 && g1.localPlayer == 1 }
        val g2 = join(h, "Next")
        val c = FakeGame(g2).apply { play() }
        waitFor("watching") { c.epoch == 2 }

        // Everyone's pressing: no seat to take.
        g2.requestSeat()
        waitFor("told") { c.news.any { "All seats are in use" in it } }
        assertEquals(2, a.epoch)

        // The loser stops pressing anything: their seat goes to the next one.
        b.idle = true
        Thread.sleep(2_000)
        g2.requestSeat()
        waitFor("swapped") { a.epoch == 3 && b.epoch == 3 && c.epoch == 3 }
        assertEquals(1, g2.localPlayer)
        assertTrue(g1.watching)
        waitFor("told the loser") { b.news.any { "You were idle" in it } }
        waitFor("frames") { a.framesOf(1) >= 60 && b.framesOf(1) >= 60 }
        assertTrue(a.matches(1, c, 60) && b.matches(0, a, 60) && b.matches(1, c, 60))
    }

    @Test
    fun aPlayerCanGiveUpTheSeatToWatch() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && g.localPlayer == 1 }
        g.requestWatch()
        waitFor("watching again") { a.epoch == 3 && b.epoch == 3 && g.watching }
        assertEquals(setOf(0), h.seats.keys)
        assertTrue(a.news.any { "Guest is watching now" in it }, a.news.toString())
        // The host can too.
        h.requestWatch()
        waitFor("host watching") { a.epoch == 4 && h.watching && h.seats.isEmpty() }
    }

    @Test
    fun aDroppedPlayersSeatIsHeldAndTheyGetItBack() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Rafi", device = "phone-rafi")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && g.localPlayer == 1 }
        val w = join(h, "Watcher", device = "phone-w")
        val c = FakeGame(w).apply { play() }
        waitFor("watching") { c.epoch == 2 && h.watcherCount == 1 }

        // Wi-Fi drops: no goodbye. The seat is held, not free.
        b.running = false
        g.vanish()
        waitFor("held") { h.held.keys == setOf(1) && w.held.keys == setOf(1) }
        assertEquals(setOf(0), h.seats.keys)
        assertTrue(w.holdEnds(h.held[1]!!.id) > System.currentTimeMillis())
        // A watcher can't take it, and is told why.
        w.requestSeat()
        waitFor("told") { c.news.any { "held for Rafi" in it } }
        assertTrue(w.watching)
        // The host plays on meanwhile.
        val before = a.emulated
        waitFor("host plays on") { a.emulated > before + 20 }

        // Rafi's phone comes back: straight into player 2, no COIN needed.
        val g2 = join(h, "Rafi", device = "phone-rafi")
        val b2 = FakeGame(g2).apply { play() }
        waitFor("back") { g2.localPlayer == 1 && b2.epoch == a.epoch && h.held.isEmpty() }
        waitFor("told back") { a.news.any { "Rafi is back as player 2" in it } }
        waitFor("frames") { a.framesOf(1) >= 60 && b2.framesOf(0) >= 60 }
        assertTrue(a.matches(1, b2, 60) && b2.matches(0, a, 60))
    }

    @Test
    fun aHeldSeatGoesFreeWhenTheHoldEndsOrTheHostFreesIt() {
        val h = host("dino", seats = 3)
        h.holdMs = 3_000
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g1 = join(h, "One", "dino", device = "one")
        val g2 = join(h, "Two", "dino", device = "two")
        val b = FakeGame(g1).apply { play() }
        val c = FakeGame(g2).apply { play() }
        waitFor("watching") { h.watcherCount == 2 }
        g1.requestSeat()
        waitFor("one seated") { g1.localPlayer == 1 }
        g2.requestSeat()
        waitFor("two seated") { g2.localPlayer == 2 }

        // One drops and doesn't come back: free after the hold.
        b.running = false
        g1.vanish()
        waitFor("held") { h.held.keys == setOf(1) }
        waitFor("free after the hold") { h.held.isEmpty() && h.hasFreeSeat }
        waitFor("told") { a.news.any { "didn't come back" in it } }

        // Two drops; the host frees the seat at once.
        h.holdMs = 60_000
        c.running = false
        g2.vanish()
        waitFor("held") { h.held.keys == setOf(2) }
        h.freeHeldSeat(2)
        waitFor("freed") { h.held.isEmpty() && h.seats.keys == setOf(0) }

        // Leaving on purpose holds nothing.
        val g3 = join(h, "Three", "dino", device = "three")
        val d = FakeGame(g3).apply { play() }
        waitFor("watching") { d.epoch >= 1 && h.watcherCount == 1 }
        g3.requestSeat()
        waitFor("three seated") { g3.localPlayer >= 0 }
        d.running = false
        g3.close()
        waitFor("gone, not held") { h.seats.keys == setOf(0) && h.held.isEmpty() && h.members.size == 1 }
    }

    @Test
    fun aPlayerLeavingFreesTheSeatAndTheGameGoesOn() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && b.epoch == 2 && g.localPlayer == 1 }

        b.running = false
        g.close()
        waitFor("host alone again") { a.epoch == 3 && h.seats.keys == setOf(0) && h.members.size == 1 }
        val before = a.emulated
        waitFor("host plays on") { a.emulated > before + 20 }
        assertEquals(null, a.closedReason)
        assertTrue(a.news.any { "Guest left" in it }, a.news.toString())

        // The seat is free for someone else.
        val g2 = join(h, "Second")
        val c = FakeGame(g2).apply { play() }
        waitFor("watching") { c.epoch == 3 }
        g2.requestSeat()
        waitFor("seated with the newcomer") { a.epoch == 4 && c.epoch == 4 && g2.localPlayer == 1 }
        waitFor("frames") { a.framesOf(1) >= 60 && c.framesOf(0) >= 60 }
        assertTrue(a.matches(1, c, 60) && c.matches(0, a, 60))
    }

    @Test
    fun whenTheHostLeavesTheNextPhoneTakesTheRoomOver() {
        val h = host("dino", seats = 3)
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g1 = join(h, "Guest A", "dino")
        val b = FakeGame(g1).apply { play() }
        waitFor("A in") { b.epoch == 1 }
        g1.requestSeat()
        waitFor("A seated") { b.epoch == 2 && g1.localPlayer == 1 }
        val g2 = join(h, "Guest B", "dino")
        val c = FakeGame(g2).apply { play() }
        waitFor("B in") { c.epoch == 2 }
        g2.requestSeat()
        waitFor("all three seated") { a.epoch == 3 && b.epoch == 3 && c.epoch == 3 && g2.localPlayer == 2 }
        assertEquals(setOf(0, 1, 2), g2.seats.keys)

        // The host leaves: Guest A (the lowest member id left) takes over.
        a.running = false
        h.close()
        waitFor("A owns the room") { g1.isOwner && g2.owner == g1.localId }
        waitFor("seated without the host") { b.epoch == 4 && c.epoch == 4 && g2.seats.keys == setOf(1, 2) }
        // Everyone carries on from the new owner's game.
        assertTrue(byteArrayOf(7, g1.localId.toByte()).contentEquals(c.loaded))
        waitFor("frames between the two left") { b.framesOf(2) >= 60 && c.framesOf(1) >= 60 }
        assertTrue(b.matches(2, c, 60) && c.matches(1, b, 60))
        assertTrue(b.news.any { "You're the host now" in it }, b.news.toString())
        assertEquals(null, b.closedReason ?: c.closedReason)
        assertEquals(null, b.error ?: c.error)

        // And the new owner takes someone in, who takes the free seat.
        val g3 = join(g1, "Guest C", "dino")
        val d = FakeGame(g3).apply { play() }
        waitFor("C watching") { d.epoch == 4 }
        g3.requestSeat()
        waitFor("C seated") { b.epoch == 5 && c.epoch == 5 && d.epoch == 5 }
        assertEquals(0, g3.localPlayer)
        waitFor("C's frames") { b.framesOf(0) >= 30 && c.framesOf(0) >= 30 }
        assertTrue(c.matches(0, d, 30))
    }

    @Test
    fun aWatcherCanTakeTheRoomOverAndRelayThePlayers() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        // Member 1 watches; member 2 plays.
        val g1 = join(h, "Watcher")
        val w = FakeGame(g1).apply { play() }
        waitFor("watching") { w.epoch == 1 }
        val g2 = join(h, "Player")
        val p = FakeGame(g2).apply { play() }
        waitFor("in") { p.epoch == 1 }
        g2.requestSeat()
        waitFor("seated") { p.epoch == 2 && w.epoch == 2 && g2.localPlayer == 1 }

        a.running = false
        h.close()
        waitFor("the watcher owns the room") { g1.isOwner && g2.owner == g1.localId && p.epoch == 3 && w.epoch == 3 }
        assertTrue(g1.watching)
        assertEquals(setOf(1), g2.seats.keys)
        // It gets the player's inputs straight, and passes them on to a newcomer.
        waitFor("watching the player") { w.framesOf(1) >= 60 }
        assertTrue(w.matches(1, p, 60))
        val g3 = join(g1, "Late")
        val late = FakeGame(g3).apply { play() }
        waitFor("relayed") { late.framesOf(1) >= 30 }
        assertTrue(late.matches(1, p, 30))
        assertEquals(null, p.error ?: w.error ?: late.error)
    }

    @Test
    fun aWatcherThatFellBehindGetsTheGameAgainAlone() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 && b.seatings == 1 }
        Thread.sleep(500)
        g.requestResync()
        waitFor("again") { b.seatings == 2 }
        assertEquals(1, a.seatings, "the host's game went on")
        waitFor("following") { b.framesOf(0) >= 30 }
        assertTrue(b.matches(0, a, 30))
    }

    @Test
    fun theWrongGameOrAFullRoomIsTurnedAway() {
        val h = host()
        FakeGame(h).play()
        waitFor("host seated") { h.epoch == 1 }
        val other = assertFailsWith<Refused> { join(h, "Other", rom = 9) }
        assertTrue("differs" in other.message!!, other.message)
        val game = assertFailsWith<Refused> { join(h, "Other", set = "kof98") }
        assertTrue("different game" in game.message!!, game.message)
        // Another emulator build: told to update.
        val built = Room.host("Host", "kof97", "Game", rom = 1, bios = 2, maxPlayers = 2, port = 0, beaconPort = -1, build = "aaaa").also { open += it }
        FakeGame(built).play()
        val build = assertFailsWith<Refused> {
            Room.join(localhost, port(built), "Old", "kof97", rom = 1, bios = 2, listenPort = 0, beaconPort = -1, build = "bbbb").also { open += it }
        }
        assertTrue("different app builds" in build.message!!, build.message)
        repeat(MAX_MEMBERS - 1) { join(h, "Watcher $it") }
        val full = assertFailsWith<Refused> { join(h, "One too many") }
        assertTrue("full" in full.message!!, full.message)
    }

    @Test
    fun aRestartStartsEveryoneFromTheStartState() {
        val h = host()
        h.startState = byteArrayOf(9, 9)
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && b.epoch == 2 }
        g.requestRematch()
        waitFor("restart") { a.epoch == 3 && b.epoch == 3 }
        assertTrue(byteArrayOf(9, 9).contentEquals(a.loaded))
        assertTrue(byteArrayOf(9, 9).contentEquals(b.loaded))
    }

    @Test
    fun playersGoingOutOfStepAreAllPutBackFromTheHostsGame() {
        val h = host()
        val a = FakeGame(h).apply { play() }
        waitFor("host seated") { a.epoch == 1 }
        val g = join(h, "Guest")
        val b = FakeGame(g).apply { play() }
        waitFor("watching") { b.epoch == 1 }
        g.requestSeat()
        waitFor("seated") { a.epoch == 2 && b.epoch == 2 }
        b.loaded = null
        // The two games' checksums for the same frame differ.
        h.reportSum(600, 1)
        g.reportSum(600, 2)
        waitFor("resynced") { a.epoch == 3 && b.epoch == 3 }
        // The guest took the host's game as it stood; nothing restarted.
        assertTrue(byteArrayOf(7, 0).contentEquals(b.loaded))
        // One difference, one resync.
        Thread.sleep(500)
        assertEquals(3, a.epoch)
    }

    @Test
    fun aProbeLearnsTheRoomsGame() {
        val h = Room.host("Host", "kf2k5uni", "KOF 2005", rom = 7, bios = 2, maxPlayers = 2, port = 0, beaconPort = -1).also { open += it }
        FakeGame(h).play()
        val b = probe(localhost, port(h))
        assertEquals("kf2k5uni", b.set)
        assertEquals("KOF 2005", b.title)
        assertEquals(h.key, b.key)
    }

    @Test
    fun relayedInputsSurviveTheWire() {
        val r = RelayedInputs(3, 1200, 0b101, arrayOf(intArrayOf(1, 2, 0xFFF), intArrayOf(7, 0, 9)), 1080, -5)
        val back = RelayedInputs.decode(r.encode())
        assertEquals(3, back.epoch)
        assertEquals(1200, back.start)
        assertEquals(0b101, back.seats)
        assertEquals(3, back.count)
        assertTrue(back.masks[0].contentEquals(intArrayOf(1, 2, 0xFFF)) && back.masks[1].contentEquals(intArrayOf(7, 0, 9)))
        assertEquals(1080, back.sumFrame)
        assertEquals(-5, back.sum)
    }

    @Test
    fun roomKeysHoldTheAddressAndPort() {
        fun v4(s: String) = InetAddress.getByName(s) as Inet4Address
        for ((ip, length) in listOf("192.168.43.1" to 6, "192.168.255.254" to 6, "172.20.10.1" to 6, "10.42.0.1" to 7, "8.8.4.4" to 10)) {
            for (port in listOf(TCP_PORT, TCP_PORT + 7)) {
                val key = roomKey(v4(ip), port)!!
                assertEquals(length, key.length, key) // with its dashes
                assertTrue(key.none { it in "01OI" }, key)
                assertEquals(v4(ip) to port, parseRoomKey(key))
                assertEquals(v4(ip) to port, parseRoomKey(" " + key.lowercase().replace("-", "") + " "))
            }
        }
        assertNull(roomKey(v4("192.168.43.1"), 1234))
        assertNull(parseRoomKey("hello"))
        assertNull(parseRoomKey("K7Q"))
        assertNull(parseRoomKey(""))
        assertEquals(v4("192.168.43.1") to TCP_PORT, parseRoomKey("192.168.43.1"))
        // Different hotspots, different keys.
        assertTrue(roomKey(v4("192.168.43.1"), TCP_PORT) != roomKey(v4("192.168.44.1"), TCP_PORT))
    }

    @Test
    fun statesPackSmallAndComeBackTheSame() {
        val state = ByteArray(300_000).also { for (i in 0 until it.size step 97) it[i] = (i % 251).toByte() }
        val packed = pack(state)
        assertTrue(packed.size < state.size / 10, "${packed.size}")
        assertTrue(state.contentEquals(unpack(packed)))
        assertEquals(0, unpack(pack(ByteArray(0))).size)
    }

    @Test
    fun beaconsAreHeard() {
        val heard = CountDownLatch(1)
        var got: Beacon? = null
        BeaconListener(port = 0) { b, _ -> got = b; heard.countDown() }.use { listener ->
            val field = BeaconListener::class.java.getDeclaredField("socket").apply { isAccessible = true }
            val port = (field.get(listener) as java.net.DatagramSocket).localPort
            val beacon = Beacon(PROTOCOL, "Host", "kof97", "KOF '97", 1, 7845, key = "K7Q-2M", watchers = 2)
            val bytes = encode(beacon)
            java.net.DatagramSocket().use { it.send(java.net.DatagramPacket(bytes, bytes.size, localhost, port)) }
            assertTrue(heard.await(5, TimeUnit.SECONDS))
            assertEquals(beacon, got)
        }
    }
}
