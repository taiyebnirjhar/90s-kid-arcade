package com.kofarcade.core.net

// Two players over the network: both phones run the game and exchange only
// button states, so each needs, for every frame, its own input and the
// partner's. The emulator (LibretroDroid's rollback netcode) runs a frame as
// soon as it has its own input, guessing the partner's until it arrives and
// replaying if the guess was wrong. This class is the bookkeeping between
// the game and the network: our inputs to send (until acknowledged) and the
// partner's to hand to the emulator, in order.
//
// Our input for frame N is read when this phone is at frame N - delay: a
// frame or two of delay gives it time to arrive, so fewer guesses are wrong.

/** Inputs exchanged on both sides, thread-safe: the network thread receives, the game samples. */
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

    /** All remote frames below this are known. */
    var remoteKnown = 0
        private set

    /** Remote frames below this have been handed out by [takeRemote]. */
    var remoteTaken = 0
        private set

    /** The partner has all our frames below this. */
    var localAcked = 0
        private set

    /**
     * Samples local input for every frame up to [emulated] + [delay] not yet
     * sampled, [input] once per frame (so a macro advances one step per frame).
     * Returns the new inputs: (first frame, masks).
     */
    @Synchronized
    fun sampleUpTo(emulated: Int, input: () -> Int): Pair<Int, IntArray> {
        val start = nextLocal
        val out = ArrayList<Int>()
        while (nextLocal <= emulated + delay) {
            val m = input()
            local[nextLocal] = m
            out += m
            nextLocal++
        }
        return start to out.toIntArray()
    }

    /** Remote inputs for frames [start] until start + masks.size; [ack]: they have our frames below it. */
    @Synchronized
    fun receive(start: Int, masks: IntArray, ack: Int) {
        masks.forEachIndexed { i, m ->
            val f = start + i
            if (f >= remoteTaken) remote.putIfAbsent(f, m)
        }
        while (remote.containsKey(remoteKnown)) remoteKnown++
        if (ack > localAcked) {
            localAcked = minOf(ack, nextLocal)
            local.keys.removeAll { it < localAcked }
        }
    }

    /** The partner's inputs not handed out yet, in order and without gaps: (first frame, masks). */
    @Synchronized
    fun takeRemote(): Pair<Int, IntArray> {
        val start = remoteTaken
        val masks = IntArray(remoteKnown - start) { remote.remove(start + it)!! }
        remoteTaken = remoteKnown
        return start to masks
    }

    /** Our inputs the partner hasn't acknowledged: (first frame, masks), at most [max]. */
    @Synchronized
    fun outgoing(max: Int = 120): Pair<Int, IntArray> {
        val start = localAcked
        val end = minOf(nextLocal, start + max)
        return start to IntArray(maxOf(0, end - start)) { local[start + it] ?: 0 }
    }
}
