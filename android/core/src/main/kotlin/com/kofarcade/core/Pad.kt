package com.kofarcade.core

/** Logical controller inputs. The app maps them onto the emulator's RetroPad. */
enum class Key { A, B, C, D, E, F, COIN, START, UP, DOWN, LEFT, RIGHT }

/** The attack buttons, in [BUTTONS] order. */
val FACE_KEYS = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F)

/**
 * Merges finger input and running macros into one controller state and sends
 * only the changes to [send].
 *
 * Call [onFrame] once per emulated frame: it advances macros and rapid fire.
 * [clock] returns the emulated frame counter.
 */
class Pad(
    private val send: (Key, Boolean) -> Unit,
    private val clock: () -> Long,
) {
    /** Turning round re-sends a held direction (block), so it stays "back". */
    var facingLeft = false
        set(v) {
            if (field == v) return
            field = v
            flush()
        }
    var timing = Timing()
    var rapidFrames = 2

    private var dir = 5
    private var hold: Int? = null // a held direction from a button (block), facing right
    private var run: Int? = null // 4 or 6 while running, on screen (not mirrored)
    private val held = mutableSetOf<Key>()
    private val rapid = mutableSetOf<Key>()
    private var macro: MacroPlayer? = null
    private var current: Frame? = null
    private var queued: List<Frame>? = null
    private val sent = mutableMapOf<Key, Boolean>()

    val playing: Boolean get() = macro != null

    val running: Boolean get() = run != null

    /** The last way the stick pointed left or right (4 or 6), for running. */
    var lastHorizontal = 6
        private set

    fun setDir(newDir: Int) {
        dir = newDir
        val f = dirFlags(newDir)
        if (f.left) lastHorizontal = 4 else if (f.right) lastHorizontal = 6
        flush()
    }

    /**
     * Runs toward [toward] (4 or 6, on screen): taps it twice the way the
     * games read a dash (neutral, tap, release), then holds it until
     * [stopRun]. The stick still steers up and down while running.
     */
    fun startRun(toward: Int = lastHorizontal) {
        require(toward == 4 || toward == 6) { "run left (4) or right (6)" }
        if (run == toward) return
        run = toward
        val s = timing.step
        start(listOf(Frame(5, "")) + List(s) { Frame(toward, "") } + List(s) { Frame(5, "") })
    }

    fun stopRun() {
        if (run == null) return
        run = null
        flush()
    }

    fun press(key: Key, down: Boolean) {
        if (down) held += key else held -= key
        flush()
    }

    /**
     * Holds a direction from a button instead of the stick, e.g. the block
     * button holds back (4) or down-back (1). Written facing right and
     * mirrored with [facingLeft]; null lets go.
     */
    fun setHold(relDir: Int?) {
        hold = relDir
        flush()
    }

    fun isRapid(key: Key) = key in rapid

    /** Auto-fire: while a rapid key is held it toggles every [rapidFrames] frames. */
    fun setRapid(key: Key, on: Boolean) {
        if (on) rapid += key else rapid -= key
        flush()
    }

    /**
     * Plays [command]. A tap while another macro runs is queued (once), so
     * mashing a super button doesn't garble the motion. Returns the number of
     * frames the command takes.
     */
    fun play(command: String, jumpFirst: Boolean = false): Int {
        // Air moves from the ground: up for 4 frames, a short pause, then the
        // motion while rising (V-Slasher comes out this way in KOF 2002).
        val jump = if (jumpFirst) List(4) { Frame(8, "") } + List(2) { Frame(5, "") } else emptyList()
        val frames = jump + compile(command, facingLeft, timing)
        if (macro != null) queued = frames else start(frames)
        return frames.size
    }

    private fun start(frames: List<Frame>) {
        macro = MacroPlayer(frames)
        current = null
        step()
    }

    private fun step() {
        val frame = macro?.tick(clock())
        if (frame != null) {
            current = frame
            flush()
            return
        }
        macro = null
        current = null
        val next = queued
        queued = null
        if (next != null) start(next) else flush()
    }

    fun onFrame() {
        if (macro != null) step() else if (rapid.any { it in held }) flush()
    }

    fun cancel() {
        macro = null
        current = null
        queued = null
        flush()
    }

    fun releaseAll() {
        held.clear()
        dir = 5
        hold = null
        run = null
        cancel()
    }

    // While a macro runs it owns the stick and A-D; coin and start stay live.
    private fun flush() {
        val frame = if (macro != null) current else null
        val holdDir = hold?.let { if (facingLeft) mirrorDir(it) else it }
        val flags = dirFlags(frame?.dir ?: holdDir ?: run?.let { withVertical(it, dir) } ?: dir)
        val phase = (clock() / rapidFrames) % 2 == 0L
        val state = mutableMapOf(
            Key.UP to flags.up,
            Key.DOWN to flags.down,
            Key.LEFT to flags.left,
            Key.RIGHT to flags.right,
            Key.COIN to (Key.COIN in held),
            Key.START to (Key.START in held),
        )
        for (k in FACE_KEYS) {
            state[k] = if (frame != null) {
                k.name in frame.buttons
            } else {
                k in held && (k !in rapid || phase)
            }
        }
        for ((k, v) in state) {
            if ((sent[k] ?: false) != v) {
                sent[k] = v
                send(k, v)
            }
        }
    }
}

/** [horizontal] (4 or 6) plus the up/down part of [stick]: running steers. */
internal fun withVertical(horizontal: Int, stick: Int): Int {
    val f = dirFlags(stick)
    return when {
        f.up -> horizontal + 3
        f.down -> horizontal - 3
        else -> horizontal
    }
}
