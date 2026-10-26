package com.kofarcade.core.net

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How smooth a game is over Wi-Fi, in simulated time (minutes of play in a
 * moment): phones whose screens tick at their own rates, packets with
 * Wi-Fi's latency, jitter, losses and hiccups (a phone's radio holding
 * packets back for a moment), and on each phone the game's own bookkeeping
 * (Lockstep, DelayTuner, TimeSync, frameLead) with the emulator's rollback
 * rules (LibretroDroid::stepNetplay). Counted: waits (a frame held on screen
 * to let a partner catch up), stalls (frames the game stood still at the
 * rollback limit), rollbacks and the frames replayed.
 */
class SmoothnessTest {
    private enum class Pacing { VSYNC, TIMER }

    // The old time sync: wait a frame whenever one measurement says two or more, at most every 20 frames.
    private enum class Sync { OLD, NEW }

    private class Screen(val hz: Double, val pacing: Pacing)

    private class Air(
        val baseMs: Double,
        val jitterMs: Double,
        val loss: Double = 0.0,
        // A hiccup every so many seconds (0: none), lasting up to so many ms.
        val hiccupEveryS: Double = 0.0,
        val hiccupMaxMs: Double = 0.0,
    )

    private class Result(
        val waits: IntArray,
        val stalls: IntArray,
        val rollbacks: IntArray,
        val replayed: IntArray,
        val delay: DoubleArray,
        val seconds: Double,
        val apart: Int,
    ) {
        fun perMinute(v: IntArray) = v.map { it * 60.0 / seconds }
        override fun toString() =
            "waits/min ${perMinute(waits).map { "%.1f".format(it) }}, stalled frames ${stalls.toList()}, " +
                "rollbacks/min ${perMinute(rollbacks).map { "%.0f".format(it) }}, replayed ${replayed.toList()}, " +
                "delay ${delay.map { "%.2f".format(it) }}, apart at the end $apart"
    }

    private fun play(
        screens: List<Screen>,
        air: Air,
        sync: Sync = Sync.NEW,
        maxRollback: Int = 12,
        seconds: Double = 120.0,
        seed: Long = 1,
    ): Result {
        val sim = Sim(screens, air, sync, maxRollback, seed)
        sim.run((seconds * 1e6).toLong())
        val r = Result(
            sim.phones.map { it.waits }.toIntArray(),
            sim.phones.map { it.stalls }.toIntArray(),
            sim.phones.map { it.rollbacks }.toIntArray(),
            sim.phones.map { it.replayed }.toIntArray(),
            sim.phones.map { it.delaySum.toDouble() / it.delayFrames }.toDoubleArray(),
            seconds - WARM_UP_S,
            sim.phones.maxOf { it.netFrame } - sim.phones.minOf { it.netFrame },
        )
        println("  $sync, ${screens.map { "${it.hz} Hz ${it.pacing}" }}, rollback $maxRollback: $r")
        return r
    }

    @Test
    fun sameScreensOnAGoodConnectionHardlyEverWait() {
        val screens = listOf(Screen(60.0, Pacing.VSYNC), Screen(60.02, Pacing.VSYNC))
        val air = Air(baseMs = 2.0, jitterMs = 1.5)
        for (seed in 1L..3L) {
            val old = play(screens, air, Sync.OLD, seed = seed)
            val new = play(screens, air, Sync.NEW, seed = seed)
            assertTrue(new.perMinute(new.waits).all { it <= 2.0 }, "waits: $new")
            assertTrue(new.waits.sum() < old.waits.sum(), "no better than before: $old / $new")
            assertTrue(new.stalls.all { it == 0 }, "stalls: $new")
            assertTrue(new.apart <= 2, "apart: $new")
            assertTrue(new.delay.all { it < 1.2 }, "delay: $new")
        }
    }

    @Test
    fun aPhoneWithA120HzScreenKeepsInStepWithA60HzOne() {
        val air = Air(baseMs = 2.0, jitterMs = 1.5)
        // Before: the 120 Hz phone ran at the game's own 59.19 frames a second, the other at its screen's 60.
        val before = play(listOf(Screen(60.0, Pacing.VSYNC), Screen(59.19, Pacing.TIMER)), air, Sync.OLD)
        // Now: in a room, every phone runs at 60.
        val now = play(listOf(Screen(60.0, Pacing.VSYNC), Screen(60.0, Pacing.TIMER)), air)
        assertTrue(now.perMinute(now.waits).all { it <= 2.0 }, "waits: $now")
        assertTrue(now.waits.sum() * 10 < before.waits.sum(), "before $before, now $now")
        assertTrue(now.stalls.all { it == 0 }, "stalls: $now")
    }

    @Test
    fun wiFiHiccupsDontStopTheGame() {
        val screens = listOf(Screen(60.0, Pacing.VSYNC), Screen(60.01, Pacing.VSYNC))
        // A router's Wi-Fi: busier air, a little loss, and now and then a
        // phone's radio holding packets back for up to a fifth of a second.
        val air = Air(baseMs = 4.0, jitterMs = 4.0, loss = 0.01, hiccupEveryS = 6.0, hiccupMaxMs = 180.0)
        for (seed in 1L..3L) {
            val before = play(screens, air, Sync.OLD, maxRollback = 8, seed = seed)
            val now = play(screens, air, Sync.NEW, maxRollback = 12, seed = seed)
            assertTrue(now.stalls.all { it == 0 }, "stalls: $now")
            assertTrue(now.perMinute(now.waits).all { it <= 2.0 }, "waits: $now")
            assertTrue(now.waits.sum() * 10 < before.waits.sum(), "before $before, now $now")
        }
    }

    @Test
    fun longerHiccupsStopTheGameLess() {
        val screens = listOf(Screen(60.0, Pacing.VSYNC), Screen(60.01, Pacing.VSYNC))
        val air = Air(baseMs = 4.0, jitterMs = 4.0, loss = 0.01, hiccupEveryS = 5.0, hiccupMaxMs = 320.0)
        for (seed in 1L..3L) {
            // Guessing up to 12 frames (a fifth of a second) rather than 8.
            val before = play(screens, air, maxRollback = 8, seed = seed)
            val now = play(screens, air, maxRollback = 12, seed = seed)
            assertTrue(now.stalls.sum() < before.stalls.sum(), "before $before, now $now")
        }
    }

    @Test
    fun aPhoneThatCantKeepUpIsFollowed() {
        // One phone manages only 56 frames a second: the other must wait for
        // it, 4 frames a second, rather than run ahead to the rollback limit.
        val screens = listOf(Screen(60.0, Pacing.VSYNC), Screen(56.0, Pacing.VSYNC))
        val air = Air(baseMs = 3.0, jitterMs = 3.0, loss = 0.01)
        for (seed in 1L..2L) {
            val old = play(screens, air, Sync.OLD, seed = seed)
            val now = play(screens, air, seed = seed)
            assertTrue(now.stalls.sum() < 60 * now.seconds / 60, "stalls: $now")
            assertTrue(now.apart <= 4, "apart: $now")
            assertTrue(now.rollbacks.sum() <= old.rollbacks.sum() * 2 + 10, "before $old, now $now")
        }
    }

    @Test
    fun threePlayersStayLevel() {
        val screens = listOf(Screen(60.0, Pacing.VSYNC), Screen(60.03, Pacing.VSYNC), Screen(60.0, Pacing.TIMER))
        val air = Air(baseMs = 3.0, jitterMs = 3.0, loss = 0.01, hiccupEveryS = 10.0, hiccupMaxMs = 120.0)
        for (seed in 1L..2L) {
            val now = play(screens, air, seed = seed)
            assertTrue(now.perMinute(now.waits).all { it <= 3.0 }, "waits: $now")
            assertTrue(now.stalls.all { it == 0 }, "stalls: $now")
            assertTrue(now.apart <= 2, "apart: $now")
        }
    }

    // ----- The simulation -----

    private class Packet(val from: Int, val start: Int, val masks: IntArray, val ack: Int, val advantage: Int, val delay: Int)

    private inner class Sim(screens: List<Screen>, val air: Air, val sync: Sync, val maxRollback: Int, seed: Long) {
        val random = Random(seed)
        var now = 0L
        private var seq = 0L
        private val events = PriorityQueue<Triple<Long, Long, () -> Unit>>(compareBy({ it.first }, { it.second }))
        val players = screens.size
        val phones = screens.mapIndexed { i, s -> Phone(i, s) }

        // Each direction's hiccups: until when its packets are held back.
        private val heldUntil = HashMap<Pair<Int, Int>, Long>()

        fun at(t: Long, action: () -> Unit) {
            events += Triple(t, seq++, action)
        }

        fun run(until: Long) {
            for (p in phones) {
                p.start(random.nextDouble() * p.periodUs)
                at(random.nextLong(0, 4_000)) { p.sendLoop() }
            }
            if (air.hiccupEveryS > 0) scheduleHiccup()
            while (events.isNotEmpty()) {
                val (t, _, action) = events.poll()
                if (t > until) break
                now = t
                action()
            }
        }

        private fun scheduleHiccup() {
            val inUs = (air.hiccupEveryS * 1e6 * (0.5 + random.nextDouble())).toLong()
            at(now + inUs) {
                val phone = random.nextInt(players)
                val lasts = (air.hiccupMaxMs * 1000 * (0.2 + 0.8 * random.nextDouble())).toLong()
                // A phone's radio: both ways at once.
                for (other in 0 until players) if (other != phone) {
                    heldUntil[phone to other] = now + lasts
                    heldUntil[other to phone] = now + lasts
                }
                scheduleHiccup()
            }
        }

        fun send(from: Int, to: Int, packet: Packet) {
            if (random.nextDouble() < air.loss) return
            val jitter = -ln(1 - random.nextDouble()) * air.jitterMs
            var arrives = now + ((air.baseMs + jitter) * 1000).toLong()
            val held = heldUntil[from to to] ?: 0L
            if (held > now) arrives = maxOf(arrives, held + random.nextLong(0, 3_000))
            at(arrives) { phones[to].receive(packet) }
        }

        inner class Phone(val id: Int, screen: Screen) {
            val pacing = screen.pacing
            val periodUs = 1e6 / screen.hz
            private val partners = (0 until players).filter { it != id }
            val ls = Lockstep(1, id, players, partners)
            private val tuner = DelayTuner()
            private val timeSync = TimeSync()
            private val advantage = IntArray(MAX_IDS)
            private val theirAdvantage = IntArray(MAX_IDS)
            private val theirDelay = IntArray(MAX_IDS) { 1 }
            private val lastHeard = LongArray(MAX_IDS)
            private var lastSent = 0L
            private var frames = 0L
            private var lastWait = 0L

            // The emulator (LibretroDroid's netplay): the next frame to run, each
            // player's inputs, what each frame ran with, the first one run wrong.
            var netFrame = 0
            private val known = IntArray(players)
            private val inputs = Array(players) { HashMap<Int, Int>() }
            private val used = Array(players) { HashMap<Int, Int>() }
            private var firstWrong = Int.MAX_VALUE
            private var waitFrames = 0
            private var stretchLeft = 0
            private var stretchUs = 0.0

            var waits = 0
            var stalls = 0
            var rollbacks = 0
            var replayed = 0
            var delaySum = 0L
            var delayFrames = 0L
            private val counting get() = now > WARM_UP_S * 1e6

            // When the next frame is due: the screen's next tick, or the timer's.
            private var due = 0.0

            fun start(at: Double) {
                due = at
                at(due.toLong()) { tick() }
            }

            fun tick() {
                step()
                onNetFrame()
                due += periodUs
                var late = 0.0
                if (pacing == Pacing.TIMER) {
                    // A wait is spread over frames; a sleep wakes a little late,
                    // but the next is due on time (FPSSync keeps the schedule).
                    if (stretchLeft > 0) {
                        stretchLeft--
                        due += stretchUs
                    }
                    late = random.nextDouble(0.0, 600.0)
                }
                at((due + late).toLong()) { tick() }
            }

            private fun step() {
                if (firstWrong < netFrame) {
                    if (counting) {
                        rollbacks++
                        replayed += netFrame - firstWrong
                    }
                    for (f in firstWrong until netFrame) simulate(f)
                }
                firstWrong = Int.MAX_VALUE
                if (waitFrames > 0) {
                    waitFrames--
                    return
                }
                val remoteKnown = partners.minOf { known[it] }
                if (netFrame < known[id] && netFrame < remoteKnown + maxRollback) {
                    simulate(netFrame)
                    netFrame++
                } else if (counting) {
                    stalls++
                }
            }

            private fun simulate(f: Int) {
                for (p in partners) {
                    used[p][f] = inputs[p][f] ?: inputs[p][known[p] - 1] ?: 0
                    used[p].remove(f - 64)
                }
            }

            private fun onNetFrame() {
                frames++
                val (start, masks) = ls.sampleUpTo(netFrame) { pressed(id, ls.nextLocal) }
                masks.forEachIndexed { i, m -> inputs[id][start + i] = m }
                known[id] += masks.size
                ls.delay = tuner.update(ls.delay, partners.maxOf { theirAdvantage[it] })
                if (counting) {
                    delaySum += ls.delay
                    delayFrames++
                }
                if (masks.isNotEmpty()) sendAll()
                // As Room does: after sending, so the packets carry the last frame's (it cancels out).
                for (p in partners) advantage[p] = netFrame - ls.known(p)
                fun lead(p: Int) = frameLead(advantage[p], ls.delay, theirAdvantage[p], theirDelay[p])
                val wait = when (sync) {
                    Sync.OLD -> if (partners.maxOf(::lead) >= 2 && frames - lastWait > 20) 1.also { lastWait = frames } else 0
                    Sync.NEW -> timeSync.update(partners) { p -> lead(p).takeIf { now - lastHeard[p] < FRESH_US } }
                }
                if (wait > 0) {
                    if (counting) waits++
                    if (pacing == Pacing.VSYNC) {
                        waitFrames = maxOf(waitFrames, wait)
                    } else {
                        stretchLeft = STRETCH_FRAMES
                        stretchUs = periodUs * wait / STRETCH_FRAMES
                    }
                }
            }

            fun sendLoop() {
                val pending = partners.any { ls.outgoing(it, 1).second.isNotEmpty() }
                val idle = now - lastSent
                if ((pending && idle > 12_000) || idle > 100_000) sendAll()
                at(now + 4_000) { sendLoop() }
            }

            private fun sendAll() {
                for (p in partners) {
                    val (start, masks) = ls.outgoing(p, InputPacket.MAX_MASKS)
                    send(id, p, Packet(id, start, masks, ls.known(p), advantage[p], ls.delay))
                }
                lastSent = now
            }

            fun receive(packet: Packet) {
                val from = packet.from
                lastHeard[from] = now
                theirAdvantage[from] = packet.advantage
                theirDelay[from] = packet.delay
                ls.receive(from, packet.start, packet.masks, packet.ack)
                val (start, masks) = ls.takeRemote(from)
                masks.forEachIndexed { i, m ->
                    val f = start + i
                    if (f != known[from]) return@forEachIndexed
                    inputs[from][f] = m
                    inputs[from].remove(f - 64)
                    if (f < netFrame && used[from][f] != m) firstWrong = minOf(firstWrong, f)
                    known[from]++
                }
            }
        }
    }

    private companion object {
        const val WARM_UP_S = 5.0
        const val FRESH_US = 50_000L
        const val STRETCH_FRAMES = 20

        // What a player presses on frame f: a new move every few frames.
        fun pressed(player: Int, f: Int): Int {
            val segment = f / 7
            var h = segment * 0x9E3779B1.toInt() + player * 0x85EBCA6B.toInt()
            h = h xor (h ushr 15)
            h *= 0x2C1B3C6D
            h = h xor (h ushr 12)
            return if (h and 3 == 0) 0 else h and 0xFFF
        }
    }
}
