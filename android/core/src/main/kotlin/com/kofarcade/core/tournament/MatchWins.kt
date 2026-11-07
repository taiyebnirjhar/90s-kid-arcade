package com.kofarcade.core.tournament

import kotlinx.serialization.Serializable

/**
 * Where a fighting game counts each side's match wins (docs/tournament-plan.md,
 * T2): one byte per side, by its offset in the RAM as the app reads it (Neo
 * Geo: swapped in pairs, so 68K 0x10DAF5 is offset 0xDAF4). The game adds one
 * to the winner's byte when a match ends; a draw (both knocked out) adds
 * nothing. Checked in the games (tools/emu.py, two players, each side
 * winning): KOF '97 and '97 Plus, 2002, 2002 Magic Plus and 2005 Unique.
 */
class MatchWins(val offsets: List<Int>) {
    init {
        require(offsets.size == 2) { "A match is between two sides" }
    }
}

/**
 * Watches a game's [MatchWins] bytes over time: [sample] says which side just
 * won a match (0 or 1), from a byte going up. The first sample only sets
 * where the counts start (a room may open mid-game), and a byte going down
 * (the game starting its count over) isn't a win.
 */
class WinWatch(private val wins: MatchWins) {
    private var last: IntArray? = null

    /** The side that won since the last sample, or null; [read] gives a byte by offset (null if it can't). */
    fun sample(read: (Int) -> Int?): Int? {
        val now = IntArray(2) { side -> read(wins.offsets[side]) ?: return null }
        val before = last
        last = now
        if (before == null) return null
        val up = (0..1).filter { now[it] > before[it] }
        // Both at once can't be one match's result: count neither.
        return up.singleOrNull()
    }

    /** Start over (a new match from the room's start state): the next sample sets the counts again. */
    fun reset() {
        last = null
    }
}

/**
 * A tournament match as the host sets it: the game's own match once
 * ([bestOf] 1), or best of 3 games. [games] holds each side's games won.
 */
@Serializable
data class MatchScore(val bestOf: Int = 1, val games: List<Int> = listOf(0, 0)) {
    init {
        require(bestOf == 1 || bestOf == 3) { "Best of 1 or 3" }
    }

    /** Games a side needs to take the match. */
    val toWin: Int get() = bestOf / 2 + 1

    /** The side that took the match, or null while it goes on. */
    val winner: Int? get() = games.indexOfFirst { it >= toWin }.takeIf { it >= 0 }

    /** [side] won a game. */
    fun withGame(side: Int): MatchScore {
        require(side in 0..1) { "Side 0 or 1" }
        require(winner == null) { "The match is over" }
        return copy(games = games.mapIndexed { i, n -> if (i == side) n + 1 else n })
    }
}
