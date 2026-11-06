package com.kofarcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * What a PSP game's buttons do, for its controls: names on the buttons,
 * buttons it doesn't use (hidden), and combo buttons that press several at
 * once (God of War's roll is L and R together, steered with the stick).
 * Button ids: tri cir cro squ l r sel sta.
 */
data class PspProfile(
    val labels: Map<String, String> = emptyMap(),
    val combos: List<PspCombo> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    companion object {
        /** God of War (Chains of Olympus, Ghost of Sparta). */
        val GOD_OF_WAR = PspProfile(
            labels = mapOf("squ" to "Light", "tri" to "Heavy", "cir" to "Grab", "cro" to "Jump", "r" to "Block"),
            combos = listOf(PspCombo("roll", "Roll", "L+R", listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1))),
            hidden = setOf("sel"),
        )

        /** The profile for a game, from its title. */
        fun forTitle(title: String): PspProfile = when {
            title.contains("god of war", ignoreCase = true) -> GOD_OF_WAR
            else -> PspProfile()
        }
    }
}

/** A button that holds several PSP buttons at once while pressed. */
data class PspCombo(val id: String, val label: String, val hint: String, val keyCodes: List<Int>)

/**
 * The PSP's controls on the touch screen, kept to the screen's edges so the
 * game shows: the stick bottom left with the D-pad above it, the action
 * buttons bottom right (triangle, circle, cross, square), L above the stick
 * and R above the action buttons where the thumbs reach, combo buttons beside
 * them, SELECT and START small at the bottom middle, the menu top middle.
 *
 * In [editing] mode a finger drags a group (the stick, the D-pad, the action
 * buttons, or one button) to where the player wants it; [moves] keeps where
 * each went, as a fraction of the screen.
 *
 * Buttons go out as Android key codes that LibretroDroid hands the core as
 * the RetroPad, whose B is PPSSPP's cross, A circle, Y square, X triangle.
 */
@SuppressLint("ViewConstructor")
class PspPad(
    context: Context,
    private val profile: PspProfile,
    private val onKey: (keyCode: Int, down: Boolean) -> Unit,
    private val onStick: (x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {
    private class Button(
        val id: String,
        val group: String,
        val label: String,
        val keyCodes: List<Int>,
        val color: Int,
        val round: Boolean = true,
        val hint: String? = null,
        var rect: RectF = RectF(),
    )

    private val d = resources.displayMetrics.density
    private val buttons: List<Button> = buildList {
        fun face(id: String, symbol: String, code: Int, color: Int) = Button(id, "face", symbol, listOf(code), color, hint = profile.labels[id])
        add(face("tri", "△", KeyEvent.KEYCODE_BUTTON_X, Color.rgb(64, 226, 160)))
        add(face("cir", "○", KeyEvent.KEYCODE_BUTTON_A, Color.rgb(255, 102, 102)))
        add(face("cro", "✕", KeyEvent.KEYCODE_BUTTON_B, Color.rgb(124, 178, 255)))
        add(face("squ", "□", KeyEvent.KEYCODE_BUTTON_Y, Color.rgb(255, 128, 210)))
        add(Button("l", "l", "L", listOf(KeyEvent.KEYCODE_BUTTON_L1), Color.WHITE, round = false, hint = profile.labels["l"]))
        add(Button("r", "r", "R", listOf(KeyEvent.KEYCODE_BUTTON_R1), Color.WHITE, round = false, hint = profile.labels["r"]))
        add(Button("sel", "sel", "SELECT", listOf(KeyEvent.KEYCODE_BUTTON_SELECT), Color.WHITE, round = false))
        add(Button("sta", "sta", "START", listOf(KeyEvent.KEYCODE_BUTTON_START), Color.WHITE, round = false))
        for ((id, s, k) in listOf(
            Triple("up", "▲", KeyEvent.KEYCODE_DPAD_UP), Triple("down", "▼", KeyEvent.KEYCODE_DPAD_DOWN),
            Triple("left", "◀", KeyEvent.KEYCODE_DPAD_LEFT), Triple("right", "▶", KeyEvent.KEYCODE_DPAD_RIGHT),
        )) add(Button(id, "dpad", s, listOf(k), Color.WHITE))
        for (c in profile.combos) add(Button(c.id, c.id, c.label, c.keyCodes, Color.rgb(232, 178, 26), round = false, hint = c.hint))
    }.filter { it.id !in profile.hidden }

    private val menu = RectF()
    private var stickCx = 0f
    private var stickCy = 0f
    private var stickR = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var stickPointer = -1

    /** Which button each finger holds. */
    private val held = HashMap<Int, Button>()

    /** Groups moved by the player: group to its centre, as a fraction of the screen. */
    val moves = HashMap<String, PointF>()

    /** Dragging groups about instead of playing. */
    var editing = false
        set(value) { field = value; releaseAll(); invalidate() }
    private var dragGroup: String? = null
    private var dragPointer = -1

    /** Bigger or smaller controls (0.7-1.4). */
    var scale = 1f
        set(value) { field = value; relayout() }

    /** See-through, so the game shows behind (0-1). */
    var opacity = 0.45f
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    /** Puts every control in its spot: the defaults at the edges, then the player's moves. */
    fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val u = min(w, h) / 7.6f * scale // one button's size, from the short side
        val pad = 10 * d
        // Stick: bottom left corner.
        stickR = u * 0.95f
        stickCx = pad + stickR + u * 0.15f
        stickCy = h - pad - stickR - u * 0.1f
        // D-pad: above the stick, against the left edge.
        val dr = u * 0.32f
        val dpCx = pad + dr * 2.8f
        val dpCy = stickCy - stickR - u * 1.2f
        place("up", dpCx, dpCy - dr * 1.65f, dr)
        place("down", dpCx, dpCy + dr * 1.65f, dr)
        place("left", dpCx - dr * 1.65f, dpCy, dr)
        place("right", dpCx + dr * 1.65f, dpCy, dr)
        // Action buttons: a diamond in the bottom right corner.
        val ar = u * 0.44f
        val gap = ar * 2.1f
        val acx = w - pad - ar - gap
        val acy = h - pad - ar - gap
        place("tri", acx, acy - gap, ar)
        place("cro", acx, acy + gap, ar)
        place("squ", acx - gap, acy, ar)
        place("cir", acx + gap, acy, ar)
        // L and R: where the thumbs reach, above the stick and the action buttons.
        val sw = u * 1.25f
        val sh = u * 0.55f
        rect("l", RectF(pad, dpCy - dr * 3f - sh - u * 0.2f, pad + sw, dpCy - dr * 3f - u * 0.2f))
        val rTop = acy - gap - ar - u * 0.35f - sh
        rect("r", RectF(w - pad - sw, rTop, w - pad, rTop + sh))
        // Combos: beside R, towards the middle.
        var cx = w - pad - sw - u * 0.25f
        for (c in profile.combos) {
            rect(c.id, RectF(cx - sw, rTop, cx, rTop + sh))
            cx -= sw + u * 0.25f
        }
        // SELECT and START: small, bottom middle.
        val bw = u * 0.95f
        val bh = u * 0.38f
        rect("sel", RectF(w / 2f - bw - u * 0.12f, h - pad - bh, w / 2f - u * 0.12f, h - pad))
        rect("sta", RectF(w / 2f + u * 0.12f, h - pad - bh, w / 2f + bw + u * 0.12f, h - pad))
        val m = u * 0.28f
        menu.set(w / 2f - m, pad * 0.6f, w / 2f + m, pad * 0.6f + m * 2)
        // The player's own spots.
        for ((group, at) in moves) moveGroupTo(group, at.x * w, at.y * h)
        knobX = stickCx; knobY = stickCy
        invalidate()
    }

    private fun place(id: String, cx: Float, cy: Float, r: Float) {
        buttons.firstOrNull { it.id == id }?.rect = RectF(cx - r, cy - r, cx + r, cy + r)
    }

    private fun rect(id: String, r: RectF) {
        buttons.firstOrNull { it.id == id }?.rect = r
    }

    private fun groupCenter(group: String): PointF {
        if (group == "stick") return PointF(stickCx, stickCy)
        val rs = buttons.filter { it.group == group }.map { it.rect }
        if (rs.isEmpty()) return PointF()
        val u = RectF(rs[0]).apply { rs.forEach { union(it) } }
        return PointF(u.centerX(), u.centerY())
    }

    private fun moveGroupTo(group: String, x: Float, y: Float) {
        val c = groupCenter(group)
        val dx = x - c.x
        val dy = y - c.y
        if (group == "stick") {
            stickCx += dx; stickCy += dy
        } else {
            buttons.filter { it.group == group }.forEach { it.rect.offset(dx, dy) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val a = (255 * opacity).toInt()
        // Stick.
        fill.color = Color.argb(a / 3, 0, 0, 0)
        canvas.drawCircle(stickCx, stickCy, stickR, fill)
        ring.color = Color.argb(a, 255, 255, 255)
        ring.strokeWidth = 2 * d
        canvas.drawCircle(stickCx, stickCy, stickR, ring)
        fill.color = Color.argb(a, 255, 255, 255)
        canvas.drawCircle(knobX, knobY, stickR * 0.4f, fill)
        for (b in buttons) {
            val down = b in held.values
            val c = Color.argb(a, Color.red(b.color), Color.green(b.color), Color.blue(b.color))
            fill.color = if (down) c else Color.argb(a / 2, 0, 0, 0)
            ring.color = c
            if (b.round) {
                canvas.drawCircle(b.rect.centerX(), b.rect.centerY(), b.rect.width() / 2, fill)
                canvas.drawCircle(b.rect.centerX(), b.rect.centerY(), b.rect.width() / 2, ring)
            } else {
                val rr = b.rect.height() / 2
                canvas.drawRoundRect(b.rect, rr, rr, fill)
                canvas.drawRoundRect(b.rect, rr, rr, ring)
            }
            text.color = Color.argb(a, 255, 255, 255)
            val hint = b.hint
            if (hint != null) {
                // The symbol with what it does under it ("□ Light"), or a combo's name with its keys.
                text.textSize = b.rect.height() * if (b.round) 0.42f else 0.36f
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - b.rect.height() * 0.04f, text)
                text.textSize = b.rect.height() * 0.22f
                canvas.drawText(hint, b.rect.centerX(), b.rect.centerY() + b.rect.height() * 0.3f, text)
            } else {
                text.textSize = if (b.label.length > 1) b.rect.height() * 0.4f else b.rect.height() * 0.55f
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
        }
        // Menu: three bars.
        fill.color = Color.argb(a / 2, 0, 0, 0)
        canvas.drawRoundRect(menu, menu.width() / 2, menu.width() / 2, fill)
        ring.color = Color.argb(a, 255, 255, 255)
        canvas.drawRoundRect(menu, menu.width() / 2, menu.width() / 2, ring)
        val bar = menu.width() * 0.5f
        for (i in -1..1) {
            val y = menu.centerY() + i * menu.height() * 0.17f
            canvas.drawLine(menu.centerX() - bar / 2, y, menu.centerX() + bar / 2, y, ring)
        }
        if (editing) {
            text.textSize = 15 * d
            text.color = Color.rgb(232, 178, 26)
            canvas.drawText("Drag the controls where you want them · ☰ when done", width / 2f, height / 2f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val id = e.getPointerId(i)
                if (menu.contains(x, y)) {
                    onMenu()
                } else if (stickPointer < 0 && hypot(x - stickCx, y - stickCy) < stickR * 1.5f) {
                    stickPointer = id
                    moveStick(x, y)
                } else {
                    hit(x, y)?.let { press(id, it) }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (id == stickPointer) {
                    moveStick(x, y)
                } else {
                    val now = hit(x, y)
                    val was = held[id]
                    if (now !== was) {
                        was?.let { release(id) }
                        now?.let { press(id, it) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = e.actionMasked == MotionEvent.ACTION_CANCEL
                val ids = if (all) (0 until e.pointerCount).map(e::getPointerId) else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    if (id == stickPointer) {
                        stickPointer = -1
                        knobX = stickCx; knobY = stickCy
                        onStick(0f, 0f)
                    }
                    release(id)
                }
                invalidate()
            }
        }
        return true
    }

    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x
                val y = e.y
                if (menu.contains(x, y)) {
                    onMenu()
                    return true
                }
                dragGroup = when {
                    hypot(x - stickCx, y - stickCy) < stickR -> "stick"
                    else -> hit(x, y)?.group
                }
                dragPointer = e.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> dragGroup?.let { g ->
                val i = e.findPointerIndex(dragPointer).takeIf { it >= 0 } ?: return true
                val x = e.getX(i).coerceIn(0f, width.toFloat())
                val y = e.getY(i).coerceIn(0f, height.toFloat())
                moveGroupTo(g, x, y)
                moves[g] = PointF(x / width, y / height)
                knobX = stickCx; knobY = stickCy
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragGroup = null
        }
        return true
    }

    private fun hit(x: Float, y: Float): Button? {
        val slop = 8 * d
        return buttons.firstOrNull { b ->
            val r = b.rect
            if (b.round) hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2 + slop
            else x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        }
    }

    private fun keysHeld(): Set<Int> = held.values.flatMap { it.keyCodes }.toSet()

    private fun press(pointer: Int, b: Button) {
        val before = keysHeld()
        held[pointer] = b
        for (k in b.keyCodes) if (k !in before) onKey(k, true)
        invalidate()
    }

    private fun release(pointer: Int) {
        val b = held.remove(pointer) ?: return
        // Another finger may still hold the same button (or a combo with it).
        val still = keysHeld()
        for (k in b.keyCodes) if (k !in still) onKey(k, false)
        invalidate()
    }

    private fun moveStick(x: Float, y: Float) {
        var dx = x - stickCx
        var dy = y - stickCy
        val dist = hypot(dx, dy)
        if (dist > stickR) {
            dx = dx / dist * stickR
            dy = dy / dist * stickR
        }
        knobX = stickCx + dx
        knobY = stickCy + dy
        onStick(dx / stickR, dy / stickR)
        invalidate()
    }

    /** Lets every held button go (the screen lost focus, the menu opened). */
    fun releaseAll() {
        for (id in held.keys.toList()) release(id)
        if (stickPointer >= 0) {
            stickPointer = -1
            knobX = stickCx; knobY = stickCy
            onStick(0f, 0f)
        }
        invalidate()
    }
}
