package com.kofarcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.angleToDir
import com.kofarcade.core.arcPositions
import com.kofarcade.core.mirrorDir
import com.kofarcade.core.motionGlyph
import com.kofarcade.core.parseCommand
import com.kofarcade.core.toArrows
import kotlin.math.hypot
import kotlin.math.min

/**
 * The in-game touch controls, drawn on one view so every finger is tracked
 * together: an 8-way stick on the left; A B C D with the character's moves on
 * a ring around them and one-tap system buttons (MAX, roll, ...) on an inner
 * ring on the right; coin / facing / start along the top; and, for fighters,
 * the team bar of three portraits on the left.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onDir(dir: Int)
        fun onKey(key: Key, down: Boolean)

        /** Plays a command; returns how many frames it takes. */
        fun onPlay(cmd: String): Int
        fun onMenu()
        fun onFlip()
        fun onSlot(index: Int)
        fun onRapid()

        /** Block button: 4 = block (back), 1 = low block (down-back), null = let go. */
        fun onBlock(relDir: Int?)

        /** Run: held from the Run button, or the stick pushed to its edge. */
        fun onRun(on: Boolean)
    }

    /** A one-tap button on the inner ring. [cmd] null means a toggle (rapid fire). */
    data class SysButton(
        val name: String,
        val sub: String,
        val cmd: String?,
        val power: Boolean = false,
        val on: Boolean = false,
        /** Works while held (Run) instead of on a tap. */
        val hold: Boolean = false,
    )

    data class Slot(val name: String?, val active: Boolean)

    /** Your change to one control's spot: offset in dp from its default, and size. */
    data class Tweak(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f)

    /**
     * Per-control layout changes, keyed by control id ("stick", "face:A",
     * "move:0", "sys:1", "team", "top", "pause"). Set from saved settings.
     */
    var tweaks: Map<String, Tweak> = emptyMap()
        set(value) {
            field = value
            refresh()
        }

    /** Layout editor: drag controls to move them, pick one to resize it. */
    var editing = false
        set(value) {
            field = value
            selected = null
            releaseAll()
            refresh()
        }

    /** The control picked in the editor, or null. */
    var selected: String? = null
        private set

    /** Told when the editor's selection changes, so the size slider can follow. */
    var onSelect: ((String?) -> Unit)? = null

    fun setScale(id: String, scale: Float) {
        tweaks = tweaks + (id to (tweaks[id] ?: Tweak()).copy(scale = scale))
    }

    /** Whether a control can be resized (groups of pills only move). */
    fun resizable(id: String?) = id != null && id != "top" && id != "team"

    /** Everything the HUD shows; set by the game screen, then call [refresh]. */
    data class State(
        val layout: Layout = Layout.FIGHTER,
        val faceButtons: List<Char> = listOf('A', 'B', 'C', 'D'),
        val labels: Map<Char, String> = emptyMap(),
        val moves: List<Move> = emptyList(),
        val system: List<SysButton> = emptyList(),
        val slots: List<Slot> = emptyList(),
        /** The FACING button; not needed while the game's RAM says which way you face. */
        val facingButton: Boolean = true,
        val facingLeft: Boolean = false,
        val size: Float = 1f,
        val leftHanded: Boolean = false,
        val labelMode: LabelMode = LabelMode.SUPERS,
        val stick: StickMode = StickMode.FLOATING,
        val haptics: Boolean = true,
        val block: Boolean = false,
        /** Pushing the stick to its edge left or right runs. */
        val run: Boolean = false,
    )

    var state = State()
        set(value) {
            val old = field
            field = value
            // Flipping sides only changes how things are drawn; anything else
            // moves buttons around, so lay out again.
            if (old.copy(facingLeft = value.facingLeft) == value) invalidate() else refresh()
        }

    private enum class Kind { PAUSE, COIN, FACING, START, SLOT, FACE, SKILL, SYS, BLOCK }

    private class El(
        val id: String,
        val kind: Kind,
        val rect: RectF,
        val round: Boolean,
        val key: Key? = null,
        val letter: Char? = null,
        val move: Move? = null,
        val sys: SysButton? = null,
        val index: Int = -1,
        val glyph: Path? = null,
        val buttons: String = "",
    ) {
        val cx get() = rect.centerX()
        val cy get() = rect.centerY()
        val r get() = rect.width() / 2
    }

    private val d = resources.displayMetrics.density
    private var elements = listOf<El>()
    private val pressed = mutableSetOf<El>()
    private val firing = mutableMapOf<El, Pair<Long, Int>>() // element -> start frame, length
    private var frame = 0L

    // Stick
    private var stickPointer = -1
    private var stickCx = 0f
    private var stickCy = 0f
    private var knobDx = 0f
    private var knobDy = 0f
    private var lastDir = 5
    private var homeX = 0f
    private var homeY = 0f
    private val pointerEl = HashMap<Int, El>()

    // ----- Paints -----

    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(92, 12, 14, 24) }
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = Color.argb(56, 255, 255, 255)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val accent = Color.rgb(255, 207, 77)
    private val superColor = Color.rgb(255, 179, 71)
    private val keyColors = mapOf(
        'A' to Color.rgb(255, 90, 95),
        'B' to Color.rgb(255, 201, 40),
        'C' to Color.rgb(52, 196, 124),
        'D' to Color.rgb(74, 157, 240),
        'E' to Color.rgb(176, 112, 240),
        'F' to Color.rgb(255, 140, 50),
    )
    private val keyFor = mapOf('A' to Key.A, 'B' to Key.B, 'C' to Key.C, 'D' to Key.D, 'E' to Key.E, 'F' to Key.F)

    /** A button's name as the game calls it ("LP"), or its letter. */
    private fun buttonName(c: Char): String = state.labels[c]?.takeIf { it.length <= 2 } ?: c.toString()

    init {
        isHapticFeedbackEnabled = true
    }

    fun refresh() {
        if (width > 0) layoutElements()
        invalidate()
    }

    /** Called once per emulated frame, to animate the move sweep. */
    fun onFrame(frame: Long) {
        this.frame = frame
        if (firing.isNotEmpty()) {
            firing.entries.removeAll { (_, v) -> frame - v.first > v.second + 6 }
            invalidate()
        }
    }

    /** Releases every finger, e.g. when a dialog opens over the controls. */
    fun releaseAll() {
        if (blockPointer != -1) listener.onBlock(null)
        blockPointer = -1
        blockLow = false
        pointerEl.clear()
        pressed.clear()
        stickPointer = -1
        knobDx = 0f
        knobDy = 0f
        lastDir = 5
        resetStick()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutElements()
    }

    // ----- Layout -----

    private fun safeInsets(): Pair<Float, Float> {
        val cutout = rootWindowInsets?.displayCutout
        return (cutout?.safeInsetLeft ?: 0).toFloat() to (cutout?.safeInsetRight ?: 0).toFloat()
    }

    private fun tweak(id: String) = tweaks[id] ?: Tweak()

    // A round control at its default spot, moved / resized by your layout and
    // kept on screen.
    private fun placed(id: String, cx: Float, cy: Float, r: Float): RectF {
        val t = tweak(id)
        val rr = r * t.scale
        val x = (cx + t.dx * d).let { if (width > 2 * rr) it.coerceIn(rr, width - rr) else it }
        val y = (cy + t.dy * d).let { if (height > 2 * rr) it.coerceIn(rr, height - rr) else it }
        return circle(x, y, rr)
    }

    private fun placed(id: String, rect: RectF): RectF {
        val t = tweak(id)
        return RectF(rect).apply { offset(t.dx * d, t.dy * d) }
    }

    private fun layoutElements() {
        val st = state
        val (safeL, safeR) = safeInsets()
        val w = width.toFloat()
        val h = height.toFloat()
        val els = mutableListOf<El>()
        val sc = st.size

        // Top bar
        val top = 8 * d
        els += El("pause", Kind.PAUSE, placed("pause", safeL + 12 * d + 17 * d, top + 17 * d, 17 * d), true)
        text.textSize = 10 * d
        val pills = buildList {
            add(Kind.COIN to "COIN")
            if (st.layout == Layout.FIGHTER && st.facingButton) add(Kind.FACING to "FACING")
            add(Kind.START to "START")
        }
        text.letterSpacing = 0.12f
        val pillW = pills.map { (k, t) -> text.measureText(t) + 24 * d + if (k == Kind.FACING) 19 * d else 0f }
        text.letterSpacing = 0f
        var x = w / 2 - (pillW.sum() + 8 * d * (pills.size - 1)) / 2
        pills.forEachIndexed { i, (k, _) ->
            els += El(
                "top", k, placed("top", RectF(x, top + 4 * d, x + pillW[i], top + 30 * d)), false,
                key = when (k) {
                    Kind.COIN -> Key.COIN
                    Kind.START -> Key.START
                    else -> null
                },
            )
            x += pillW[i] + 8 * d
        }

        // Team bar
        st.slots.forEachIndexed { i, _ ->
            val y = 54 * d + i * 40 * d
            els += El("team", Kind.SLOT, placed("team", RectF(safeL + 12 * d, y, safeL + 12 * d + 96 * d, y + 34 * d)), false, index = i)
        }

        // Button cluster: A B C D, moves on an outer ring, system on an inner one.
        val s = 62 * d * sc
        val mirror = if (st.leftHanded) -1f else 1f
        val ax = if (st.leftHanded) safeL + 1.3f * s + 16 * d else w - safeR - 1.3f * s - 16 * d
        val ay = h - s - 16 * d
        val faceR = s / 2 * if (st.layout == Layout.SHOOTER) 1.12f else 1f
        val hh = s / 2 + 5 * d
        val facePos = if (st.faceButtons.size == 6) {
            // Capcom's arcade layout: punches on top, kicks below, light to heavy.
            val sx = 0.98f * s
            mapOf(
                'A' to (-1.2f * sx to -hh), 'B' to (-0.2f * sx to -hh * 1.12f), 'C' to (0.8f * sx to -hh * 1.12f),
                'D' to (-1.0f * sx to hh), 'E' to (0f to hh * 0.88f), 'F' to (1.0f * sx to hh * 0.88f),
            )
        } else {
            mapOf(
                'C' to (-0.3f * s - 5 * d to -hh),
                'D' to (0.7f * s + 5 * d to -hh),
                'A' to (-0.7f * s - 5 * d to hh),
                'B' to (0.3f * s + 5 * d to hh),
            )
        }
        for (c in st.faceButtons) {
            val (fx, fy) = facePos.getValue(c)
            els += El("face:$c", Kind.FACE, placed("face:$c", ax + fx * mirror, ay + fy, faceR), true, key = keyFor[c], letter = c)
        }
        val skillR = 26 * d * sc
        arcPositions(st.moves.size, 3 * s, 72f, 23f).forEachIndexed { i, p ->
            val m = st.moves[i]
            val rect = placed("move:$i", ax + p.x * mirror, ay + p.y, skillR)
            els += El(
                "move:$i", Kind.SKILL, rect, true, move = m,
                glyph = glyphPath(m.cmd, rect.centerX(), rect.centerY(), rect.width() / 2 * 0.84f),
                // Sequences show their inputs as text, so no separate button chips.
                buttons = runCatching { parseCommand(m.cmd) }.getOrNull()
                    ?.takeUnless { it.isSequence }?.buttons.orEmpty(),
            )
        }
        // Block: left of A, where the thumb already is. Slide down for low block.
        if (st.block) {
            els += El("block", Kind.BLOCK, placed("block", ax + (-1.85f * s) * mirror, ay + hh, 25 * d * sc), true)
        }
        val sysR = 22 * d * sc
        arcPositions(st.system.size, 1.72f * s, 104f, 34f).forEachIndexed { i, p ->
            els += El("sys:$i", Kind.SYS, placed("sys:$i", ax + p.x * mirror, ay + p.y, sysR), true, sys = st.system[i])
        }

        // Stick home
        val home = placed(
            "stick",
            if (st.leftHanded) w - safeR - 112 * d * sc else safeL + 112 * d * sc,
            h - 104 * d * sc,
            64 * d * sc,
        )
        homeX = home.centerX()
        homeY = home.centerY()

        // Buttons moved: let go of anything a finger was holding on the old layout.
        if (blockPointer != -1) {
            listener.onBlock(null)
            blockPointer = -1
            blockLow = false
        }
        for (el in pointerEl.values) {
            if (el.kind == Kind.FACE || el.kind == Kind.COIN || el.kind == Kind.START) listener.onKey(el.key!!, false)
        }
        pressed.clear()
        firing.clear()
        pointerEl.clear()
        elements = els
        if (stickPointer == -1) resetStick()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)

    private fun glyphPath(cmd: String, cx: Float, cy: Float, half: Float): Path? {
        val g = motionGlyph(cmd) ?: return null
        val k = 2 * half / 48f
        val ox = cx - half
        val oy = cy - half
        val path = Path()
        g.line.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(ox + p.x * k, oy + p.y * k) else path.lineTo(ox + p.x * k, oy + p.y * k)
        }
        for (head in g.heads) {
            head.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(ox + p.x * k, oy + p.y * k) else path.lineTo(ox + p.x * k, oy + p.y * k)
            }
        }
        g.hold?.let { path.addCircle(ox + it.x * k, oy + it.y * k, 3.4f * k, Path.Direction.CW) }
        return path
    }

    private fun resetStick() {
        stickCx = homeX
        stickCy = homeY
    }

    // ----- Drawing -----

    override fun onDraw(canvas: Canvas) {
        val st = state
        drawStick(canvas)
        for (el in elements) {
            when (el.kind) {
                Kind.PAUSE -> drawPause(canvas, el)
                Kind.COIN, Kind.START, Kind.FACING -> drawPill(canvas, el)
                Kind.SLOT -> drawSlot(canvas, el, st.slots.getOrNull(el.index))
                Kind.FACE -> drawFace(canvas, el)
                Kind.SKILL -> drawSkill(canvas, el)
                Kind.SYS -> drawSys(canvas, el)
                Kind.BLOCK -> drawBlock(canvas, el)
            }
        }
        if (editing) {
            drawEditor(canvas)
            return
        }
        if (st.slots.isNotEmpty() && st.slots.all { it.name == null }) {
            text.textSize = 12 * d
            text.textAlign = Paint.Align.LEFT
            text.setShadowLayer(4 * d, 0f, 1 * d, Color.BLACK)
            canvas.drawText("Tap a slot to pick your fighter and get their moves", safeInsets().first + 132 * d, 78 * d, text)
            text.clearShadowLayer()
            text.textAlign = Paint.Align.CENTER
        }
    }

    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }

    // Outlines every control (gold for the picked one) and a hint line.
    private fun drawEditor(canvas: Canvas) {
        fill.color = Color.argb(70, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val outlines = elements.groupBy { it.id }.map { (id, els) ->
            id to RectF(els.first().rect).apply { els.forEach { union(it.rect) } }
        } + ("stick" to circle(homeX, homeY, stickRadius()))
        for ((id, r) in outlines) {
            val sel = id == selected
            dashed.color = if (sel) accent else Color.argb(150, 255, 255, 255)
            dashed.strokeWidth = (if (sel) 2.5f else 1.5f) * d
            val pad = 4 * d
            canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, 12 * d, 12 * d, dashed)
        }
        text.setShadowLayer(4 * d, 0f, d, Color.BLACK)
        centeredText(canvas, "Drag any control to move it · tap one to resize it", width / 2f, height - 18 * d, 12 * d)
        text.clearShadowLayer()
    }

    private fun glass(canvas: Canvas, el: El, stroke: Int? = null, strokeWidth: Float = 1.5f * d) {
        if (el.round) {
            canvas.drawCircle(el.cx, el.cy, el.r, if (el in pressed) pressedFill() else glassFill)
            ring.color = stroke ?: glassStroke.color
            ring.strokeWidth = strokeWidth
            canvas.drawCircle(el.cx, el.cy, el.r - strokeWidth / 2, ring)
        } else {
            val rr = el.rect.height() / 2
            canvas.drawRoundRect(el.rect, rr, rr, if (el in pressed) pressedFill() else glassFill)
            ring.color = stroke ?: glassStroke.color
            ring.strokeWidth = strokeWidth
            canvas.drawRoundRect(el.rect, rr, rr, ring)
        }
    }

    private fun pressedFill() = fill.apply { color = Color.argb(90, 255, 255, 255) }

    private fun centeredText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int = Color.WHITE) {
        text.textSize = size
        text.color = color
        canvas.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
        text.color = Color.WHITE
    }

    private fun drawPause(canvas: Canvas, el: El) {
        glass(canvas, el)
        fill.color = Color.WHITE
        val bw = 3.4f * d
        val bh = 11 * d
        canvas.drawRoundRect(el.cx - 4.5f * d - bw / 2, el.cy - bh / 2, el.cx - 4.5f * d + bw / 2, el.cy + bh / 2, d, d, fill)
        canvas.drawRoundRect(el.cx + 4.5f * d - bw / 2, el.cy - bh / 2, el.cx + 4.5f * d + bw / 2, el.cy + bh / 2, d, d, fill)
    }

    private fun drawPill(canvas: Canvas, el: El) {
        val facing = el.kind == Kind.FACING
        glass(canvas, el, stroke = if (facing) Color.argb(150, 255, 207, 77) else null)
        val label = when (el.kind) {
            Kind.COIN -> "COIN"
            Kind.START -> "START"
            else -> "FACING"
        }
        text.letterSpacing = 0.12f
        if (facing) {
            // Arrow showing which way your fighter faces.
            val ax = el.rect.left + 16 * d
            val dir = if (state.facingLeft) -1f else 1f
            glyphPaint.color = accent
            glyphPaint.strokeWidth = 2.2f * d
            val p = Path().apply {
                moveTo(ax - 6 * d * dir, el.cy)
                lineTo(ax + 6 * d * dir, el.cy)
                moveTo(ax + 2 * d * dir, el.cy - 4 * d)
                lineTo(ax + 6 * d * dir, el.cy)
                lineTo(ax + 2 * d * dir, el.cy + 4 * d)
            }
            canvas.drawPath(p, glyphPaint)
            centeredText(canvas, label, el.cx + 9 * d, el.cy, 10 * d, accent)
        } else {
            centeredText(canvas, label, el.cx, el.cy, 10 * d)
        }
        text.letterSpacing = 0f
    }

    private fun drawSlot(canvas: Canvas, el: El, slot: Slot?) {
        val tile = RectF(el.rect.left, el.rect.top, el.rect.left + 34 * d, el.rect.bottom)
        val name = slot?.name
        val active = slot?.active == true
        if (name == null) {
            canvas.drawRoundRect(tile, 10 * d, 10 * d, glassFill)
            ring.color = Color.argb(110, 255, 255, 255)
            ring.strokeWidth = 1.5f * d
            canvas.drawRoundRect(tile, 10 * d, 10 * d, ring)
            centeredText(canvas, "+", tile.centerX(), tile.centerY(), 18 * d, Color.argb(190, 255, 255, 255))
        } else {
            val hue = nameHue(name)
            val inset = if (active) 0f else 3 * d
            val t = RectF(tile.left + inset, tile.top + inset, tile.right - inset, tile.bottom - inset)
            fill.shader = LinearGradient(
                t.left, t.top, t.right, t.bottom,
                Color.HSVToColor(floatArrayOf(hue, 0.7f, if (active) 0.8f else 0.45f)),
                Color.HSVToColor(floatArrayOf((hue + 30) % 360, 0.65f, if (active) 0.35f else 0.22f)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(t, 10 * d, 10 * d, fill)
            fill.shader = null
            ring.color = if (active) accent else Color.argb(76, 255, 255, 255)
            ring.strokeWidth = (if (active) 2f else 1.5f) * d
            canvas.drawRoundRect(t, 10 * d, 10 * d, ring)
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            centeredText(canvas, initials(name), t.centerX(), t.centerY(), 12.5f * d)
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Only the fighter in play (or an empty slot) gets a name, to keep the corner quiet.
        if (active || name == null) {
            text.textAlign = Paint.Align.LEFT
            text.setShadowLayer(4 * d, 0f, d, Color.BLACK)
            text.textSize = 10 * d
            text.color = if (active) accent else Color.argb(200, 255, 255, 255)
            canvas.drawText(name?.substringBefore(" ") ?: "Pick", tile.right + 6 * d, tile.centerY() + 3.5f * d, text)
            text.color = Color.WHITE
            text.clearShadowLayer()
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawFace(canvas: Canvas, el: El) {
        val c = keyColors.getValue(el.letter!!)
        val down = el in pressed
        canvas.drawCircle(el.cx, el.cy, el.r, if (down) fill.apply { color = withAlpha(c, 200) } else glassFill)
        ring.color = withAlpha(c, 200)
        ring.strokeWidth = 2.5f * d
        canvas.drawCircle(el.cx, el.cy, el.r - 1.25f * d, ring)
        val label = state.labels[el.letter]
        if (label != null && label.length <= 2) {
            // A short name ("LP") is the button's name.
            centeredText(canvas, label, el.cx, el.cy, el.r * 0.56f)
        } else if (state.layout == Layout.SHOOTER && label != null) {
            text.letterSpacing = 0.06f
            centeredText(canvas, label.uppercase(), el.cx, el.cy, el.r * 0.36f)
            text.letterSpacing = 0f
        } else {
            centeredText(canvas, el.letter.toString(), el.cx, el.cy - if (label != null && label.length > 1) el.r * 0.16f else 0f, el.r * 0.74f)
            if (label != null && label.length > 1) {
                centeredText(canvas, label, el.cx, el.cy + el.r * 0.42f, el.r * 0.27f, Color.argb(200, 255, 255, 255))
            }
        }
    }

    private fun drawSkill(canvas: Canvas, el: El) {
        val move = el.move!!
        val tint = if (move.isSuper) superColor else accent
        glass(canvas, el, stroke = withAlpha(if (move.isSuper) Color.rgb(255, 120, 60) else accent, 150))
        if (el in pressed) canvas.drawCircle(el.cx, el.cy, el.r, fill.apply { color = Color.argb(80, 255, 207, 77) })
        val glyph = el.glyph
        if (glyph != null) {
            canvas.save()
            if (state.facingLeft) canvas.scale(-1f, 1f, el.cx, el.cy)
            glyphPaint.color = tint
            glyphPaint.strokeWidth = 3.4f * (el.r * 0.84f * 2 / 48f)
            canvas.drawPath(glyph, glyphPaint)
            canvas.restore()
        } else {
            // A button sequence ("D B C C ↑"): its inputs, on two lines if long.
            val tokens = toArrows(mirrored(move.cmd)).split(" ").map { t -> t.map { c -> if (c in keyFor) buttonName(c) else c.toString() }.joinToString("") }
            val lines = if (tokens.size > 3) {
                listOf(tokens.take((tokens.size + 1) / 2), tokens.drop((tokens.size + 1) / 2))
            } else {
                listOf(tokens)
            }
            val size = el.r * 0.375f
            lines.forEachIndexed { i, line ->
                val dy = (i - (lines.size - 1) / 2f) * size * 1.15f
                centeredText(canvas, line.joinToString(" "), el.cx, el.cy + dy, size, tint)
            }
        }
        // Chips for the buttons the move ends on, top-left.
        val chipR = el.r * 0.3f
        el.buttons.forEachIndexed { i, b ->
            val cx = el.rect.left + chipR - 4 * d + i * (chipR * 2 - 5 * d)
            val cy = el.rect.top + chipR - 3 * d
            canvas.drawCircle(cx, cy, chipR, fill.apply { color = keyColors.getValue(b) })
            ring.color = Color.argb(215, 10, 10, 20)
            ring.strokeWidth = 1.5f * d
            canvas.drawCircle(cx, cy, chipR, ring)
            val name = buttonName(b)
            centeredText(canvas, name, cx, cy, el.r * if (name.length > 1) 0.22f else 0.32f, if (b == 'B') Color.rgb(58, 44, 0) else Color.WHITE)
        }
        val showLabel = when (state.labelMode) {
            LabelMode.ALL -> true
            LabelMode.SUPERS -> move.isSuper
            LabelMode.OFF -> false
        }
        if (showLabel) {
            text.setShadowLayer(4 * d, 0f, d, Color.BLACK)
            text.textSize = el.r * 0.32f
            val label = ellipsize(move.shortName, el.r * 2.7f)
            canvas.drawText(label, el.cx, el.rect.bottom + el.r * 0.39f, text)
            text.clearShadowLayer()
        }
        drawSweep(canvas, el)
    }

    private fun drawSys(canvas: Canvas, el: El) {
        val sys = el.sys!!
        val stroke = when {
            sys.power -> Color.argb(205, 255, 207, 77)
            sys.on -> Color.argb(230, 52, 196, 124)
            else -> null
        }
        glass(canvas, el, stroke = stroke)
        val tint = when {
            sys.power -> accent
            sys.on -> Color.rgb(127, 240, 180)
            else -> Color.WHITE
        }
        centeredText(canvas, sys.name, el.cx, el.cy - el.r * 0.2f, el.r * 0.43f, tint)
        centeredText(canvas, sys.sub, el.cx, el.cy + el.r * 0.3f, el.r * 0.36f, withAlpha(tint, 150))
        drawSweep(canvas, el)
    }

    private var blockPointer = -1
    private var blockStartY = 0f
    private var blockLow = false

    private fun drawBlock(canvas: Canvas, el: El) {
        val active = blockPointer != -1
        glass(canvas, el, stroke = if (active) Color.argb(220, 120, 200, 255) else Color.argb(120, 160, 210, 255))
        if (active) canvas.drawCircle(el.cx, el.cy, el.r, fill.apply { color = Color.argb(70, 120, 200, 255) })
        val tint = Color.rgb(190, 225, 255)
        centeredText(canvas, if (active && blockLow) "LOW" else "BLOCK", el.cx, el.cy - el.r * 0.12f, el.r * 0.34f, tint)
        centeredText(canvas, if (active && blockLow) "↙ held" else "↓ low", el.cx, el.cy + el.r * 0.3f, el.r * 0.26f, withAlpha(tint, 150))
    }

    private fun drawSweep(canvas: Canvas, el: El) {
        val (start, len) = firing[el] ?: return
        val p = min(1f, (frame - start).toFloat() / len.coerceAtLeast(1))
        ring.color = accent
        ring.strokeWidth = 3 * d
        val r = el.r + 4 * d
        canvas.drawArc(el.cx - r, el.cy - r, el.cx + r, el.cy + r, -90f, 360f * p, false, ring)
    }

    private fun drawStick(canvas: Canvas) {
        val st = state
        if (st.stick == StickMode.HIDDEN && !editing) return
        val active = stickPointer != -1
        val alpha = when {
            active || editing -> 1f
            st.stick == StickMode.FIXED -> 0.8f
            else -> 0.35f
        }
        val r = stickRadius()
        fill.color = Color.argb((115 * alpha).toInt(), 14, 16, 28)
        canvas.drawCircle(stickCx, stickCy, r, fill)
        ring.color = Color.argb((70 * alpha).toInt(), 255, 255, 255)
        ring.strokeWidth = 1.5f * d
        canvas.drawCircle(stickCx, stickCy, r, ring)
        // ‹ › hints either side, like KOF Arena.
        glyphPaint.color = Color.argb((190 * alpha).toInt(), 255, 255, 255)
        glyphPaint.strokeWidth = 3 * d
        val cx = r * 1.12f
        val ch = r * 0.094f
        canvas.drawPath(Path().apply {
            moveTo(stickCx - cx + ch / 2, stickCy - ch)
            lineTo(stickCx - cx - ch / 2, stickCy)
            lineTo(stickCx - cx + ch / 2, stickCy + ch)
            moveTo(stickCx + cx - ch / 2, stickCy - ch)
            lineTo(stickCx + cx + ch / 2, stickCy)
            lineTo(stickCx + cx - ch / 2, stickCy + ch)
        }, glyphPaint)
        fill.color = Color.argb((225 * alpha).toInt(), 225, 226, 236)
        canvas.drawCircle(stickCx + knobDx, stickCy + knobDy, r * 0.42f, fill)
    }

    // Sequences are drawn as text, so mirror their forward/back by hand.
    private fun mirrored(cmd: String) = if (state.facingLeft) {
        cmd.map { ch -> if (ch in '1'..'9') ('0' + mirrorDir(ch - '0')) else ch }.joinToString("")
    } else {
        cmd
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // ----- Touch -----

    private fun hit(x: Float, y: Float, only: Kind? = null): El? {
        // Inner elements first so a ring button never steals a tap meant for A-D.
        val order = listOf(Kind.FACE, Kind.BLOCK, Kind.SYS, Kind.SKILL, Kind.PAUSE, Kind.COIN, Kind.FACING, Kind.START, Kind.SLOT)
        for (k in order) {
            if (only != null && k != only) continue
            for (el in elements) {
                if (el.kind != k) continue
                val inside = if (el.round) {
                    hypot(x - el.cx, y - el.cy) <= el.r * if (el.kind == Kind.FACE) 1.15f else 1.08f
                } else {
                    RectF(el.rect).apply { inset(-6 * d, -6 * d) }.contains(x, y)
                }
                if (inside) return el
            }
        }
        return null
    }

    private fun inStickZone(x: Float, y: Float): Boolean {
        val side = if (state.leftHanded) x > width * 0.58f else x < width * 0.42f
        return side && y > height * 0.2f
    }

    private fun haptic(strong: Boolean = false) {
        if (!state.haptics) return
        performHapticFeedback(if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private var dragId: String? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragStart = Tweak()

    // Which control is under a finger in the editor (the stick included).
    private fun editHit(x: Float, y: Float): String? {
        hit(x, y)?.let { return it.id }
        return if (hypot(x - homeX, y - homeY) <= stickRadius()) "stick" else null
    }

    private fun editTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragId = editHit(e.x, e.y)
                dragX = e.x
                dragY = e.y
                dragStart = dragId?.let(::tweak) ?: Tweak()
                selected = dragId
                onSelect?.invoke(selected)
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragId ?: return
                val t = dragStart.copy(dx = dragStart.dx + (e.x - dragX) / d, dy = dragStart.dy + (e.y - dragY) / d)
                tweaks = tweaks + (id to t)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragId = null
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) {
            editTouch(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                for (id in pointerEl.keys.toList()) up(id)
                if (stickPointer != -1) up(stickPointer)
            }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val el = hit(x, y)
        if (el == null) {
            if (stickPointer == -1 && inStickZone(x, y)) startStick(id, x, y)
            return
        }
        when (el.kind) {
            Kind.FACE, Kind.COIN, Kind.START -> {
                pointerEl[id] = el
                pressed += el
                listener.onKey(el.key!!, true)
                haptic()
            }
            Kind.SKILL -> fire(id, el, el.move!!.cmd)
            Kind.SYS -> {
                val cmd = el.sys!!.cmd
                if (el.sys.hold) {
                    pointerEl[id] = el
                    pressed += el
                    holdRuns++
                    listener.onRun(true)
                    haptic()
                } else if (cmd != null) fire(id, el, cmd) else {
                    listener.onRapid()
                    haptic()
                }
            }
            Kind.BLOCK -> {
                if (blockPointer != -1) return
                blockPointer = id
                blockStartY = y
                blockLow = false
                pointerEl[id] = el
                listener.onBlock(4)
                haptic()
            }
            Kind.PAUSE -> listener.onMenu()
            Kind.FACING -> {
                listener.onFlip()
                haptic()
            }
            Kind.SLOT -> {
                listener.onSlot(el.index)
                haptic()
            }
        }
    }

    private fun fire(id: Int, el: El, cmd: String) {
        val frames = listener.onPlay(cmd)
        pointerEl[id] = el
        pressed += el
        firing[el] = frame to frames
        haptic(strong = true)
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == stickPointer) {
            updateStick(x, y)
            return
        }
        if (id == blockPointer) {
            // Slide down past a small threshold for low block, back up for high.
            val low = y - blockStartY > 18 * d
            if (low != blockLow) {
                blockLow = low
                listener.onBlock(if (low) 1 else 4)
                haptic()
            }
            return
        }
        val el = pointerEl[id] ?: return
        if (el.kind != Kind.FACE) return
        // Slide between A B C D without lifting.
        val next = hit(x, y, Kind.FACE) ?: return
        if (next == el) return
        listener.onKey(el.key!!, false)
        pressed -= el
        pointerEl[id] = next
        pressed += next
        listener.onKey(next.key!!, true)
        haptic()
    }

    private fun up(id: Int) {
        if (id == stickPointer) {
            stickPointer = -1
            knobDx = 0f
            knobDy = 0f
            lastDir = 5
            resetStick()
            listener.onDir(5)
            setStickRun(false)
            return
        }
        if (id == blockPointer) {
            blockPointer = -1
            blockLow = false
            pointerEl.remove(id)
            listener.onBlock(null)
            return
        }
        val el = pointerEl.remove(id) ?: return
        pressed -= el
        if (el.kind == Kind.FACE || el.kind == Kind.COIN || el.kind == Kind.START) listener.onKey(el.key!!, false)
        if (el.kind == Kind.SYS && el.sys?.hold == true) {
            holdRuns--
            if (holdRuns == 0 && !stickRun) listener.onRun(false)
        }
    }

    // Running from the Run button (how many fingers hold it) and from the stick.
    private var holdRuns = 0
    private var stickRun = false

    private fun setStickRun(on: Boolean) {
        if (on == stickRun) return
        stickRun = on
        if (on) haptic(strong = true)
        if (holdRuns == 0) listener.onRun(on)
    }

    private fun stickRadius() = 64 * d * state.size * tweak("stick").scale

    private fun startStick(id: Int, x: Float, y: Float) {
        stickPointer = id
        // Fixed: a touch near the drawn stick uses its centre; anywhere else
        // (and always when floating) the stick jumps under the thumb.
        if (state.stick == StickMode.FIXED && hypot(x - homeX, y - homeY) < stickRadius() * 1.5f) {
            stickCx = homeX
            stickCy = homeY
        } else {
            stickCx = x
            stickCy = y
        }
        updateStick(x, y)
    }

    private fun updateStick(x: Float, y: Float) {
        var dx = x - stickCx
        var dy = y - stickCy
        val dist = hypot(dx, dy)
        // The centre follows a thumb that drifts too far, so you never end up
        // pushing against the edge.
        val follow = stickRadius() * 1.4f
        if (dist > follow) {
            stickCx += dx * (dist - follow) / dist
            stickCy += dy * (dist - follow) / dist
            dx = x - stickCx
            dy = y - stickCy
        }
        val r = stickRadius()
        val k = if (hypot(dx, dy) > r) r / hypot(dx, dy) else 1f
        knobDx = dx * k
        knobDy = dy * k
        val dir = angleToDir(dx, dy, 14 * d)
        if (dir != lastDir) {
            lastDir = dir
            if (dir != 5) haptic()
            listener.onDir(dir)
        }
        if (state.run) {
            // Pushed past the edge sideways: run. Easing back in (or turning
            // up or down) walks again; the gap between the two stops flicker.
            val sideways = dir == 4 || dir == 6 || dir == 1 || dir == 3 || dir == 7 || dir == 9
            val reach = hypot(dx, dy)
            if (!stickRun && sideways && reach > r * 1.25f) setStickRun(true)
            else if (stickRun && (!sideways || reach < r * 0.9f)) setStickRun(false)
        }
    }
}
