package com.kofarcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The PSP's controls on the touch screen: the analog stick bottom left with
 * the D-pad above it, the four action buttons bottom right (triangle, circle,
 * cross, square), L and R in the top corners, SELECT and START bottom middle,
 * the menu top middle.
 * Several fingers at once; a finger that slides off a button lets it go.
 *
 * Buttons go out as Android key codes that LibretroDroid hands the core as
 * the RetroPad, whose B is PPSSPP's cross, A circle, Y square, X triangle.
 */
@SuppressLint("ViewConstructor")
class PspPad(
    context: Context,
    private val onKey: (keyCode: Int, down: Boolean) -> Unit,
    private val onStick: (x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {
    private class Button(val id: String, val label: String, val keyCode: Int, val color: Int, var rect: RectF = RectF(), val round: Boolean = true)

    private val d = resources.displayMetrics.density
    private val buttons = listOf(
        Button("tri", "△", KeyEvent.KEYCODE_BUTTON_X, Color.rgb(64, 226, 160)),
        Button("cir", "○", KeyEvent.KEYCODE_BUTTON_A, Color.rgb(255, 102, 102)),
        Button("cro", "✕", KeyEvent.KEYCODE_BUTTON_B, Color.rgb(124, 178, 255)),
        Button("squ", "□", KeyEvent.KEYCODE_BUTTON_Y, Color.rgb(255, 128, 210)),
        Button("l", "L", KeyEvent.KEYCODE_BUTTON_L1, Color.WHITE, round = false),
        Button("r", "R", KeyEvent.KEYCODE_BUTTON_R1, Color.WHITE, round = false),
        Button("sel", "SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, Color.WHITE, round = false),
        Button("sta", "START", KeyEvent.KEYCODE_BUTTON_START, Color.WHITE, round = false),
        Button("up", "▲", KeyEvent.KEYCODE_DPAD_UP, Color.WHITE),
        Button("down", "▼", KeyEvent.KEYCODE_DPAD_DOWN, Color.WHITE),
        Button("left", "◀", KeyEvent.KEYCODE_DPAD_LEFT, Color.WHITE),
        Button("right", "▶", KeyEvent.KEYCODE_DPAD_RIGHT, Color.WHITE),
    )
    private val menu = RectF()
    private var stickCx = 0f
    private var stickCy = 0f
    private var stickR = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var stickPointer = -1

    /** Which button each finger holds. */
    private val held = HashMap<Int, Button>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** See-through, so the game shows behind (0-1). */
    var opacity = 0.55f
        set(value) { field = value; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val u = min(w, h) / 7.2f // one button's size, from the short side
        val pad = 14 * d
        // Stick bottom left, D-pad above it.
        stickR = u * 1.05f
        stickCx = pad + stickR + u * 0.3f
        stickCy = h - pad - stickR - u * 0.15f
        knobX = stickCx; knobY = stickCy
        val dpCx = stickCx + u * 0.25f
        val dpCy = stickCy - stickR - u * 1.25f
        val dr = u * 0.36f
        place("up", dpCx, dpCy - dr * 1.6f, dr)
        place("down", dpCx, dpCy + dr * 1.6f, dr)
        place("left", dpCx - dr * 1.6f, dpCy, dr)
        place("right", dpCx + dr * 1.6f, dpCy, dr)
        // Action buttons bottom right, a diamond.
        val ar = u * 0.46f
        val acx = w - pad - ar * 3.3f
        val acy = h - pad - ar * 3.3f
        val gap = ar * 2.15f
        place("tri", acx, acy - gap, ar)
        place("cro", acx, acy + gap, ar)
        place("squ", acx - gap, acy, ar)
        place("cir", acx + gap, acy, ar)
        // Shoulders in the top corners, SELECT / START bottom middle, the menu top middle.
        val sw = u * 1.5f
        val sh = u * 0.62f
        rect("l", RectF(pad, pad, pad + sw, pad + sh))
        rect("r", RectF(w - pad - sw, pad, w - pad, pad + sh))
        val bw = u * 1.15f
        val bh = u * 0.44f
        rect("sel", RectF(w / 2f - bw - u * 0.15f, h - pad - bh, w / 2f - u * 0.15f, h - pad))
        rect("sta", RectF(w / 2f + u * 0.15f, h - pad - bh, w / 2f + bw + u * 0.15f, h - pad))
        val m = u * 0.32f
        menu.set(w / 2f - m, pad, w / 2f + m, pad + m * 2)
    }

    private fun place(id: String, cx: Float, cy: Float, r: Float) {
        buttons.first { it.id == id }.rect = RectF(cx - r, cy - r, cx + r, cy + r)
    }

    private fun rect(id: String, r: RectF) {
        buttons.first { it.id == id }.rect = r
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
        canvas.drawCircle(knobX, knobY, stickR * 0.42f, fill)
        for (b in buttons) {
            val down = b in held.values
            fill.color = if (down) Color.argb(a, Color.red(b.color), Color.green(b.color), Color.blue(b.color)) else Color.argb(a / 2, 0, 0, 0)
            ring.color = Color.argb(a, Color.red(b.color), Color.green(b.color), Color.blue(b.color))
            if (b.round) {
                canvas.drawCircle(b.rect.centerX(), b.rect.centerY(), b.rect.width() / 2, fill)
                canvas.drawCircle(b.rect.centerX(), b.rect.centerY(), b.rect.width() / 2, ring)
            } else {
                val rr = b.rect.height() / 2
                canvas.drawRoundRect(b.rect, rr, rr, fill)
                canvas.drawRoundRect(b.rect, rr, rr, ring)
            }
            text.color = Color.argb(a, 255, 255, 255)
            text.textSize = if (b.label.length > 1) b.rect.height() * 0.42f else b.rect.height() * 0.55f
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
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
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val id = e.getPointerId(i)
                if (menu.contains(x, y)) {
                    onMenu()
                } else if (stickPointer < 0 && hypot(x - stickCx, y - stickCy) < stickR * 1.6f) {
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

    private fun hit(x: Float, y: Float): Button? {
        val slop = 8 * d
        return buttons.firstOrNull { b ->
            val r = b.rect
            if (b.round) hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2 + slop
            else x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        }
    }

    private fun press(pointer: Int, b: Button) {
        held[pointer] = b
        onKey(b.keyCode, true)
        invalidate()
    }

    private fun release(pointer: Int) {
        val b = held.remove(pointer) ?: return
        // Another finger may still hold the same button.
        if (b !in held.values) onKey(b.keyCode, false)
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
