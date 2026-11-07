package com.kofarcade.core.tournament

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A single-elimination tournament for 2 to 8 players (docs/tournament-plan.md,
 * T1). Plain data: the room passes it around and the server keeps it (T4, T6).
 *
 * [players] are player ids in seed order (seed 1 first). The bracket is the
 * next power of two (4 or 8); the missing places are byes, which go to the
 * top seeds, as tournaments do. [results] holds each match's winner by its
 * number (round by round, top to bottom: 8 players are matches 0-3, then 4-5,
 * then 6, the final). A player who [left] loses their next match without
 * playing it; their opponent goes through.
 */
@Serializable
data class Bracket(
    val players: List<String>,
    val results: Map<Int, String> = emptyMap(),
    val left: Set<String> = emptySet(),
) {
    init {
        require(players.size in MIN..MAX) { "A tournament is for $MIN to $MAX players" }
        require(players.toSet().size == players.size) { "A player can only enter once" }
    }

    /** A match: its [number], [round] (0 first), the two sides (null: a bye, or not known yet) and its winner. */
    @Serializable
    data class Match(val number: Int, val round: Int, val a: String?, val b: String?, val winner: String?) {
        /** Both players known, no winner yet: to be played. */
        val ready: Boolean get() = a != null && b != null && winner == null
    }

    /** Places in the bracket: 4 or 8. */
    val size: Int get() = if (players.size <= 4) 4 else 8

    /** Rounds to a champion: 2 or 3. */
    val rounds: Int get() = if (size == 4) 2 else 3

    /**
     * Every match, round by round, with what's known of it; byes and players
     * who left decided without play. Worked out once per bracket (each change
     * is a new one), not on every look.
     */
    val matches: List<Match> by lazy {
            val out = ArrayList<Match>()
            // The first round's places, by seed: the top seeds meet the byes.
            var sides: List<Side> = seedOrder(size).map { seed -> players.getOrNull(seed)?.let { Side.Player(it) } ?: Side.Bye }
            var number = 0
            for (round in 0 until rounds) {
                val next = ArrayList<Side>()
                for (i in sides.indices step 2) {
                    val a = sides[i]
                    val b = sides[i + 1]
                    val winner = decide(number, a, b)
                    out += Match(number, round, (a as? Side.Player)?.id, (b as? Side.Player)?.id, (winner as? Side.Player)?.id)
                    next += winner ?: Side.Unknown
                    number++
                }
                sides = next
            }
            out
        }

    /** The winner, once the final's decided. */
    val champion: String? get() = matches.last().winner

    /** The next match to play (the earliest round first), or null when there's none left (or none ready yet). */
    val next: Match? get() = matches.firstOrNull { it.ready }

    /** Matches still to be played, counting ones whose players aren't known yet; byes don't count. */
    val matchesLeft: Int get() = matches.count { it.winner == null && !(it.round == 0 && (it.a == null || it.b == null)) }

    /** [winner] won match [number] (one of its two players). */
    fun withResult(number: Int, winner: String): Bracket {
        val m = matches.getOrNull(number) ?: throw IllegalArgumentException("No match $number")
        require(m.ready) { "Match $number isn't being played" }
        require(winner == m.a || winner == m.b) { "$winner isn't in match $number" }
        return copy(results = results + (number to winner))
    }

    /** [player] left: their next match goes to their opponent (and so on, if they were still in). */
    fun withLeft(player: String): Bracket {
        require(player in players) { "$player isn't in the tournament" }
        return copy(left = left + player)
    }

    /** "Final", "Semi-final", "Quarter-final" for match [m]. */
    fun roundName(m: Match): String = when (rounds - m.round) {
        1 -> "Final"
        2 -> "Semi-final"
        else -> "Quarter-final"
    }

    /** Where match [m] stands in its round: "Quarter-final 2 of 4". */
    fun label(m: Match): String {
        val inRound = matches.filter { it.round == m.round }
        return if (inRound.size == 1) roundName(m) else "${roundName(m)} ${inRound.indexOf(m) + 1} of ${inRound.size}"
    }

    private sealed interface Side {
        data class Player(val id: String) : Side
        data object Bye : Side
        /** Waiting for an earlier match. */
        data object Unknown : Side
    }

    /** A match's winner, if it's decided: a result, a bye, or someone who left. */
    private fun decide(number: Int, a: Side, b: Side): Side? {
        results[number]?.let { return Side.Player(it) }
        if (a == Side.Unknown || b == Side.Unknown) return null
        val aOut = a == Side.Bye || (a is Side.Player && a.id in left)
        val bOut = b == Side.Bye || (b is Side.Player && b.id in left)
        return when {
            aOut && bOut -> Side.Bye // nobody goes through: the next round's opponent gets a bye
            aOut -> b
            bOut -> a
            else -> null
        }
    }

    companion object {
        const val MIN = 2
        const val MAX = 8

        /** A bracket for [players] (any order), seeded at random. */
        fun draw(players: List<String>, random: Random = Random): Bracket = Bracket(players.shuffled(random))

        /**
         * Seed numbers (0 = the top seed) for each first-round place, so the
         * top two can only meet in the final and byes go to the top seeds.
         */
        fun seedOrder(size: Int): List<Int> = when (size) {
            4 -> listOf(0, 3, 1, 2)
            8 -> listOf(0, 7, 3, 4, 1, 6, 2, 5)
            else -> throw IllegalArgumentException("A bracket is 4 or 8 places")
        }
    }
}
