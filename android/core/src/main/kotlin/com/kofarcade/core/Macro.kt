package com.kofarcade.core

// Motion-input engine.
//
// Commands are written in numpad notation, the standard for fighting games,
// always as if your character faces RIGHT:
//
//   7 8 9      4 = back, 6 = forward, 2 = down, 8 = up, 5 = neutral
//   4 5 6      236     = quarter circle forward  (QCF)
//   1 2 3      2141236 = QCB then HCF            (Kyo's Orochinagi)
//
// After the directions come the buttons to press together: "236236+AC".
// "[4]6+A" is a charge motion: hold back, then forward + A.
// Commas make a sequence of separate inputs, for KOF 2002's button-sequence
// supers: "D,B,C,C,8" is Mai's Kyuubi no Kitsune (D, B, C, C, up).
// "~" joins two inputs that must follow at once: "214+C~A" is K''s Crimson
// Star Road (C, then A right away; the game misses it at comma speed).
//
// compile() turns a command into a per-frame timeline the pad plays back one
// emulated frame at a time, mirrored when your fighter faces left. It moves
// like a real stick: rolling through the diagonal between two directions a
// quarter turn apart, and letting go between two taps of the same direction.
// Every KOF '97, 2002 and 2005 move was checked this way in the real games
// (FBNeo, tools/emu.py).

const val BUTTONS = "ABCD"

data class Part(val dir: Int, val charge: Boolean)

/** One input: directions, then buttons pressed together. [quick]: follows the previous input at once ("~"). */
data class Step(val parts: List<Part>, val buttons: String, val quick: Boolean = false)

/** A parsed command: usually one [Step]; a comma-separated sequence has several. */
data class Command(val steps: List<Step>) {
    constructor(parts: List<Part>, buttons: String) : this(listOf(Step(parts, buttons)))

    val isSequence: Boolean get() = steps.size > 1

    /** Directions of a single-step command (all steps' directions for a sequence). */
    val parts: List<Part> get() = steps.flatMap { it.parts }

    /** The buttons the command ends on. */
    val buttons: String get() = steps.last().buttons
}

/** One emulated frame of a macro: the stick direction and buttons held. */
data class Frame(val dir: Int, val buttons: String)

data class Timing(
    val step: Int = 2, // frames each direction is held
    val charge: Int = 50, // frames a [charge] direction is held
    val press: Int = 4, // frames the buttons are held
    val gap: Int = 2, // neutral frames between the inputs of a sequence
    val quickPress: Int = 2, // frames the buttons are held around a "~"
)

class CommandException(message: String) : IllegalArgumentException(message)

private val UP = setOf(7, 8, 9)

private val ROLL = mapOf(
    (4 to 2) to 1, (2 to 4) to 1, (2 to 6) to 3, (6 to 2) to 3,
    (6 to 8) to 9, (8 to 6) to 9, (8 to 4) to 7, (4 to 8) to 7,
)

private val MIRROR = mapOf(1 to 3, 3 to 1, 4 to 6, 6 to 4, 7 to 9, 9 to 7, 2 to 2, 5 to 5, 8 to 8)

fun mirrorDir(dir: Int): Int = MIRROR[dir] ?: 5

/** Parses "2141236+AC" or "D,B,C,C,8". Throws [CommandException] with a readable message on bad input. */
fun parseCommand(command: String): Command {
    val src = command.uppercase().filterNot { it.isWhitespace() }
    if (src.isEmpty()) throw CommandException("Empty command")
    val steps = mutableListOf<Step>()
    var quick = false
    var start = 0
    for (i in 0..src.length) {
        val c = src.getOrNull(i)
        if (c == null || c == ',' || c == '>' || c == '~') {
            val segment = src.substring(start, i)
            if (segment.isEmpty()) throw CommandException("Empty input between commas")
            steps += parseStep(segment).copy(quick = quick)
            quick = c == '~'
            start = i + 1
        }
    }
    return Command(steps)
}

private fun parseStep(src: String): Step {
    val parts = mutableListOf<Part>()
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        when {
            ch == '[' -> {
                val m = Regex("""^\[([1-9])]""").find(src.substring(i))
                    ?: throw CommandException("Charge must look like [4] or [2]")
                parts += Part(m.groupValues[1].toInt(), charge = true)
                i += m.value.length
            }
            ch in '1'..'9' -> {
                parts += Part(ch - '0', charge = false)
                i++
            }
            else -> break
        }
    }
    var rest = src.substring(i)
    if (rest.startsWith("+")) rest = rest.substring(1)
    if (!rest.all { it in BUTTONS }) {
        throw CommandException("Unknown input \"$rest\". Use directions 1-9 and buttons A B C D")
    }
    val buttons = BUTTONS.filter { it in rest }
    if (parts.isEmpty() && buttons.isEmpty()) throw CommandException("Command has no inputs")
    return Step(parts, buttons)
}

fun isValidCommand(command: String): Boolean = runCatching { parseCommand(command) }.isSuccess

/** The frame-by-frame timeline for [command]. The last frame is always neutral. */
fun compile(command: String, facingLeft: Boolean = false, timing: Timing = Timing()): List<Frame> {
    val steps = parseCommand(command).steps
    val map = { d: Int -> if (facingLeft) mirrorDir(d) else d }
    val frames = mutableListOf<Frame>()
    steps.forEachIndexed { i, (parts, buttons, quick) ->
        val quickNext = steps.getOrNull(i + 1)?.quick == true
        parts.forEachIndexed { k, (dir, charge) ->
            val before = parts.getOrNull(k - 1)?.dir
            when {
                // The same direction twice ("22") is two taps: let go in between.
                before == dir -> repeat(timing.step) { frames += Frame(5, "") }
                // A real stick passes through the diagonal between two
                // directions a quarter turn apart (4 to 2 goes via 1). The
                // games need it: "214214" doesn't come out without it.
                before != null -> ROLL[before to dir]?.let { d -> repeat(timing.step) { frames += Frame(map(d), "") } }
            }
            // Up in the middle of a motion (Mary's 1236982) is only passed
            // through: held longer, the fighter jumps before the move registers.
            val through = dir in UP && k < parts.lastIndex && !charge
            repeat(if (charge) timing.charge else if (through) 1 else timing.step) { frames += Frame(map(dir), "") }
        }
        if (buttons.isNotEmpty()) {
            // Keep holding the final direction while the buttons go down: the
            // games read the button on the frame the motion completes.
            val last = parts.lastOrNull()?.let { map(it.dir) } ?: 5
            repeat(if (quick || quickNext) timing.quickPress else timing.press) { frames += Frame(last, buttons) }
        }
        // Let go between the inputs of a sequence, so "C,C" is two presses
        // ("C~A" goes straight on).
        if (i < steps.lastIndex && !quickNext) repeat(timing.gap) { frames += Frame(5, "") }
    }
    frames += Frame(5, "")
    return frames
}

private val ARROWS = mapOf(1 to "↙", 2 to "↓", 3 to "↘", 4 to "←", 5 to "•", 6 to "→", 7 to "↖", 8 to "↑", 9 to "↗")

/** "2141236+AC" -> "↓↙←↙↓↘→ AC", "D,B,C,C,8" -> "D B C C ↑", for labels and the editor. */
fun toArrows(command: String): String = try {
    val parsed = parseCommand(command)
    parsed.steps.joinToString(" ") { (parts, buttons) ->
        val dirs = parts.joinToString("") { if (it.charge) "[${ARROWS[it.dir]}]" else ARROWS[it.dir]!! }
        // In a sequence each input is one token ("→D"); otherwise "↓↘→ A".
        if (parsed.isSequence || dirs.isEmpty() || buttons.isEmpty()) dirs + buttons else "$dirs $buttons"
    }
} catch (e: CommandException) {
    command
}

data class DirFlags(val up: Boolean, val down: Boolean, val left: Boolean, val right: Boolean)

fun dirFlags(dir: Int) = DirFlags(
    up = dir == 7 || dir == 8 || dir == 9,
    down = dir == 1 || dir == 2 || dir == 3,
    left = dir == 1 || dir == 4 || dir == 7,
    right = dir == 3 || dir == 6 || dir == 9,
)

/**
 * Plays a compiled timeline against a frame clock. [tick] returns the frame to
 * apply, or null once finished. Advances at most one step per new clock value,
 * so a stutter can't eat a direction out of the motion.
 */
class MacroPlayer(private val frames: List<Frame>) {
    private var index = -1
    private var lastClock: Long? = null

    fun tick(clock: Long): Frame? {
        val last = lastClock
        if (last == null || clock > last) {
            index++
            lastClock = clock
        }
        return frames.getOrNull(index)
    }

    val done: Boolean get() = index >= frames.size
}
