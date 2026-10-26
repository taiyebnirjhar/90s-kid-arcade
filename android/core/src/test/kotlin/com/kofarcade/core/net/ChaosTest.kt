package com.kofarcade.core.net

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Rooms on bad Wi-Fi, with the app's own timings: for a while, phones drop
 * out (for a moment or for seconds, their connections stalling or destroyed
 * as Android does), packets go missing, arrive late or out of order. Then
 * the network settles, and every phone must be back in the one room, in its
 * own seat, the games in step and every input where it belongs.
 *
 * KOF_SOAK_SEEDS (e.g. "1,2,3") and KOF_SOAK_SECONDS run longer soaks.
 */
class ChaosTest {
    private val phones = Phones { println(it) }

    @AfterTest
    fun cleanUp() = phones.closeAll()

    private val seeds: List<Long> =
        System.getenv("KOF_SOAK_SEEDS")?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: listOf(1L)
    private val chaosMs: Long = (System.getenv("KOF_SOAK_SECONDS")?.toLongOrNull() ?: 45L) * 1000

    @Test
    fun twoPlayersComeThroughBadWiFi() {
        for (seed in seeds) {
            val host = phones.host("Host")
            waitFor("host in") { host.game?.epoch == 1 }
            val guest = phones.join("Rafi", host)
            waitFor("watching") { guest.game?.epoch == 1 }
            guest.room.requestSeat()
            waitFor("seated") { guest.room.localPlayer == 1 && host.game?.epoch == 2 }
            chaos(seed, listOf(host, guest))
            settled(listOf(host, guest), players = setOf("Host", "Rafi"))
            phones.closeAll()
            phones.all.clear()
        }
    }

    @Test
    fun fourPhonesComeThroughBadWiFi() {
        for (seed in seeds) {
            val host = phones.host("Host", "dino", seats = 3)
            waitFor("host in") { host.game?.epoch == 1 }
            val a = phones.join("Rafi", host, "dino")
            val b = phones.join("Mim", host, "dino")
            val w = phones.join("Tanu", host, "dino")
            waitFor("watching") { host.room.watcherCount == 3 }
            a.room.requestSeat()
            waitFor("Rafi seated") { a.room.localPlayer >= 0 }
            b.room.requestSeat()
            waitFor("Mim seated") { b.room.localPlayer >= 0 && host.room.seats.size == 3 }
            chaos(seed, listOf(host, a, b, w))
            settled(listOf(host, a, b, w), players = setOf("Host", "Rafi", "Mim"))
            phones.closeAll()
            phones.all.clear()
        }
    }

    @Test
    fun theOthersPlayOnWhileAPlayerIsAwayAndItGetsItsSeatBack() {
        val host = phones.host("Host", "dino", seats = 3)
        waitFor("host in") { host.game?.epoch == 1 }
        val a = phones.join("Rafi", host, "dino")
        val b = phones.join("Mim", host, "dino")
        waitFor("watching") { host.room.watcherCount == 2 }
        a.room.requestSeat()
        waitFor("Rafi seated") { a.room.localPlayer >= 0 }
        b.room.requestSeat()
        waitFor("Mim seated") { b.room.localPlayer >= 0 && host.room.seats.size == 3 }
        val mimSeat = b.room.localPlayer

        // Mim's Wi-Fi goes for 15 s (Android drops its connections).
        b.faults.tcp = TcpCut.RESET
        b.room.offline = true
        waitFor("held") { host.room.held.isNotEmpty() }
        // After a short wait, the other two play on together.
        Thread.sleep(6_000)
        val hostFrames = host.game!!.framesOf(a.room.localPlayer)
        val rafiFrames = a.game!!.framesOf(host.room.localPlayer)
        Thread.sleep(3_000)
        if (host.game!!.framesOf(a.room.localPlayer) < hostFrames + 60 || a.game!!.framesOf(host.room.localPlayer) < rafiFrames + 60) {
            fail("the others stopped while Mim was away")
        }
        Thread.sleep(6_000)
        b.faults.cut = false
        settled(listOf(host, a, b), players = setOf("Host", "Rafi", "Mim"))
        if (b.room.localPlayer != mimSeat) fail("Mim came back in seat ${b.room.localPlayer}, not $mimSeat")
    }

    @Test
    fun aPhoneWhoseOwnConnectionBreaksGoesBackToTheHostWithoutATakeover() {
        val host = phones.host("Host", "dino", seats = 3)
        waitFor("host in") { host.game?.epoch == 1 }
        val a = phones.join("Rafi", host, "dino")
        val b = phones.join("Mim", host, "dino")
        waitFor("watching") { host.room.watcherCount == 2 }
        a.room.requestSeat()
        waitFor("Rafi seated") { a.room.localPlayer >= 0 }
        b.room.requestSeat()
        waitFor("Mim seated") { b.room.localPlayer >= 0 && host.room.seats.size == 3 }
        val mimRoom = b.room

        // Rafi (next in line to host) loses only its own connection to the host.
        a.room.breakOwnerLine()
        settled(listOf(host, a, b), players = setOf("Host", "Rafi", "Mim"))
        if (!host.room.isOwner) fail("the host lost the room")
        if (b.room !== mimRoom) fail("Mim was thrown out too")
        if (b.news.any { "host now" in it }) fail("Mim followed a takeover: ${b.news}")
    }

    // Bad Wi-Fi for [chaosMs], then good.
    private fun chaos(seed: Long, all: List<Phone>) {
        val random = Random(seed)
        phones.note("chaos, seed $seed, ${chaosMs / 1000} s")
        val end = System.currentTimeMillis() + chaosMs
        val cuts = ArrayList<Thread>()
        while (System.currentTimeMillis() < end) {
            for (p in all) {
                p.faults.udpLoss = random.nextDouble() * 0.08
                p.faults.udpDelayMs = random.nextInt(0, 30)
                p.faults.udpJitterMs = random.nextInt(0, 40)
            }
            val roll = random.nextDouble()
            val victims = when {
                roll < 0.6 -> listOf(all.random(random))
                roll < 0.75 -> all.shuffled(random).take(2)
                roll < 0.8 -> all
                else -> emptyList()
            }
            for (p in victims) {
                val ms = if (random.nextBoolean()) random.nextLong(300, 3_000) else random.nextLong(3_000, 12_000)
                val mode = if (random.nextBoolean()) TcpCut.STALL else TcpCut.RESET
                cuts += kotlin.concurrent.thread(isDaemon = true) {
                    if (p.faults.cut) return@thread
                    phones.note("${p.name} cut for $ms ms ($mode)")
                    p.faults.tcp = mode
                    p.room.offline = true
                    Thread.sleep(ms)
                    p.faults.cut = false
                    phones.note("${p.name} back")
                }
            }
            Thread.sleep(random.nextLong(1_000, 6_000))
        }
        cuts.forEach { it.join() }
        for (p in all) {
            p.faults.udpLoss = 0.0
            p.faults.udpDelayMs = 0
            p.faults.udpJitterMs = 0
            p.faults.cut = false
        }
        phones.note("calm")
    }

    // Everyone back in one room, the players in their seats, the games in step.
    private fun settled(all: List<Phone>, players: Set<String>) {
        var why = ""
        waitFor("settled", ms = 120_000, why = { why }) {
            why = problem(all, players) ?: return@waitFor true
            false
        }
        // And playing: everyone gets every seat's inputs, right.
        val from = all.associateWith { p -> p.room.seats.keys.associateWith { s -> p.game!!.framesOf(s) } }
        waitFor("playing", why = { problem(all, players) ?: "" }) {
            all.all { p -> p.room.seats.keys.filter { it != p.room.localPlayer }.all { s -> p.game!!.framesOf(s) >= (from[p]?.get(s) ?: 0) + 120 } }
        }
        for (p in all) {
            for ((seat, m) in p.room.seats) {
                if (seat == p.room.localPlayer) continue
                if (!p.game!!.inputsRight(seat, m.id)) fail("${p.name} has wrong inputs for seat $seat")
            }
        }
        val errors = all.flatMap { it.errors }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
        phones.note("settled and playing")
    }

    private fun problem(all: List<Phone>, players: Set<String>): String? {
        all.firstOrNull { it.ended != null }?.let { return "${it.name} ended: ${it.ended}" }
        all.firstOrNull { it.reconnecting }?.let { return "${it.name} still reconnecting" }
        val owners = all.filter { it.room.isOwner }
        if (owners.size != 1) return "owners: ${owners.map { it.name }}"
        val owner = owners.single().room
        for (p in all) {
            val r = p.room
            if (r.sessionNumber != owner.sessionNumber) return "${p.name} in another room"
            if (r.owner != owner.localId) return "${p.name} thinks the owner is ${r.owner}"
            if (r.epoch != owner.epoch || p.game?.epoch != owner.epoch) return "${p.name} at game ${r.epoch}/${p.game?.epoch}, owner at ${owner.epoch}"
        }
        val devices = owner.members.values.map { it.device }.sorted()
        if (devices != all.map { it.name }.sorted()) return "members $devices"
        if (owner.held.isNotEmpty()) return "held ${owner.held.values.map { it.name }}"
        val seated = owner.seats.values.map { it.device }.toSet()
        if (seated != players) return "seated $seated"
        return null
    }

    private fun waitFor(what: String, ms: Long = 20_000, why: () -> String = { "" }, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what: ${why()}")
            Thread.sleep(50)
        }
    }
}
