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

/**
 * How many frames this phone's game runs ahead of a partner's, times two:
 * the [advantage] it runs ahead of the partner's inputs and the partner's
 * own ([theirAdvantage], as last sent), each set against the other's input
 * delay. The network's latency and the delays cancel out, whatever they are.
 */
fun frameLead(advantage: Int, delay: Int, theirAdvantage: Int, theirDelay: Int): Int =
    (advantage + theirDelay) - (theirAdvantage + delay)

/**
 * Keeps this phone's game level with its partners'. Phones never run at
 * exactly the same speed (screens differ a little), so one slowly gets
 * ahead; that one guesses the others' inputs more and more and, at the
 * rollback limit, stops. Waiting a frame puts it back level. But a wait
 * shows (a frame held on screen), and a single measurement of the lead
 * jumps by a frame or two with each packet's timing: waiting on those had
 * the phones taking turns waiting, about once a second on a router's Wi-Fi.
 * So it waits only for a lead that's real: a frame or more on average over
 * a second of fresh measurements of one partner (the largest of several
 * partners' would be larger than any of them, from the jumps alone), and
 * then not again until that wait has shown in them. A lead of two frames
 * or more is no jump: it's waited for after a quarter of a second, so a
 * phone that can't keep up (an old one, in a heavy scene) is followed, by
 * several frames a second.
 */
class TimeSync(private val window: Int = 60, private val rest: Int = 15) {
    private val total = LongArray(MAX_IDS)
    private val samples = IntArray(MAX_IDS)
    private var frames = 0
    private var resting = window

    /**
     * Once per frame, with each partner's [frameLead], or null when it's stale
     * (that partner unheard for a moment). Returns how many frames to wait
     * now: 0 almost always.
     */
    @Synchronized
    fun update(partners: List<Int>, lead: (Int) -> Int?): Int {
        if (resting > 0) {
            resting--
            return 0
        }
        frames++
        for (p in partners) {
            val l = lead(p) ?: continue
            total[p] += l
            samples[p]++
        }
        val enough = if (frames < window) QUICK else window / 2
        val ahead = partners.filter { samples[it] >= enough }.maxOfOrNull { total[it].toDouble() / samples[it] / 2 } ?: 0.0
        if (frames < window && ahead < FAR_AHEAD) return 0
        clear()
        if (ahead < AHEAD) return 0
        // The wait shows in our measurements at once, in the partners' a round trip later.
        resting = rest
        return kotlin.math.round(ahead).toInt().coerceIn(1, MAX_WAIT)
    }

    /** A new game: measurements start again, after a moment for the first packets. */
    @Synchronized
    fun reset() {
        clear()
        resting = window
    }

    private fun clear() {
        total.fill(0)
        samples.fill(0)
        frames = 0
    }

    private companion object {
        const val AHEAD = 0.75
        const val FAR_AHEAD = 2.0
        const val QUICK = 15
        // The emulator waits no more than this at once (LibretroDroid::netWait).
        const val MAX_WAIT = 4
    }
}
