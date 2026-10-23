package com.kofarcade.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Draws a move's stick motion as an icon: the path the stick travels, as a
// curve around a circle that tightens as it goes (so 236236 is a visible
// double spiral, not 236 drawn twice), with an arrowhead at the end.
// Coordinates fit a 48x48 box; y points down like the screen.

data class Pt(val x: Float, val y: Float)

/** The icon for a move: the motion line, its arrowhead and, for charge moves, the hold dot. */
data class Glyph(val line: List<Pt>, val head: List<Pt>, val hold: Pt?)

private const val C = 24f // centre
private const val R = 17f // outer radius

// Numpad direction to an angle in degrees (screen coordinates, y down).
private val ANGLE = mapOf(6 to 0, 3 to 45, 2 to 90, 1 to 135, 4 to 180, 7 to 225, 8 to 270, 9 to 315)

private fun round1(v: Double) = (v * 10).roundToInt() / 10f

/**
 * Points along the motion. Each step turns the short way round the circle; a
 * 180° turn (charge back, then forward) cuts straight through the middle.
 */
fun motionPoints(command: String): List<Pt> {
    val dirs = parseCommand(command).parts.map { it.dir }.filter { it != 5 }
    if (dirs.isEmpty()) return emptyList()
    val n = dirs.size
    fun radius(i: Int) = R * (if (n > 3) 1 - 0.42f * i / (n - 1) else 1f)
    fun at(deg: Double, r: Float) = Pt(
        round1(C + cos(deg * PI / 180) * r),
        round1(C + sin(deg * PI / 180) * r),
    )
    if (n == 1) {
        // A single direction (or charge): an arrow from the centre outwards.
        val a = ANGLE.getValue(dirs[0]).toDouble()
        return listOf(at(a + 180, R * 0.55f), at(a, R))
    }
    val pts = mutableListOf(at(ANGLE.getValue(dirs[0]).toDouble(), radius(0)))
    for (i in 1 until n) {
        val a0 = ANGLE.getValue(dirs[i - 1])
        val delta = ((ANGLE.getValue(dirs[i]) - a0 + 540) % 360) - 180
        val r0 = radius(i - 1)
        val r1 = radius(i)
        if (abs(delta) == 180) {
            pts += at(ANGLE.getValue(dirs[i]).toDouble(), r1)
            continue
        }
        val steps = max(1, (abs(delta) / 15.0).roundToInt())
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            pts += at(a0 + delta * t.toDouble(), r0 + (r1 - r0) * t)
        }
    }
    return pts
}

// Centre the drawing and scale it to fill the icon, so a quarter circle isn't
// stuck small in one corner. Keeps proportions.
private fun fit(pts: List<Pt>, pad: Float = 9f): List<Pt> {
    val x0 = pts.minOf { it.x }
    val x1 = pts.maxOf { it.x }
    val y0 = pts.minOf { it.y }
    val y1 = pts.maxOf { it.y }
    val k = (48 - 2 * pad) / max(max(x1 - x0, y1 - y0), 12f)
    val ox = 24 - (x0 + x1) / 2 * k
    val oy = 24 - (y0 + y1) / 2 * k
    return pts.map { Pt(round1((it.x * k + ox).toDouble()), round1((it.y * k + oy).toDouble())) }
}

/** The icon for [command], fitted to a 48x48 box, or null for button-only commands. */
fun motionGlyph(command: String): Glyph? {
    val raw = motionPoints(command)
    if (raw.size < 2) return null
    val pts = fit(raw)
    val a = pts[pts.size - 2]
    val b = pts.last()
    val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
    val s = 6.0
    fun wing(off: Double) = Pt(round1(b.x - cos(ang + off) * s), round1(b.y - sin(ang + off) * s))
    val hold = if (parseCommand(command).parts.firstOrNull()?.charge == true) pts.first() else null
    return Glyph(pts, listOf(wing(0.55), b, wing(-0.55)), hold)
}

// ----- Layout helpers -----

/**
 * Centres for [count] buttons along an arc, starting at angle [from] and
 * [step] degrees apart (0 = right, 90 = up). Offsets from the arc centre, y down.
 */
fun arcPositions(count: Int, radius: Float, from: Float, step: Float): List<Pt> = (0 until count).map { i ->
    val a = (from + step * i) * PI / 180
    Pt((cos(a) * radius).roundToInt().toFloat() + 0f, (-sin(a) * radius).roundToInt().toFloat() + 0f)
}

/** Stick offset to a numpad direction in 45° sectors; inside [deadzone] is neutral (5). */
fun angleToDir(dx: Float, dy: Float, deadzone: Float): Int {
    if (dx * dx + dy * dy < deadzone * deadzone) return 5
    val a = atan2(-dy.toDouble(), dx.toDouble()) * 180 / PI
    val sector = (((a + 360) % 360) / 45).roundToInt() % 8
    return intArrayOf(6, 9, 8, 7, 4, 1, 2, 3)[sector]
}

internal fun clampF(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)
