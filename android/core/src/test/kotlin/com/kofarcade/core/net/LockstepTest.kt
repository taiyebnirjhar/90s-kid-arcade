package com.kofarcade.core.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockstepTest {
    /**
     * One simulated phone with a rollback emulator: it runs a frame once it
     * has its own input, at most [maxRollback] frames past the partner's.
     */
    private class Phone(val player: Int, delay: Int, val maxRollback: Int) {
        val lockstep = Lockstep(delay, player)
        val sampled = ArrayList<Int>()
        val received = ArrayList<Int>()
        var emulated = 0

        fun input(frame: Int) = (frame * 7 + player * 3) % 1024

        fun tick() {
            if (emulated < sampled.size && emulated < received.size + maxRollback) emulated++
            var f = lockstep.nextLocal
            val (start, masks) = lockstep.sampleUpTo(emulated) { input(f++) }
            assertEquals(sampled.size, start)
            sampled += masks.toList()
            val (rs, rm) = lockstep.takeRemote()
            assertEquals(received.size, rs)
            received += rm.toList()
        }
    }

    @Test
    fun eachSideGetsThePartnersInputsInOrderDespiteLossAndReordering() {
        val rnd = Random(7)
        val host = Phone(0, delay = 1, maxRollback = 8)
        val guest = Phone(1, delay = 1, maxRollback = 8)
        data class Packet(val at: Int, val to: Phone, val start: Int, val masks: IntArray, val ack: Int)
        val wire = ArrayList<Packet>()
        for (tick in 0 until 3000) {
            for (p in listOf(host, guest)) p.tick()
            for ((from, to) in listOf(host to guest, guest to host)) {
                val (start, masks) = from.lockstep.outgoing()
                // 20% loss, 0-4 ticks of latency (so packets also arrive out of order).
                if (rnd.nextDouble() > 0.2) wire += Packet(tick + rnd.nextInt(5), to, start, masks, from.lockstep.remoteKnown)
            }
            val due = wire.filter { it.at <= tick }
            wire.removeAll(due)
            due.shuffled(rnd).forEach { it.to.lockstep.receive(it.start, it.masks, it.ack) }
        }
        assertTrue(host.emulated > 2500 && guest.emulated > 2500, "${host.emulated} / ${guest.emulated}")
        assertEquals(guest.sampled.take(guest.received.size.coerceAtMost(host.received.size)), host.received.take(host.received.size.coerceAtMost(guest.sampled.size)))
        assertEquals(guest.sampled.subList(0, host.received.size), host.received)
        assertEquals(host.sampled.subList(0, guest.received.size), guest.received)
        // Each frame's input is what that player had for that frame.
        host.received.forEachIndexed { f, m -> assertEquals(guest.input(f), m) }
    }

    @Test
    fun samplingStaysDelayFramesAhead() {
        val a = Lockstep(2, 0)
        val (start, masks) = a.sampleUpTo(0) { 1 }
        assertEquals(0, start)
        assertEquals(3, masks.size) // frames 0, 1, 2
        assertEquals(0, a.sampleUpTo(0) { 1 }.second.size)
        assertEquals(1, a.sampleUpTo(1) { 1 }.second.size)
    }

    @Test
    fun remoteInputsComeOutInOrderOnlyWithoutGaps() {
        val a = Lockstep(1, 0)
        a.receive(2, intArrayOf(7), ack = 0)
        assertEquals(0, a.takeRemote().second.size) // frames 0 and 1 missing
        a.receive(0, intArrayOf(5, 6), ack = 0)
        val (start, masks) = a.takeRemote()
        assertEquals(0, start)
        assertEquals(listOf(5, 6, 7), masks.toList())
        a.receive(1, intArrayOf(6, 7, 8), ack = 0) // a resend overlapping what we have
        assertEquals(3 to listOf(8), a.takeRemote().let { it.first to it.second.toList() })
    }

    @Test
    fun acknowledgedInputsAreNotResent() {
        val a = Lockstep(3, 1)
        a.sampleUpTo(0) { 9 }
        assertEquals(0 to IntArray(4) { 9 }.toList(), a.outgoing().let { it.first to it.second.toList() })
        a.receive(0, IntArray(0), ack = 3)
        assertEquals(3, a.outgoing().first)
        assertEquals(1, a.outgoing().second.size)
    }

    @Test
    fun packetsRoundTrip() {
        val p = InputPacket(42, 10, 7, intArrayOf(0, 1, 0xFFFF, 256), 120, -5L, advantage = -3, epoch = 2, delay = 3)
        assertEquals(p, InputPacket.decode(p.encode()))
        assertEquals(null, InputPacket.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun theDelayCanChangeMidGameWithoutGapsOrRepeats() {
        val a = Lockstep(1, 0)
        var next = 0
        val frames = ArrayList<Int>()
        fun sample(emulated: Int) = a.sampleUpTo(emulated) { next++ }.also { (start, masks) ->
            assertEquals(frames.size, start)
            frames += masks.toList()
        }
        sample(0) // frames 0, 1
        a.delay = 3
        assertEquals(3, sample(1).second.size) // up to 1 + 3
        a.delay = 1
        assertEquals(0, sample(2).second.size) // already have frame 3
        assertEquals(1, sample(4).second.size)
        assertEquals((0..5).toList(), frames)
    }

    /** The partner's advantage with a network [latency] in frames, as [DelayTuner] sees it. */
    private fun tune(latency: Double, start: Int, seconds: Int): List<Int> {
        val tuner = DelayTuner()
        var delay = start
        val seen = ArrayList<Int>()
        repeat(seconds * 60) {
            val jitter = if (it % 3 == 0) 1 else 0
            delay = tuner.update(delay, Math.round(latency - delay).toInt() + jitter)
            seen += delay
        }
        return seen
    }

    @Test
    fun aSlowConnectionGetsMoreDelayAndAFastOneLess() {
        assertEquals(3, tune(latency = 4.5, start = 1, seconds = 20).last())
        assertEquals(1, tune(latency = 0.0, start = 3, seconds = 20).last())
        // A good connection stays at one frame.
        assertEquals(setOf(1), tune(latency = 0.5, start = 1, seconds = 20).toSet())
    }

    @Test
    fun theDelaySettlesInsteadOfSwinging() {
        for (latency in listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5)) {
            val seen = tune(latency, start = 1, seconds = 60)
            val changes = seen.zipWithNext().count { (a, b) -> a != b }
            assertTrue(changes <= 2, "latency $latency: $changes changes")
        }
    }
}
