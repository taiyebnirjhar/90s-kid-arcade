package com.kofarcade.core.tournament

import kotlinx.serialization.Serializable

/**
 * A room's tournament (docs/tournament-plan.md, T3-T5), as the room's host
 * runs it and every phone in the room keeps a copy of it (so a new host
 * carries on). Plain data: each change gives a new one.
 *
 * Players are room member ids as text (the [bracket]'s ids); [names] keeps
 * what each was called, for a player who has left. [playing] is the match
 * number on now (null between matches: the next pair is about to be called),
 * [score] its games so far ([bestOf] 1: the game's own match; 3: best of 3).
 * [reports] (T3) are who each player says won the game on now, by reporter.
 * [upAt] is when the pair on now was called (the host's clock): a player not
 * there [NO_SHOW_MS] after that loses (T4).
 */
@Serializable
data class Tournament(
    val bracket: Bracket,
    val names: Map<String, String>,
    val game: String,
    val bestOf: Int = 1,
    val playing: Int? = null,
    val score: MatchScore = MatchScore(bestOf),
    val reports: Map<String, String> = emptyMap(),
    val upAt: Long = 0,
    /** Every match's result as it happened, newest last, for the room to see. */
    val results: List<Result> = emptyList(),
) {
    /** A match decided: [winner] beat [loser] ("" for a bye or a player who didn't come), and how. */
    @Serializable
    data class Result(val match: Int, val winner: String, val loser: String, val how: How)

    enum class How {
        /** Read from the game ([MatchWins]). */
        GAME,
        /** Both players said so (T3). */
        PLAYERS,
        /** The host said so (a disagreement, or no word). */
        HOST,
        /** The other didn't come in time, or left. */
        NO_SHOW,
    }

    /** What happened when a result was put in. */
    sealed interface Outcome {
        /** A game won, the match goes on (best of 3): play again with the same seats. */
        data object NextGame : Outcome
        /** The match is decided: call the next pair, or crown the [champion]. */
        data class MatchOver(val result: Result, val champion: String?) : Outcome
        /** The players disagree: the host decides. */
        data object Disputed : Outcome
        /** One player has said; waiting for the other. */
        data object Waiting : Outcome
    }

    init {
        require(bestOf == 1 || bestOf == 3) { "Best of 1 or 3" }
    }

    /** The match on now, or null. */
    val current: Bracket.Match? get() = playing?.let { bracket.matches.getOrNull(it) }

    /** The player's name, or "Player" for one never seen. */
    fun name(id: String?): String = id?.let { names[it] } ?: "Player"

    val champion: String? get() = bracket.champion

    val over: Boolean get() = champion != null

    /** The players' ids on now: (seat 0, seat 1). */
    val pair: Pair<String, String>? get() = current?.let { m -> if (m.a != null && m.b != null) m.a to m.b else null }

    /** Calls the next pair ([now]: the host's clock); unchanged if one's on or none is ready. */
    fun callNext(now: Long): Tournament {
        if (playing != null || over) return this
        val next = bracket.next ?: return this
        return copy(playing = next.number, score = MatchScore(bestOf), reports = emptyMap(), upAt = now)
    }

    /** Side [side] (0: the pair's first, seat 0) won a game, as [how] says. */
    fun gameWon(side: Int, how: How): Pair<Tournament, Outcome> {
        val m = current ?: throw IllegalStateException("No match on")
        val (a, b) = pair ?: throw IllegalStateException("No match on")
        val s = score.withGame(side)
        val winner = s.winner ?: return copy(score = s, reports = emptyMap()) to Outcome.NextGame
        val won = if (winner == 0) a else b
        val lost = if (winner == 0) b else a
        return decide(m.number, won, lost, how)
    }

    /**
     * Player [from] says [winner] won the game on now (T3). Both saying the
     * same is the result; saying different, the host decides ([hostDecides]).
     */
    fun report(from: String, winner: String): Pair<Tournament, Outcome> {
        val (a, b) = pair ?: throw IllegalStateException("No match on")
        require(from == a || from == b) { "Only the two playing say who won" }
        require(winner == a || winner == b) { "$winner isn't playing" }
        val next = copy(reports = reports + (from to winner))
        val other = if (from == a) b else a
        val theirs = next.reports[other] ?: return next to Outcome.Waiting
        if (theirs != winner) return next to Outcome.Disputed
        return next.gameWon(if (winner == a) 0 else 1, How.PLAYERS)
    }

    /** The host says [winner] won the game on now. */
    fun hostDecides(winner: String): Pair<Tournament, Outcome> {
        val (a, b) = pair ?: throw IllegalStateException("No match on")
        require(winner == a || winner == b) { "$winner isn't playing" }
        return gameWon(if (winner == a) 0 else 1, How.HOST)
    }

    /**
     * [player] isn't there for the match on now (didn't come in time, or
     * left the room): the other goes through, whatever the score. For a
     * player who left between matches, use [left].
     */
    fun noShow(player: String): Pair<Tournament, Outcome> {
        val m = current ?: throw IllegalStateException("No match on")
        val (a, b) = pair ?: throw IllegalStateException("No match on")
        require(player == a || player == b) { "$player isn't playing" }
        return decide(m.number, if (player == a) b else a, player, How.NO_SHOW)
    }

    /** [player] left the tournament: their next match goes to their opponent (the one on now, at once). */
    fun left(player: String): Pair<Tournament, Outcome?> {
        pair?.let { (a, b) -> if (player == a || player == b) return noShow(player).let { (t, o) -> t.copy(bracket = t.bracket.withLeft(player)) to o } }
        return copy(bracket = bracket.withLeft(player)) to null
    }

    private fun decide(match: Int, winner: String, loser: String, how: How): Pair<Tournament, Outcome> {
        val r = Result(match, winner, loser, how)
        val b = bracket.withResult(match, winner)
        val next = copy(bracket = b, playing = null, score = MatchScore(bestOf), reports = emptyMap(), results = results + r)
        return next to Outcome.MatchOver(r, b.champion)
    }

    /** The bar over the game: "Quarter-final 2 of 4 · Rafi vs Tanu" (and the score in a best of 3). */
    fun bar(): String? {
        val m = current ?: return null
        val (a, b) = pair ?: return null
        val games = if (bestOf > 1) "  ${score.games[0]}-${score.games[1]}" else ""
        return "${bracket.label(m)} · ${name(a)} vs ${name(b)}$games"
    }

    companion object {
        const val NO_SHOW_MS = 60_000L
        const val MIN_PLAYERS = 3

        /** A new tournament for [players] (member id to name, 3 to 8), drawn at random. */
        fun start(players: Map<String, String>, game: String, bestOf: Int, random: kotlin.random.Random = kotlin.random.Random): Tournament {
            require(players.size in MIN_PLAYERS..Bracket.MAX) { "A tournament is for $MIN_PLAYERS to ${Bracket.MAX} players" }
            return Tournament(Bracket.draw(players.keys.toList(), random), players, game, bestOf)
        }
    }
}
