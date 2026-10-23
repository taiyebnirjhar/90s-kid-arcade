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
//
// compile() turns a command into a per-frame timeline the pad plays back one
// emulated frame at a time, mirrored when your fighter faces left.

const val BUTTONS = "ABCD"

data class Part(val dir: Int, val charge: Boolean)

data class Command(val parts: List<Part>, val buttons: String)

/** One emulated frame of a macro: the stick direction and buttons held. */
data class Frame(val dir: Int, val buttons: String)

data class Timing(
    val step: Int = 2, // frames each direction is held
    val charge: Int = 50, // frames a [charge] direction is held
    val press: Int = 4, // frames the buttons are held
)

class CommandException(message: String) : IllegalArgumentException(message)

private val MIRROR = mapOf(1 to 3, 3 to 1, 4 to 6, 6 to 4, 7 to 9, 9 to 7, 2 to 2, 5 to 5, 8 to 8)

fun mirrorDir(dir: Int): Int = MIRROR[dir] ?: 5

/** Parses "2141236+AC". Throws [CommandException] with a readable message on bad input. */
fun parseCommand(command: String): Command {
    val src = command.uppercase().filterNot { it.isWhitespace() || it in ",~>" }
    if (src.isEmpty()) throw CommandException("Empty command")
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
    return Command(parts, buttons)
}

fun isValidCommand(command: String): Boolean = runCatching { parseCommand(command) }.isSuccess

/** The frame-by-frame timeline for [command]. The last frame is always neutral. */
fun compile(command: String, facingLeft: Boolean = false, timing: Timing = Timing()): List<Frame> {
    val (parts, buttons) = parseCommand(command)
    val map = { d: Int -> if (facingLeft) mirrorDir(d) else d }
    val frames = mutableListOf<Frame>()
    for ((dir, charge) in parts) {
        repeat(if (charge) timing.charge else timing.step) { frames += Frame(map(dir), "") }
    }
    if (buttons.isNotEmpty()) {
        // Keep holding the final direction while the buttons go down: the games
        // read the button on the frame the motion completes.
        val last = parts.lastOrNull()?.let { map(it.dir) } ?: 5
        repeat(timing.press) { frames += Frame(last, buttons) }
    }
    frames += Frame(5, "")
    return frames
}

private val ARROWS = mapOf(1 to "↙", 2 to "↓", 3 to "↘", 4 to "←", 5 to "•", 6 to "→", 7 to "↖", 8 to "↑", 9 to "↗")

/** "2141236+AC" -> "↓↙←↙↓↘→ AC", for labels and the editor. */
fun toArrows(command: String): String = try {
    val (parts, buttons) = parseCommand(command)
    val dirs = parts.joinToString("") { if (it.charge) "[${ARROWS[it.dir]}]" else ARROWS[it.dir]!! }
    listOf(dirs, buttons).filter { it.isNotEmpty() }.joinToString(" ")
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
