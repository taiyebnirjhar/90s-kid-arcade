package com.kofarcade.core.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// The room tests again, over a simulated internet instead of sockets on one
// machine: every way between two phones has its own latency (20-80 ms),
// jitter, and lost inputs. The room logic must not care how the phones
// reach each other.

private fun internet() = FakeNet { Path(latencyMs = Random.nextInt(20, 80), jitterMs = 20, loss = 0.02) }

class RoomOverInternetTest : RoomTest() {
    override val net = internet()
}

class ChaosOverInternetTest : ChaosTest() {
    override val net = internet()

    @Test
    fun aShortOutageBetweenTwoPhonesPassesWithoutAnyoneLeaving() {
        val host = phones.host("Host")
        waitFor("host in") { host.game?.epoch == 1 }
        val guest = phones.join("Rafi", host)
        waitFor("watching") { guest.game?.epoch == 1 }
        guest.room.requestSeat()
        waitFor("seated") { guest.room.localPlayer == 1 && host.game?.epoch == 2 }
        val epoch = host.room.epoch
        // The way between them goes for 2.5 s (under the 5 s that make a phone gone).
        val path = net.path(host.room.fakeHost!!, guest.room.fakeHost!!)
        path.down = true
        Thread.sleep(2_500)
        path.down = false
        settled(listOf(host, guest), players = setOf("Host", "Rafi"))
        if (host.room.epoch != epoch) fail("the room was seated again (game ${host.room.epoch}, was $epoch): ${host.news + guest.news}")
        assertTrue(guest.news.none { "lost" in it }, "${guest.news}")
    }
}

// And over WebRtcTransport, as internet rooms run: each pair of phones on
// its own (simulated) peer connection, set up through signaling.

private fun webRtc() = RtcNet(internet())

class RoomOverWebRtcTest : RoomTest() {
    override val net = webRtc()

    init {
        NetLog.echo = { println(it) }
    }
}

class ChaosOverWebRtcTest : ChaosTest() {
    override val net = webRtc()
}
