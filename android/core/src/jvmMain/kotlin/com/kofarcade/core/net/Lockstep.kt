package com.kofarcade.core.net

// Playing over the network: every phone runs the game and they exchange only
// button states, so each needs, for every frame, its own input and every
// partner's. The emulator (LibretroDroid's rollback netcode) runs a frame as
// soon as it has its own input, guessing the partner's until it arrives and
// replaying if the guess was wrong. This class is the bookkeeping between
// the game and the network: our inputs to send (until acknowledged) and the
// partner's to hand to the emulator, in order.
//
// Our input for frame N is read when this phone is at frame N - delay: a
// frame or two of delay gives it time to arrive, so fewer guesses are wrong.
// Each phone picks its own delay ([DelayTuner]); the partner needn't match.

/**
 * Inputs exchanged between the phones of a game, thread-safe: the network
 * thread receives, the game samples. Phones are numbered (a room's member
 * ids, below [MAX_IDS]; in a plain match, the seats). [partners] are the
 * other phones that play: we need their inputs. [watchers] only watch: they
 * get our inputs too, but send none. [localPlayer] -1: this phone watches.
 * Each one acknowledges our inputs on its own, and we send each one only
 * what it hasn't got.
 */
class Lockstep(
    delay: Int,
    val localPlayer: Int,
    val players: Int = 2,
    /** The other players. */
    val partners: List<Int> = (0 until players).filter { it != localPlayer },
    /** Phones that watch: they're sent our inputs, and send none. */
    val watchers: List<Int> = emptyList(),
) {
    init {
        require(delay >= 1) { "delay must be at least one frame" }
        require(players in 2..4) { "2 to 4 players" }
        require(localPlayer in -1 until MAX_IDS)
        require((partners + watchers).all { it in 0 until MAX_IDS && it != localPlayer }) { "bad partners $partners $watchers" }
    }

    /** This phone plays (it has inputs to send), rather than watching. */
    val playing: Boolean get() = localPlayer >= 0

    /** May change mid-game: the next [sampleUpTo] then reads one input more, or one fewer. */
    @Volatile var delay = delay
        set(value) {
            require(value >= 1) { "delay must be at least one frame" }
            field = value
        }

    private val local = HashMap<Int, Int>()
    private val remote = Array(MAX_IDS) { HashMap<Int, Int>() }
    private val known = IntArray(MAX_IDS)
    private val taken = IntArray(MAX_IDS)
    private val acked = IntArray(MAX_IDS)

    /** The next local frame to sample. */
    var nextLocal = 0
        private set

    /** All of [player]'s frames below this are known. */
    @Synchronized
    fun known(player: Int): Int = known[player]

    /** Every partner's frames below this are known (alone: all of them). */
    val remoteKnown: Int @Synchronized get() = partners.minOfOrNull { known[it] } ?: Int.MAX_VALUE

    /** [player]'s frames below this have been handed out by [takeRemote]. */
    @Synchronized
    fun taken(player: Int): Int = taken[player]

    /** Everyone has all our frames below this (alone: everything sampled). */
    val localAcked: Int @Synchronized get() = (partners + watchers).minOfOrNull { acked[it] } ?: nextLocal

    /**
     * Samples local input for every frame up to [emulated] + [delay] not yet
     * sampled, [input] once per frame (so a macro advances one step per frame).
     * Returns the new inputs: (first frame, masks).
     */
    @Synchronized
    fun sampleUpTo(emulated: Int, input: () -> Int): Pair<Int, IntArray> {
        val start = nextLocal
        if (!playing) return start to IntArray(0)
        val out = ArrayList<Int>()
        while (nextLocal <= emulated + delay) {
            val m = input()
            local[nextLocal] = m
            out += m
            nextLocal++
        }
        // Alone: nobody to send them to, so nothing to keep.
        if (partners.isEmpty() && watchers.isEmpty()) local.clear()
        return start to out.toIntArray()
    }

    /** [from]'s inputs for frames [start] until start + masks.size (a watcher's: none); [ack]: they have our frames below it. */
    @Synchronized
    fun receive(from: Int, start: Int, masks: IntArray, ack: Int) {
        require(from in partners || from in watchers) { "not in the game: $from" }
        if (from in partners) {
            val got = remote[from]
            masks.forEachIndexed { i, m ->
                val f = start + i
                if (f >= taken[from]) got.putIfAbsent(f, m)
            }
            while (got.containsKey(known[from])) known[from]++
        }
        if (ack > acked[from]) {
            acked[from] = minOf(ack, nextLocal)
            val all = localAcked
            local.keys.removeAll { it < all }
        }
    }

    /** [from]'s inputs not handed out yet, in order and without gaps: (first frame, masks). */
    @Synchronized
    fun takeRemote(from: Int): Pair<Int, IntArray> {
        val start = taken[from]
        val masks = IntArray(known[from] - start) { remote[from].remove(start + it)!! }
        taken[from] = known[from]
        return start to masks
    }

    /** Our inputs [to] hasn't acknowledged: (first frame, masks), at most [max]. */
    @Synchronized
    fun outgoing(to: Int, max: Int = 120): Pair<Int, IntArray> {
        val start = acked[to]
        val end = minOf(nextLocal, start + max)
        return start to IntArray(maxOf(0, end - start)) { local[start + it] ?: 0 }
    }
}

/** Phone numbers in a [Lockstep] are below this. */
const val MAX_IDS = 16

/**
 * Picks this phone's input delay from how often the partner has to guess our
 * inputs. The partner's advantage (frames it runs ahead of our inputs) is
 * about the network's latency minus our delay: when it stays high the partner
 * keeps replaying, and characters jump; one frame more delay stops that. When
 * the delay is a whole frame more than needed, it goes back down, so buttons
 * feel as quick as the connection allows.
 */
class DelayTuner(private val min: Int = 1, private val max: Int = 3, private val window: Int = 180) {
    private var total = 0
    private var count = 0

    /** Once per frame with the partner's latest advantage; returns the delay to use from now on. */
    fun update(delay: Int, partnerAdvantage: Int): Int {
        total += partnerAdvantage
        if (++count < window) return delay
        val average = total.toDouble() / count
        total = 0
        count = 0
        return when {
            average >= RAISE && delay < max -> delay + 1
            average <= LOWER && delay > min -> delay - 1
            else -> delay
        }
    }

    private companion object {
        // Apart by more than a frame, so a change never undoes itself.
        const val RAISE = 1.5
        const val LOWER = -1.0
    }
}
