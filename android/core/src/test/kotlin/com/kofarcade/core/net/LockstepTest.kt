package com.kofarcade.core.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockstepTest {
    /** One simulated phone: an emulator that runs a frame only when both inputs are in. */
    private class Phone(val player: Int, delay: Int) {
        val lockstep = Lockstep(delay, player)
        val ran = ArrayList<FrameInputs>()
        val emulated get() = ran.size

        // Scripted input: a different pattern per player, by frame.
        fun input(frame: Int) = (frame * 7 + player * 3) % 1024

        fun tick() {
            var f = lockstep.nextLocal
            lockstep.sampleUpTo(emulated) { input(f++) }
            ran += lockstep.drain()
        }
    }

    @Test
    fun bothSidesRunTheSameInputsDespiteLossAndReordering() {
        val rnd = Random(7)
        val host = Phone(0, delay = 3)
        val guest = Phone(1, delay = 3)
        // Packets in flight: (arrival tick, to, start, masks, ack)
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
        val n = minOf(host.ran.size, guest.ran.size)
        assertTrue(n > 2000, "only $n frames ran")
        assertEquals(host.ran.take(n), guest.ran.take(n))
        // Each frame carries each player's own input for that frame.
        host.ran.take(n).forEach {
            assertEquals(host.input(it.frame), it.p1)
            assertEquals(guest.input(it.frame), it.p2)
        }
        assertEquals((0 until n).toList(), host.ran.take(n).map { it.frame })
    }

    @Test
    fun aPhoneWaitsForItsPartner() {
        val a = Lockstep(2, 0)
        a.sampleUpTo(0) { 1 }
        assertEquals(3, a.nextLocal) // frames 0, 1, 2 sampled ahead
        assertTrue(a.drain().isEmpty()) // nothing from the partner yet
        a.receive(0, intArrayOf(5, 6), ack = 0)
        assertEquals(listOf(FrameInputs(0, 1, 5), FrameInputs(1, 1, 6)), a.drain())
        assertEquals(2, a.remoteKnown)
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
        val p = InputPacket(42, 10, 7, intArrayOf(0, 1, 0xFFFF, 256), 120, -5L)
        assertEquals(p, InputPacket.decode(p.encode()))
        assertEquals(null, InputPacket.decode(byteArrayOf(1, 2, 3)))
    }
}
