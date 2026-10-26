package com.kofarcade.core.net

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// Phones for network tests that behave like the app's game screen: a fake
// rollback emulator that samples inputs every frame, freezes while a seat is
// held, asks to be put back in step when stuck, and, having lost the room,
// tries to get back in every couple of seconds for a minute, as
// GameActivity.reconnect does.

/** What a phone presses on frame [f] (so every phone's inputs can be told apart). */
fun pressedBy(f: Int, id: Int) = (f * 13 + id) and 0xFFF or 1

class Phones(private val log: (String) -> Unit = {}) {
    val all = CopyOnWriteArrayList<Phone>()
    private val start = System.nanoTime()

    fun note(text: String) = log("%6.2f s  %s".format((System.nanoTime() - start) / 1e9, text))

    fun host(name: String, set: String = "kof97", seats: Int = 2, timings: (Room) -> Unit = {}): Phone {
        val p = Phone(this, name, timings)
        p.attach(Room.host(name, set, "Game", rom = 1, bios = 2, maxPlayers = seats, port = 0, beaconPort = -1, device = name, faults = p.faults))
        all += p
        return p
    }

    fun join(name: String, to: Phone, set: String = "kof97", timings: (Room) -> Unit = {}): Phone {
        val p = Phone(this, name, timings)
        p.attach(
            Room.join(
                InetAddress.getLoopbackAddress(), to.room.port, name, set, rom = 1, bios = 2,
                listenPort = 0, beaconPort = -1, device = name, faults = p.faults,
            ),
        )
        all += p
        return p
    }

    fun closeAll() = all.forEach { it.shutDown() }
}

/** For tests: the TCP port a room listens on. */
val Room.port: Int
    get() {
        val f = Room::class.java.getDeclaredField("server").apply { isAccessible = true }
        return (f.get(this) as java.net.ServerSocket).localPort
    }

class Phone(private val phones: Phones, val name: String, private val timings: (Room) -> Unit) {
    /** This phone's network: every room it's in shares it. */
    val faults = Faults()

    @Volatile lateinit var room: Room
        private set
    @Volatile var game: Game? = null
        private set

    /** Out of multiplayer for good (the app would show "Multiplayer game ended"), and why. */
    @Volatile var ended: String? = null
    @Volatile var reconnecting = false
    @Volatile private var shut = false
    val errors = CopyOnWriteArrayList<String>()
    val news = CopyOnWriteArrayList<String>()

    fun attach(r: Room) {
        timings(r)
        room = r
        game = Game(r).also { it.play() }
    }

    fun shutDown() {
        shut = true
        game?.running = false
        runCatching { room.close() }
    }

    // The game screen's reconnect: every 2 s for 65 s, then it gives up.
    private fun lost(from: Room, reason: String) {
        if (shut || from !== room || reconnecting) return
        reconnecting = true
        phones.note("$name lost the room: $reason")
        game?.running = false
        thread(isDaemon = true, name = "rejoin-$name") {
            val until = System.currentTimeMillis() + 65_000
            while (!shut && System.currentTimeMillis() < until) {
                val back = runCatching { from.rejoin() }.getOrNull()
                if (back != null) {
                    phones.note("$name back in (member ${back.localId})")
                    attach(back)
                    reconnecting = false
                    return@thread
                }
                Thread.sleep(2_000)
            }
            if (!shut) ended = "couldn't get back in: $reason"
            reconnecting = false
        }
    }

    inner class Game(val room: Room) : RoomListener {
        private val lock = Any()
        @Volatile var epoch = -1
        @Volatile var startAt = 0
        val local = ArrayList<Int>()
        val remotes = HashMap<Int, ArrayList<Int>>()
        @Volatile var emulated = 0
        @Volatile var running = true

        override fun captureState(): ByteArray = byteArrayOf(7, room.localId.toByte())

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
        }

        override fun onRemote(player: Int, start: Int, masks: IntArray) = synchronized(lock) {
            val got = remotes.getOrPut(player) { ArrayList() }
            if (startAt + got.size != start) errors += "$name: player $player's inputs from $start, expected ${startAt + got.size}"
            got += masks.toList()
            Unit
        }

        override fun onDesync(frame: Int) {
            errors += "$name: desync at $frame"
        }

        override fun onClosed(reason: String) {
            if (room === this@Phone.room && !shut) {
                phones.note("$name closed: $reason")
                ended = reason
            }
        }

        override fun onLost(reason: String) = lost(room, reason)

        override fun onNews(message: String) {
            news += message
            phones.note("$name: $message")
        }

        fun play() {
            room.start(this)
            thread(isDaemon = true, name = "game-$name") {
                var lastProgress = System.nanoTime()
                var lastEmulated = -1
                var lastAsk = 0L
                while (running && !shut) {
                    // The app waits 5 s for a player who dropped out, then plays on.
                    val frozen = room.held.values.any { room.heldFor(it.id) < 5_000 }
                    if (!frozen) {
                        synchronized(lock) {
                            val known = startAt.toLong() + (remotes.values.minOfOrNull { it.size } ?: Int.MAX_VALUE / 2)
                            if (room.watching) {
                                if (emulated < known) emulated++
                            } else if (emulated < local.size && emulated.toLong() < known + 8) {
                                emulated++
                            }
                        }
                        room.sample(
                            emulated = { emulated },
                            input = { pressedBy(room.lockstep.nextLocal, room.localId) },
                            push = { _, masks -> synchronized(lock) { local += masks.toList() } },
                        )
                        room.updateAdvantage(emulated)
                    }
                    // Stuck 3 seconds: put back in step (as the game screen asks).
                    val now = System.nanoTime()
                    if (emulated != lastEmulated || frozen || room.partnerPaused) {
                        lastEmulated = emulated
                        lastProgress = now
                    } else if (now - lastProgress > 3_000_000_000L && now - lastAsk > 5_000_000_000L) {
                        lastAsk = now
                        thread(isDaemon = true) { runCatching { room.requestResync() } }
                    }
                    Thread.sleep(16)
                }
            }
        }

        fun framesOf(seat: Int) = synchronized(lock) { remotes[seat]?.size ?: 0 }

        /** Every input this phone has of [seat] is what that seat's player pressed. */
        fun inputsRight(seat: Int, presser: Int): Boolean = synchronized(lock) {
            remotes[seat].orEmpty().withIndex().all { (i, m) -> m == pressedBy(startAt + i, presser) }
        }
    }
}
