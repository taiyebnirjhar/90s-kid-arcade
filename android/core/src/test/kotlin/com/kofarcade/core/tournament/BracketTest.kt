package com.kofarcade.core.tournament

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BracketTest {
    private fun players(n: Int) = (1..n).map { "p$it" }

    /** Plays every match to the end, the side named first winning. */
    private fun playOut(b: Bracket): Bracket {
        var x = b
        while (true) {
            val m = x.next ?: return x
            x = x.withResult(m.number, m.a!!)
        }
    }

    @Test
    fun eightPlayersPlaySevenMatchesQuarterFinalsFirst() {
        val b = Bracket(players(8))
        assertEquals(7, b.matches.size)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2), b.matches.map { it.round })
        assertEquals(7, b.matchesLeft)
        // Seed 1 meets seed 8; seeds 1 and 2 are in different halves.
        assertEquals("p1" to "p8", b.matches[0].a to b.matches[0].b)
        assertEquals("p2" to "p7", b.matches[2].a to b.matches[2].b)
        assertEquals(0, b.next!!.number)
        assertNull(b.champion)

        val done = playOut(b)
        assertEquals("p1", done.champion)
        assertEquals(0, done.matchesLeft)
        assertNull(done.next)
    }

    @Test
    fun theWinnerGoesOnToTheNextRound() {
        val b = Bracket(players(8)).withResult(0, "p8").withResult(1, "p4")
        assertEquals("p8" to "p4", b.matches[4].a to b.matches[4].b)
        assertTrue(b.matches[4].ready)
        // The earliest round is played first.
        assertEquals(2, b.next!!.number)
        assertEquals(5, b.matchesLeft)
    }

    @Test
    fun byesGoToTheTopSeedsAndArentPlayed() {
        for (n in 2..8) {
            val b = Bracket(players(n))
            val byes = b.size - n
            val firstRound = b.matches.filter { it.round == 0 }
            val walkedOver = firstRound.filter { it.a == null || it.b == null }
            assertEquals(byes, walkedOver.size, "$n players")
            // The players with a bye are seeds 1..byes, and they're through already.
            assertEquals(players(n).take(byes).toSet(), walkedOver.mapNotNull { it.winner }.toSet(), "$n players")
            assertEquals(n - 1, b.matchesLeft, "$n players")
            val done = playOut(b)
            assertEquals("p1", done.champion, "$n players")
        }
    }

    @Test
    fun twoPlayersIsJustTheFinal() {
        val b = Bracket(listOf("a", "b"))
        assertEquals("a" to "b", b.next!!.a to b.next!!.b)
        assertEquals("Final", b.label(b.next!!))
        assertEquals("b", b.withResult(b.next!!.number, "b").champion)
    }

    @Test
    fun aResultMustBeForAMatchBeingPlayedByOneOfItsPlayers() {
        val b = Bracket(players(5))
        assertFailsWith<IllegalArgumentException> { b.withResult(0, "p1") } // p1's bye
        assertFailsWith<IllegalArgumentException> { b.withResult(6, "p1") } // the final, not known yet
        assertFailsWith<IllegalArgumentException> { b.withResult(1, "p1") } // p1 isn't in it
        assertFailsWith<IllegalArgumentException> { b.withResult(9, "p4") }
        val r = b.withResult(1, "p5")
        assertFailsWith<IllegalArgumentException> { r.withResult(1, "p4") } // decided
    }

    @Test
    fun aPlayerWhoLeavesLosesTheirNextMatch() {
        val b = Bracket(players(8)).withLeft("p8")
        assertEquals("p1", b.matches[0].winner)
        assertEquals(6, b.matchesLeft)
        assertEquals(1, b.next!!.number)
        assertFailsWith<IllegalArgumentException> { b.withLeft("nobody") }
    }

    @Test
    fun aPlayerWhoLeavesBeforeTheirOpponentIsKnownStillLoses() {
        // p1 wins, then leaves while the semi-final's other side is still playing.
        val b = Bracket(players(8)).withResult(0, "p1").withLeft("p1")
        assertNull(b.matches[4].winner)
        val c = b.withResult(1, "p5")
        assertEquals("p5", c.matches[4].winner)
        assertEquals("p5" to null, c.matches[6].a to c.matches[6].b)
    }

    @Test
    fun bothPlayersLeavingGivesTheNextOpponentABye() {
        val b = Bracket(players(8)).withLeft("p1").withLeft("p8").withResult(1, "p4")
        assertNull(b.matches[0].winner)
        assertEquals("p4", b.matches[4].winner)
    }

    @Test
    fun labels() {
        val b = Bracket(players(8))
        assertEquals("Quarter-final 2 of 4", b.label(b.matches[1]))
        assertEquals("Semi-final 1 of 2", b.label(b.matches[4]))
        assertEquals("Final", b.label(b.matches[6]))
        val four = Bracket(players(4))
        assertEquals("Semi-final 2 of 2", four.label(four.matches[1]))
    }

    @Test
    fun entrantsAndTheDraw() {
        assertFailsWith<IllegalArgumentException> { Bracket(players(1)) }
        assertFailsWith<IllegalArgumentException> { Bracket(players(9)) }
        assertFailsWith<IllegalArgumentException> { Bracket(listOf("a", "a", "b")) }
        val drawn = Bracket.draw(players(8), Random(7))
        assertEquals(players(8).toSet(), drawn.players.toSet())
        assertTrue((1..20).map { Bracket.draw(players(8), Random(it)).players }.toSet().size > 1)
    }

    @Test
    fun survivesTheWire() {
        val b = Bracket(players(6)).withResult(1, "p4").withLeft("p6")
        assertEquals(b, Json.decodeFromString<Bracket>(Json.encodeToString(Bracket.serializer(), b)))
    }
}
