package com.kofarcade.core.tournament

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TournamentTest {
    private val five = (1..5).associate { "$it" to "P$it" }

    private fun seeded(n: Int, bestOf: Int = 1) =
        Tournament(Bracket((1..n).map { "$it" }), (1..n).associate { "$it" to "P$it" }, "kof2002mp", bestOf)

    @Test
    fun startNeedsThreeToEight() {
        assertFailsWith<IllegalArgumentException> { Tournament.start(mapOf("1" to "a", "2" to "b"), "kof97", 1) }
        val t = Tournament.start(five, "kof97", 1)
        assertEquals(five.keys, t.bracket.players.toSet())
        assertNull(t.current)
    }

    @Test
    fun aWholeTournamentFromTheGame() {
        var t = seeded(5).callNext(1000)
        // 5 players: byes for seeds 1-3; the first match played is 4 vs 5.
        assertEquals("4" to "5", t.pair)
        assertEquals(1000, t.upAt)
        assertEquals("Quarter-final 2 of 4 · P4 vs P5", t.bar())
        var crowned: String? = null
        var guard = 0
        while (!t.over && guard++ < 10) {
            t = t.callNext(0)
            val (next, out) = t.gameWon(0, Tournament.How.GAME)
            val over = assertIs<Tournament.Outcome.MatchOver>(out)
            crowned = over.champion
            t = next
        }
        assertEquals("1", t.champion)
        assertEquals("1", crowned)
        assertEquals(4, t.results.size)
        assertTrue(t.results.all { it.how == Tournament.How.GAME })
    }

    @Test
    fun bestOfThreePlaysOnWithTheSameSeats() {
        var t = seeded(4, bestOf = 3).callNext(0)
        val pair = t.pair
        var (n, o) = t.gameWon(1, Tournament.How.GAME)
        assertEquals(Tournament.Outcome.NextGame, o)
        assertEquals(pair, n.pair)
        assertEquals("Semi-final 1 of 2 · P1 vs P4  0-1", n.bar())
        n.gameWon(0, Tournament.How.GAME).let { n = it.first; o = it.second }
        assertEquals(Tournament.Outcome.NextGame, o)
        val (done, end) = n.gameWon(1, Tournament.How.GAME)
        assertEquals("4", assertIs<Tournament.Outcome.MatchOver>(end).result.winner)
        assertNull(done.playing)
    }

    @Test
    fun playersSayAndTheHostSettlesADispute() {
        val t = seeded(4).callNext(0)
        val (a, b) = t.pair!!
        val (one, waiting) = t.report(a, a)
        assertEquals(Tournament.Outcome.Waiting, waiting)
        val (agreed, out) = one.report(b, a)
        assertEquals(Tournament.How.PLAYERS, assertIs<Tournament.Outcome.MatchOver>(out).result.how)
        assertEquals(a, agreed.results.last().winner)

        val (disputed, d) = one.report(b, b)
        assertEquals(Tournament.Outcome.Disputed, d)
        val (settled, s) = disputed.hostDecides(b)
        assertEquals(b, assertIs<Tournament.Outcome.MatchOver>(s).result.winner)
        assertEquals(Tournament.How.HOST, settled.results.last().how)
        assertFailsWith<IllegalArgumentException> { t.report("3", a) }
    }

    @Test
    fun aNoShowOrALeaverLoses() {
        val t = seeded(4).callNext(0)
        val (a, b) = t.pair!!
        val (n, o) = t.noShow(a)
        assertEquals(b, assertIs<Tournament.Outcome.MatchOver>(o).result.winner)
        assertEquals(Tournament.How.NO_SHOW, n.results.last().how)
        // Leaving between matches: their next match goes to the opponent.
        val (l, none) = n.left("2")
        assertNull(none)
        assertTrue("2" in l.bracket.left)
        // Leaving mid-match: the opponent goes through at once.
        val on = seeded(4).callNext(0)
        val (gone, out) = on.left(on.pair!!.second)
        assertEquals(on.pair!!.first, assertIs<Tournament.Outcome.MatchOver>(out).result.winner)
        assertTrue(on.pair!!.second in gone.bracket.left)
    }

    @Test
    fun survivesTheWire() {
        val t = seeded(6, bestOf = 3).callNext(5).gameWon(0, Tournament.How.GAME).first
        assertEquals(t, Json.decodeFromString(Tournament.serializer(), Json.encodeToString(Tournament.serializer(), t)))
    }
}
