package com.kofarcade.core

/** Logical controller inputs. The app maps them onto the emulator's RetroPad. */
enum class Key { A, B, C, D, COIN, START, UP, DOWN, LEFT, RIGHT }

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
    var facingLeft = false
    var timing = Timing()
    var rapidFrames = 2

    private var dir = 5
    private val held = mutableSetOf<Key>()
    private val rapid = mutableSetOf<Key>()
    private var macro: MacroPlayer? = null
    private var current: Frame? = null
    private var queued: List<Frame>? = null
    private val sent = mutableMapOf<Key, Boolean>()

    val playing: Boolean get() = macro != null

    fun setDir(newDir: Int) {
        dir = newDir
        flush()
    }

    fun press(key: Key, down: Boolean) {
        if (down) held += key else held -= key
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
    fun play(command: String): Int {
        val frames = compile(command, facingLeft, timing)
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
        cancel()
    }

    // While a macro runs it owns the stick and A-D; coin and start stay live.
    private fun flush() {
        val frame = if (macro != null) current else null
        val flags = dirFlags(frame?.dir ?: dir)
        val phase = (clock() / rapidFrames) % 2 == 0L
        val state = mutableMapOf(
            Key.UP to flags.up,
            Key.DOWN to flags.down,
            Key.LEFT to flags.left,
            Key.RIGHT to flags.right,
            Key.COIN to (Key.COIN in held),
            Key.START to (Key.START in held),
        )
        for (k in listOf(Key.A, Key.B, Key.C, Key.D)) {
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
