package com.kofarcade.core.tournament

import com.kofarcade.core.GAMES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MatchWinsTest {
    private val wins = MatchWins(listOf(0xDAF4, 0xDAF6))

    /** The bytes as KOF '97 kept them in the game (tools/emu.py): P2 won, a draw, then P1 won. */
    @Test
    fun aWinIsTheWinnersByteGoingUp() {
        val ram = mutableMapOf(0xDAF4 to 0, 0xDAF6 to 0)
        val w = WinWatch(wins)
        assertNull(w.sample(ram::get)) // where the counts start
        assertNull(w.sample(ram::get))
        ram[0xDAF6] = 1
        assertEquals(1, w.sample(ram::get))
        assertNull(w.sample(ram::get)) // once only
        // A double knockout: nobody's byte moves.
        assertNull(w.sample(ram::get))
        ram[0xDAF4] = 1
        assertEquals(0, w.sample(ram::get))
    }

    @Test
    fun aRoomOpenedMidGameStartsFromTheCountsThere() {
        val ram = mutableMapOf(0xDAF4 to 3, 0xDAF6 to 0)
        val w = WinWatch(wins)
        assertNull(w.sample(ram::get))
        ram[0xDAF6] = 1
        assertEquals(1, w.sample(ram::get))
    }

    @Test
    fun aCountStartingOverOrAnUnreadByteIsNoWin() {
        val ram = mutableMapOf(0xDAF4 to 2, 0xDAF6 to 1)
        val w = WinWatch(wins)
        w.sample(ram::get)
        ram[0xDAF4] = 0
        assertNull(w.sample(ram::get))
        assertNull(w.sample { null })
        w.reset()
        ram[0xDAF6] = 5
        assertNull(w.sample(ram::get))
    }

    @Test
    fun bestOfThree() {
        var s = MatchScore(bestOf = 3)
        assertEquals(2, s.toWin)
        s = s.withGame(0).withGame(1)
        assertNull(s.winner)
        s = s.withGame(1)
        assertEquals(1, s.winner)
        val done = s
        assertFailsWith<IllegalArgumentException> { done.withGame(0) }
        assertEquals(0, MatchScore().withGame(0).winner)
        assertFailsWith<IllegalArgumentException> { MatchScore(bestOf = 2) }
    }

    @Test
    fun everyKofKnowsWhereItsWinsAre() {
        for (id in listOf("kof97", "kof97plus", "kof2002", "kof2002mp", "kof2005")) {
            assertNotNull(GAMES.first { it.id == id }.matchWins, id)
        }
    }
}
