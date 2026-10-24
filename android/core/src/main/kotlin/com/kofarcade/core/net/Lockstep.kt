package com.kofarcade.core.net

// Two-player over the network, delay-based lockstep: both phones run the game
// and exchange only button states. Frame N runs on both phones with the same
// two inputs, so they stay identical (FBNeo is deterministic once both start
// from the same state, which the host sends at the start).
//
// Your input for frame N is read when your phone is at frame N - delay, so it
// has `delay` frames (~16 ms each) to reach the other phone before it's
// needed. A phone that hasn't got its partner's input for the next frame just
// waits, which a fast local network makes rare.

/** Inputs for one frame: [p1] and [p2] are RetroPad button masks (bit = RetroPad id). */
data class FrameInputs(val frame: Int, val p1: Int, val p2: Int)

/**
 * The input bookkeeping for one side. Thread-safe: the network thread calls
 * [receive] and [drain], the game calls [sampleUpTo].
 */
class Lockstep(val delay: Int, val localPlayer: Int) {
    init {
        require(delay >= 1) { "delay must be at least one frame" }
        require(localPlayer == 0 || localPlayer == 1)
    }

    private val local = HashMap<Int, Int>()
    private val remote = HashMap<Int, Int>()

    /** The next local frame to sample. */
    var nextLocal = 0
        private set

    /** The next frame [drain] will hand out. */
    var nextMerged = 0
        private set

    /** All remote frames below this are known. */
    var remoteKnown = 0
        private set

    /** The partner has all our frames below this. */
    var localAcked = 0
        private set

    /**
     * Samples local input for every frame up to [emulated] + [delay] not yet
     * sampled, [input] once per frame (so a macro advances one step per frame).
     * Returns how many frames were sampled.
     */
    @Synchronized
    fun sampleUpTo(emulated: Int, input: () -> Int): Int {
        var n = 0
        while (nextLocal <= emulated + delay) {
            local[nextLocal] = input()
            nextLocal++
            n++
        }
        return n
    }

    /** Remote inputs for frames [start] until start + masks.size; [ack]: they have our frames below it. */
    @Synchronized
    fun receive(start: Int, masks: IntArray, ack: Int) {
        masks.forEachIndexed { i, m ->
            val f = start + i
            if (f >= nextMerged) remote.putIfAbsent(f, m)
        }
        while (remote.containsKey(remoteKnown) || remoteKnown < nextMerged) remoteKnown++
        if (ack > localAcked) localAcked = minOf(ack, nextLocal)
    }

    /** Frames whose two inputs are both known, in order, each handed out once. */
    @Synchronized
    fun drain(): List<FrameInputs> {
        val out = ArrayList<FrameInputs>()
        while (true) {
            val l = local[nextMerged] ?: break
            val r = remote[nextMerged] ?: break
            out += if (localPlayer == 0) FrameInputs(nextMerged, l, r) else FrameInputs(nextMerged, r, l)
            remote.remove(nextMerged)
            nextMerged++
        }
        // Local inputs can go once merged here and received there.
        local.keys.removeAll { it < minOf(nextMerged, localAcked) }
        return out
    }

    /** Our inputs the partner hasn't acknowledged: (first frame, masks), at most [max]. */
    @Synchronized
    fun outgoing(max: Int = 120): Pair<Int, IntArray> {
        val start = localAcked
        val end = minOf(nextLocal, start + max)
        return start to IntArray(maxOf(0, end - start)) { local[start + it] ?: 0 }
    }
}
