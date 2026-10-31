package com.kofarcade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.Pt
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
// The screen the controls were laid out on (a 20:9 phone, 411 x 914 dp):
// below this height (or a width the two sides need), they shrink to fit.
private const val FULL_HEIGHT_DP = 411f
private const val FULL_WIDTH_DP = 560f
private const val MIN_FIT = 0.6f
// Tablets and unfolded phones: the controls grow with the screen (a phone's
// size in dp would leave them small, far from the thumbs), up to this much.
private const val MAX_FIT = 1.35f

class HudView(context: Context, private val listener: Listener) : View(context) {
    companion object {
        /**
         * The controls' size (Settings: Button size) until set: small, in the
         * corners, so the game stays in view (as players set theirs).
         */
        const val DEFAULT_SIZE = 0.85f
    }


    interface Listener {
        fun onDir(dir: Int)
        fun onKey(key: Key, down: Boolean)

        /** Plays a command ([move]: the ring's move it came from); returns how many frames it takes. */
        fun onPlay(cmd: String, move: Move? = null): Int
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

    /** A fighter's face from the game, by name, once it has been on screen (else initials). */
    var faceOf: (String) -> Bitmap? = { null }

    fun setScale(id: String, scale: Float) {
        tweaks = tweaks + (id to (tweaks[id] ?: Tweak()).copy(scale = scale))
    }

    /** Whether a control can be resized (groups of pills only move). */
    fun resizable(id: String?) = id != null && id != "top" && id != "team"

    /**
     * How see-through the controls are (the Opacity setting, 0.3 to 1). The
     * glass fills fade with it fully; rings, letters and arrows less, so
     * they stay readable over a busy stage.
     */
    var opacity = 1f
        set(value) {
            field = value
            alpha = 0.55f + 0.45f * value
            // Under the view's alpha, the fill ends up 85% of [value] dark.
            glassFill.alpha = (255 * 0.85f * value / alpha).toInt().coerceIn(0, 255)
            invalidate()
        }

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
        val size: Float = DEFAULT_SIZE,
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
        // Words worked out at layout, so drawing makes no strings: the
        // button's letter, the move's (shortened) name or the slot's name.
        val label: String = "",
        /** A sequence move's inputs, in one or two lines, facing right [0] and left [1]. */
        val seq: Array<List<String>>? = null,
        /** The names on [buttons]' chips. */
        val chips: List<String> = emptyList(),
    ) {
        val cx get() = rect.centerX()
        val cy get() = rect.centerY()
        val r get() = rect.width() / 2
    }

    private val density = resources.displayMetrics.density

    // The controls' unit: a dp on a phone 411 dp tall or more (where they
    // were laid out), and on a smaller screen as much less, so every control,
    // gap and offset shrinks together and the layout stays the same, not
    // crowded (Block above the stick, the moves around the buttons).
    private var d = density
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

    // Glass: a dark see-through fill with a thin light ring; pressed, it glows.
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 8, 8, 14) }
    private val glassStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = Color.argb(220, 255, 255, 255)
    }
    // Behind words that sit on the game picture itself (slot names, pills).
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 8, 8, 14) }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val boldItalic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    // Reused while drawing, so a frame allocates nothing.
    private val scratch = Path()
    private val tileRect = RectF()
    private val slotRect = RectF()
    // A small arrow mark at the stick ring's right, in units of its radius; turned for the other three.
    private val stickMark = Path().apply {
        moveTo(0.86f, 0f)
        lineTo(0.74f, -0.1f)
        lineTo(0.74f, 0.1f)
        close()
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Faces are pixel art: drawn without smoothing, clipped to the tile's rounded corners.
    private val facePaint = Paint().apply { isFilterBitmap = false }
    private val faceClip = Path()
    private val faceSrc = Rect()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = bold
        textAlign = Paint.Align.CENTER
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val accent = Color.rgb(232, 178, 26) // Palette.gold
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
        softShadow()
    }

    // Every label carries a small dark shadow, so it reads on a bright stage too.
    private fun softShadow() = text.setShadowLayer(2f * d, 0f, 0.5f * d, Color.argb(230, 0, 0, 0))

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
        stopRunning()
        stickPointer = -1
        knobDx = 0f
        knobDy = 0f
        lastDir = 5
        resetStick()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        d = density * fit(w, h)
        layoutElements()
    }

    private fun fit(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 1f
        return minOf(MAX_FIT, h / density / FULL_HEIGHT_DP, w / density / FULL_WIDTH_DP).coerceAtLeast(MIN_FIT)
    }

    // ----- Layout -----

    private fun safeInsets(): Pair<Float, Float> {
        val cutout = rootWindowInsets?.displayCutout
        return (cutout?.safeInsetLeft ?: 0).toFloat() to (cutout?.safeInsetRight ?: 0).toFloat()
    }

    private fun tweak(id: String) = tweaks[id] ?: Tweak()

    // A round control at its default spot, moved / resized by your layout and
    // kept on screen.
    // How far each control was pulled back onto the screen (px): a drag past
    // the edge is saved where the control is shown, not past it.
    private val overshoot = HashMap<String, Pair<Float, Float>>()

    // A round control at its default spot, moved / resized by your layout and
    // kept on screen, clear of the camera cutout.
    private fun placed(id: String, cx: Float, cy: Float, r: Float): RectF {
        val t = tweak(id)
        val rr = r * t.scale
        val (safeL, safeR) = safeInsets()
        val rawX = cx + t.dx * d
        val rawY = cy + t.dy * d
        val x = if (width - safeL - safeR > 2 * rr) rawX.coerceIn(safeL + rr, width - safeR - rr) else rawX
        val y = if (height > 2 * rr) rawY.coerceIn(rr, height - rr) else rawY
        overshoot[id] = (x - rawX) to (y - rawY)
        return circle(x, y, rr)
    }

    // A bar (COIN/START, the team bar) moved by your layout, kept on screen too:
    // once off it, it couldn't be grabbed again, nor COIN pressed.
    private fun placed(id: String, rect: RectF): RectF {
        val t = tweak(id)
        val (safeL, safeR) = safeInsets()
        val r = RectF(rect).apply { offset(t.dx * d, t.dy * d) }
        var ox = 0f
        var oy = 0f
        if (r.left < safeL) ox = safeL - r.left else if (r.right > width - safeR) ox = width - safeR - r.right
        if (r.top < 0) oy = -r.top else if (r.bottom > height) oy = height - r.bottom
        if (width > 0 && height > 0) r.offset(ox, oy)
        // Several pills share one id: the largest pull back counts.
        val prev = overshoot[id]
        if (prev == null || kotlin.math.abs(ox) + kotlin.math.abs(oy) > kotlin.math.abs(prev.first) + kotlin.math.abs(prev.second)) overshoot[id] = ox to oy
        return r
    }

    private fun layoutElements() {
        overshoot.clear()
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
        val barLeft = x
        val barRight = w - x
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
        st.slots.indices.forEach { i ->
            val y = 54 * d + i * 40 * d
            val name = st.slots[i].name
            els += El(
                "team", Kind.SLOT, placed("team", RectF(safeL + 12 * d, y, safeL + 12 * d + 96 * d, y + 34 * d)), false,
                index = i, label = name?.substringBefore(" ") ?: "Pick",
            )
        }

        // Button cluster: A B C D, moves on an outer ring, system on an inner one.
        val s = 62 * d * sc
        val mirror = if (st.leftHanded) -1f else 1f
        val ax = if (st.leftHanded) safeL + 1.3f * s + 16 * d else w - safeR - 1.3f * s - 16 * d
        val ay = h - s - 16 * d
        val six = st.faceButtons.size == 6
        val faceR = s / 2 * when {
            st.layout == Layout.SHOOTER -> 1.12f
            six -> 0.88f
            else -> 1f
        }
        // Default spots, in button sizes from the cluster's centre (x right,
        // y down). Laid out by hand on a phone for each kind of game.
        val facePos: Map<Char, Pair<Float, Float>> = when {
            // Capcom: punches over kicks, light to heavy, in straight columns.
            six -> mapOf(
                'A' to (-1.25f to -0.6f), 'B' to (-0.19f to -0.6f), 'C' to (0.87f to -0.6f),
                'D' to (-1.25f to 0.55f), 'E' to (-0.19f to 0.55f), 'F' to (0.87f to 0.55f),
            )
            // Metal Slug, Nightmare in the Dark: Shoot and Jump low, Bomb above Jump.
            st.layout == Layout.SHOOTER -> mapOf(
                'A' to (-0.98f to 0.35f), 'B' to (0.71f to 0.38f), 'C' to (0.63f to -1.3f), 'D' to (-1.15f to -0.95f),
            )
            // Sengoku 3, Cadillacs and Dinosaurs: big buttons, spread out.
            st.layout == Layout.BRAWLER -> mapOf(
                'A' to (-0.85f to 0.19f), 'B' to (0.87f to 0.4f), 'C' to (-0.92f to -1.17f), 'D' to (0.84f to -1.27f),
            )
            // Fighters: A B C D in a square, C D on top.
            else -> mapOf(
                'C' to (-0.42f to -0.61f), 'D' to (0.76f to -0.61f), 'A' to (-0.44f to 0.6f), 'B' to (0.76f to 0.6f),
            )
        }
        val faceScale = if (st.layout == Layout.BRAWLER) mapOf('A' to 1.14f, 'B' to 1.14f, 'C' to 1.14f) else emptyMap()
        for (c in st.faceButtons) {
            val (fx, fy) = facePos.getValue(c)
            val r = faceR * (faceScale[c] ?: 1f)
            els += El("face:$c", Kind.FACE, placed("face:$c", ax + fx * s * mirror, ay + fy * s, r), true, key = keyFor[c], letter = c, label = c.toString())
        }
        val skillR = 26 * d * sc
        // Big buttons on a narrow screen: a high move that would sit on
        // COIN / START goes beside them, on the side away from the buttons.
        fun belowBar(p: Pt): Pt {
            val r = skillR * 1.15f
            val cx = ax + p.x * mirror
            val cy = ay + p.y
            val onBar = cx + r > barLeft && cx - r < barRight && cy - r < top + 30 * d + 6 * d
            if (!onBar) return p
            val left = barLeft - r - 6 * d
            val right = barRight + r + 6 * d
            val nx = if (mirror > 0) left else right
            return Pt((nx - ax) * mirror, p.y)
        }
        // Moves on an outer ring; Sengoku 3's first three sit higher and bigger.
        val brawler = st.layout == Layout.BRAWLER
        // Six buttons: a slightly tighter ring, so the last move clears Block.
        val ring = arcPositions(st.moves.size, 3 * s, 72f, if (six) 21f else 23f).mapIndexed { i, p ->
            when {
                brawler && i == 1 -> belowBar(Pt(-0.52f * s, -2.98f * s))
                brawler && i == 2 -> belowBar(Pt(-1.93f * s, -2.94f * s))
                else -> p
            }
        }
        ring.forEachIndexed { i, p ->
            val m = st.moves[i]
            val rect = placed("move:$i", ax + p.x * mirror, ay + p.y, skillR * if (brawler && i < 3) 1.15f else 1f)
            val glyph = glyphPath(m.cmd, rect.centerX(), rect.centerY(), rect.width() / 2 * 0.84f)
            // Sequences show their inputs as text, so no separate button chips.
            val buttons = runCatching { parseCommand(m.cmd) }.getOrNull()?.takeUnless { it.isSequence }?.buttons.orEmpty()
            text.textSize = rect.width() / 2 * 0.32f
            els += El(
                "move:$i", Kind.SKILL, rect, true, move = m,
                glyph = glyph,
                buttons = buttons,
                // No wider than this, so it stays clear of the next move round the ring.
                label = ellipsize(m.shortName, rect.width() / 2 * 2.2f),
                seq = if (glyph == null) arrayOf(sequenceLines(m.cmd), sequenceLines(mirrored(m.cmd))) else null,
                chips = buttons.map(::buttonName),
            )
        }
        // Stick home: bottom corner, for the left thumb (the right, left-handed).
        val home = placed(
            "stick",
            if (st.leftHanded) w - safeR - 112 * d * sc else safeL + 112 * d * sc,
            h - 104 * d * sc,
            64 * d * sc,
        )
        // Block: above the stick, for the thumb that steers (slide down for
        // low block), clear of the team bar. With no room there (a short
        // screen, big controls), left of the buttons, over Blowback.
        if (st.block) {
            val r = 25 * d * sc
            val teamBottom = if (st.slots.isEmpty()) 0f else 54 * d + (st.slots.size - 1) * 40 * d + 34 * d
            val y = maxOf(home.centerY() - 140 * d * sc, teamBottom + 8 * d + r)
            val above = home.top - (y + r) >= 12 * d
            val rect = if (above) {
                placed("block", home.centerX(), y, r)
            } else {
                placed("block", ax + (if (six) -2.2f else -1.85f) * s * mirror, ay - 0.6f * s, r)
            }
            els += El("block", Kind.BLOCK, rect, true)
        }
        // System chips (MAX, Roll, Tag...): above the buttons, the third to the left.
        val sysR = 22 * d * sc
        val sysSpots = if (six) {
            listOf(Pt(-0.7f * s, -1.6f * s), Pt(0.6f * s, -1.6f * s))
        } else {
            // MAX and Roll over C and D; the third (Blowback) beside A, on the bottom row.
            listOf(Pt(-0.43f * s, -1.71f * s), Pt(0.89f * s, -1.73f * s), Pt(-1.75f * s, 0.6f * s))
        }
        val sysArc = arcPositions(st.system.size, 1.72f * s, 104f, 34f)
        st.system.indices.forEach { i ->
            val sys = st.system[i]
            if (brawler && sys.hold) {
                // Run: big, right above the stick, for the thumb that steers.
                val sx = if (st.leftHanded) w - safeR - 112 * d * sc else safeL + 112 * d * sc
                val rect = placed("sys:$i", sx, h - 104 * d * sc - 2.13f * s, sysR * 1.4f)
                els += El("sys:$i", Kind.SYS, rect, true, sys = sys)
                return@forEach
            }
            val p = if (st.layout == Layout.FIGHTER) sysSpots.getOrNull(i) ?: sysArc[i] else sysArc[i]
            els += El("sys:$i", Kind.SYS, placed("sys:$i", ax + p.x * mirror, ay + p.y, sysR), true, sys = sys)
        }

        homeX = home.centerX()
        homeY = home.centerY()

        // Laid out again (the fighters swapped sides, the move ring changed…):
        // a finger on a button that's still there keeps holding it, so Block,
        // a held A-D (rapid fire) or Run don't let go mid-fight. Only a finger
        // whose button went lets go.
        val byId = els.associateBy { it.id }
        val kept = HashMap<Int, El>()
        var runsKept = 0
        for ((pid, old) in pointerEl) {
            val now = byId[old.id]?.takeIf { it.kind == old.kind && it.key == old.key && it.sys?.hold == old.sys?.hold }
            val holding = old.kind == Kind.FACE || old.kind == Kind.COIN || old.kind == Kind.START ||
                old.kind == Kind.BLOCK || (old.kind == Kind.SYS && old.sys?.hold == true)
            if (now != null && holding) {
                kept[pid] = now
                if (now.kind == Kind.SYS) runsKept++
            } else if (old.kind == Kind.FACE || old.kind == Kind.COIN || old.kind == Kind.START) {
                listener.onKey(old.key!!, false)
            }
        }
        if (blockPointer != -1 && blockPointer !in kept) {
            listener.onBlock(null)
            blockPointer = -1
            blockLow = false
        }
        pressed.clear()
        pressed += kept.values.filter { it.kind != Kind.BLOCK }
        firing.clear()
        pointerEl.clear()
        pointerEl += kept
        if (runsKept != holdRuns) {
            // A Run button that went: stop, unless the stick still runs.
            holdRuns = runsKept
            if (holdRuns == 0 && !stickRun) listener.onRun(false)
        }
        elements = els
        if (stickPointer == -1) resetStick()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)

    /** Every control's spot as laid out (id, bounds, round), the stick's home included: for the layout tests. */
    internal fun controlSpots(): List<Triple<String, RectF, Boolean>> =
        elements.map { Triple(it.id, RectF(it.rect), it.round) } + Triple("stick", circle(homeX, homeY, stickRadius()), true)

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
        if (editing) {
            // Dim the game well (under the controls), so they and their outlines read on any stage.
            fill.color = Color.argb(150, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
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
            softShadow()
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
        // Names under the buttons, here only: in play the buttons stay clean.
        for (el in elements) {
            if (!el.round) continue
            val name = when (el.kind) {
                // A B C D already show their letter; only a longer name ("Attack") goes under.
                Kind.FACE -> state.labels[el.letter]?.takeIf { it.length > 2 } ?: continue
                Kind.SKILL -> el.move!!.shortName
                Kind.SYS -> el.sys!!.name
                Kind.BLOCK -> "Block"
                else -> continue
            }
            text.textSize = 10 * d
            text.color = if (el.id == selected) accent else Color.WHITE
            // Under the control, or over it when that would leave the screen.
            val below = el.rect.bottom + 13 * d
            canvas.drawText(ellipsize(name, el.r * 2.8f), el.cx, if (below > height - 3 * d) el.rect.top - 6 * d else below, text)
        }
        text.textSize = 10 * d
        text.color = if (selected == "stick") accent else Color.WHITE
        canvas.drawText("Stick", homeX, homeY + stickRadius() + 14 * d, text)
        text.color = Color.WHITE
        softShadow()
    }

    private fun glass(canvas: Canvas, el: El, stroke: Int? = null, strokeWidth: Float = 1.2f * d, glow: Int = accent) {
        val down = el in pressed
        if (el.round) {
            if (down) halo(canvas, el.cx, el.cy, el.r, glow)
            canvas.drawCircle(el.cx, el.cy, el.r, if (down) pressedFill(glow) else glassFill)
            // A faint dark edge outside the light ring keeps the button's shape on a bright stage.
            ring.color = Color.argb(110, 0, 0, 0)
            ring.strokeWidth = d
            canvas.drawCircle(el.cx, el.cy, el.r + 0.5f * d, ring)
            ring.color = if (down) glow else stroke ?: glassStroke.color
            ring.strokeWidth = if (down) 2 * d else strokeWidth
            canvas.drawCircle(el.cx, el.cy, el.r - ring.strokeWidth / 2, ring)
        } else {
            val rr = el.rect.height() / 2
            canvas.drawRoundRect(el.rect, rr, rr, if (down) pressedFill(glow) else glassFill)
            ring.color = if (down) glow else stroke ?: glassStroke.color
            ring.strokeWidth = strokeWidth
            canvas.drawRoundRect(el.rect, rr, rr, ring)
        }
    }

    // Pressed glass: lit from inside in the glow's colour.
    private fun pressedFill(c: Int) = fill.apply { color = Color.argb(110, Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2) }

    // A soft glow round a pressed button: rings fading outwards.
    private fun halo(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int) {
        ring.color = withAlpha(c, 120)
        ring.strokeWidth = 3 * d
        canvas.drawCircle(cx, cy, r + 2.5f * d, ring)
        ring.color = withAlpha(c, 60)
        ring.strokeWidth = 4 * d
        canvas.drawCircle(cx, cy, r + 6 * d, ring)
        ring.color = withAlpha(c, 25)
        ring.strokeWidth = 5 * d
        canvas.drawCircle(cx, cy, r + 10.5f * d, ring)
    }

    private fun centeredText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int = Color.WHITE) {
        text.textSize = size
        text.color = color
        canvas.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
        text.color = Color.WHITE
    }

    /** [size], or smaller so the widest of [lines] fits in [width]: words stay inside their button. */
    private fun fitSize(lines: List<String>, size: Float, width: Float): Float {
        text.textSize = size
        var widest = 0f
        for (l in lines) widest = maxOf(widest, text.measureText(l))
        return if (widest > width) size * width / widest else size
    }

    private fun fittedText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, width: Float, color: Int = Color.WHITE) {
        text.textSize = size
        val w = text.measureText(s)
        text.textSize = if (w > width) size * width / w else size
        text.color = color
        canvas.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
        text.color = Color.WHITE
    }

    private fun drawPause(canvas: Canvas, el: El) {
        glass(canvas, el, glow = Color.WHITE)
        fill.color = Color.WHITE
        val bw = 3.4f * d
        val bh = 11 * d
        canvas.drawRoundRect(el.cx - 4.5f * d - bw / 2, el.cy - bh / 2, el.cx - 4.5f * d + bw / 2, el.cy + bh / 2, d, d, fill)
        canvas.drawRoundRect(el.cx + 4.5f * d - bw / 2, el.cy - bh / 2, el.cx + 4.5f * d + bw / 2, el.cy + bh / 2, d, d, fill)
    }

    private fun drawPill(canvas: Canvas, el: El) {
        val facing = el.kind == Kind.FACING
        if (el !in pressed) {
            val rr = el.rect.height() / 2
            canvas.drawRoundRect(el.rect, rr, rr, labelFill)
        }
        glass(canvas, el, stroke = if (facing) withAlpha(accent, 170) else null, glow = Color.WHITE)
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
            scratch.reset()
            scratch.moveTo(ax - 6 * d * dir, el.cy)
            scratch.lineTo(ax + 6 * d * dir, el.cy)
            scratch.moveTo(ax + 2 * d * dir, el.cy - 4 * d)
            scratch.lineTo(ax + 6 * d * dir, el.cy)
            scratch.lineTo(ax + 2 * d * dir, el.cy + 4 * d)
            canvas.drawPath(scratch, glyphPaint)
            centeredText(canvas, label, el.cx + 9 * d, el.cy, 10 * d, accent)
        } else {
            centeredText(canvas, label, el.cx, el.cy, 10 * d)
        }
        text.letterSpacing = 0f
    }

    private fun drawSlot(canvas: Canvas, el: El, slot: Slot?) {
        val tile = tileRect.apply { set(el.rect.left, el.rect.top, el.rect.left + 34 * d, el.rect.bottom) }
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
            val t = slotRect.apply { set(tile.left + inset, tile.top + inset, tile.right - inset, tile.bottom - inset) }
            fill.shader = LinearGradient(
                t.left, t.top, t.right, t.bottom,
                Color.HSVToColor(floatArrayOf(hue, 0.7f, if (active) 0.8f else 0.45f)),
                Color.HSVToColor(floatArrayOf((hue + 30) % 360, 0.65f, if (active) 0.35f else 0.22f)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(t, 10 * d, 10 * d, fill)
            fill.shader = null
            val face = faceOf(name)
            if (face != null) {
                // Centre-crop the face to the square tile.
                val side = minOf(face.width, face.height)
                faceSrc.set((face.width - side) / 2, (face.height - side) / 2, (face.width + side) / 2, (face.height + side) / 2)
                faceClip.reset()
                faceClip.addRoundRect(t, 10 * d, 10 * d, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(faceClip)
                facePaint.alpha = if (active) 255 else 150
                canvas.drawBitmap(face, faceSrc, t, facePaint)
                canvas.restore()
            }
            ring.color = if (active) accent else Color.argb(76, 255, 255, 255)
            ring.strokeWidth = (if (active) 2f else 1.5f) * d
            canvas.drawRoundRect(t, 10 * d, 10 * d, ring)
            text.typeface = boldItalic
            if (face == null) centeredText(canvas, initials(name), t.centerX(), t.centerY(), 12.5f * d)
            text.typeface = bold
        }
        // Only the fighter in play (or an empty slot) gets a name, to keep the corner quiet.
        // On a dark glass pill, so it reads on a bright stage.
        if (active || name == null) {
            text.textAlign = Paint.Align.LEFT
            text.textSize = 10 * d
            val x = tile.right + 5 * d
            val w = text.measureText(el.label)
            slotRect.set(x, tile.centerY() - 8 * d, x + w + 12 * d, tile.centerY() + 8 * d)
            canvas.drawRoundRect(slotRect, 8 * d, 8 * d, labelFill)
            text.color = if (active) accent else Color.WHITE
            canvas.drawText(el.label, x + 6 * d, tile.centerY() + 3.5f * d, text)
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawFace(canvas: Canvas, el: El) {
        val c = keyColors.getValue(el.letter!!)
        val down = el in pressed
        // Glass with the key's colour in a thin inner ring (the move chips use the same colours).
        glass(canvas, el)
        if (!down) {
            ring.color = withAlpha(c, 150)
            ring.strokeWidth = 1.5f * d
            canvas.drawCircle(el.cx, el.cy, el.r - 4.5f * d, ring)
        }
        text.typeface = boldItalic
        val label = state.labels[el.letter]
        if (label != null && label.length <= 2) {
            // A short name ("LP") is the button's name.
            centeredText(canvas, label, el.cx, el.cy, el.r * 0.56f)
        } else if (state.layout == Layout.SHOOTER && label != null) {
            text.letterSpacing = 0.06f
            centeredText(canvas, label.uppercase(), el.cx, el.cy, el.r * 0.36f)
            text.letterSpacing = 0f
        } else {
            centeredText(canvas, el.label, el.cx, el.cy - if (label != null && label.length > 1) el.r * 0.16f else 0f, el.r * 0.74f)
            if (label != null && label.length > 1) {
                centeredText(canvas, label, el.cx, el.cy + el.r * 0.42f, el.r * 0.27f, Color.argb(200, 255, 255, 255))
            }
        }
        text.typeface = bold
    }

    private fun drawSkill(canvas: Canvas, el: El) {
        val move = el.move!!
        val tint = if (move.isSuper) superColor else accent
        glass(canvas, el, stroke = withAlpha(if (move.isSuper) Color.rgb(255, 120, 60) else Color.WHITE, if (move.isSuper) 210 else 175))
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
            val lines = el.seq!![if (state.facingLeft) 1 else 0]
            val size = fitSize(lines, el.r * 0.375f, el.r * 1.7f)
            for (i in lines.indices) {
                val dy = (i - (lines.size - 1) / 2f) * size * 1.15f
                centeredText(canvas, lines[i], el.cx, el.cy + dy, size, tint)
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
            val name = el.chips[i]
            centeredText(canvas, name, cx, cy, el.r * if (name.length > 1) 0.22f else 0.32f, if (b == 'B') Color.rgb(58, 44, 0) else Color.WHITE)
        }
        val showLabel = !editing && when (state.labelMode) {
            LabelMode.ALL -> true
            LabelMode.SUPERS -> move.isSuper
            LabelMode.OFF -> false
        }
        if (showLabel) {
            text.setShadowLayer(4 * d, 0f, d, Color.BLACK)
            text.textSize = el.r * 0.32f
            canvas.drawText(el.label, el.cx, el.rect.bottom + el.r * 0.39f, text)
            softShadow()
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
        fittedText(canvas, sys.name, el.cx, el.cy - el.r * 0.2f, el.r * 0.43f, el.r * 1.7f, tint)
        fittedText(canvas, sys.sub, el.cx, el.cy + el.r * 0.3f, el.r * 0.36f, el.r * 1.5f, withAlpha(tint, 150))
        drawSweep(canvas, el)
    }

    private var blockPointer = -1
    private var blockStartY = 0f
    private var blockLow = false

    private fun drawBlock(canvas: Canvas, el: El) {
        val active = blockPointer != -1
        if (active) halo(canvas, el.cx, el.cy, el.r, Color.rgb(120, 200, 255))
        glass(canvas, el, stroke = if (active) Color.rgb(120, 200, 255) else Color.argb(120, 160, 210, 255), strokeWidth = (if (active) 2f else 1.2f) * d)
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
            else -> 0.4f
        }
        // Like the buttons: the fill fades (a floating stick waiting for a thumb
        // most), the ring and arrows hardly, so it reads on any stage.
        val lines = maxOf(alpha, 0.85f)
        val r = stickRadius()
        // A see-through ring...
        fill.color = Color.argb((glassFill.alpha * 0.9f * maxOf(alpha, 0.6f)).toInt(), 8, 8, 14)
        canvas.drawCircle(stickCx, stickCy, r, fill)
        ring.color = Color.argb(110, 0, 0, 0)
        ring.strokeWidth = d
        canvas.drawCircle(stickCx, stickCy, r + 0.5f * d, ring)
        ring.color = Color.argb((220 * lines).toInt(), 255, 255, 255)
        ring.strokeWidth = 1.2f * d
        canvas.drawCircle(stickCx, stickCy, r, ring)
        ring.color = Color.argb((60 * lines).toInt(), 255, 255, 255)
        canvas.drawCircle(stickCx, stickCy, r * 0.62f, ring)
        // ...with an arrow mark each way, gold for where it's pushed.
        val dir = if (active) lastDir else 5
        canvas.save()
        canvas.translate(stickCx, stickCy)
        canvas.scale(r, r)
        for (i in 0 until 4) {
            val lit = when (i) {
                0 -> dir == 3 || dir == 6 || dir == 9
                1 -> dir == 1 || dir == 2 || dir == 3
                2 -> dir == 1 || dir == 4 || dir == 7
                else -> dir == 7 || dir == 8 || dir == 9
            }
            fill.color = if (lit) accent else Color.argb((200 * lines).toInt(), 255, 255, 255)
            canvas.drawPath(stickMark, fill)
            canvas.rotate(90f)
        }
        canvas.restore()
        // The knob: glass, glowing while held.
        val kx = stickCx + knobDx
        val ky = stickCy + knobDy
        val kr = r * 0.42f
        if (active) halo(canvas, kx, ky, kr, accent)
        fill.color = if (active) Color.argb(150, 116, 89, 13) else Color.argb((160 * lines).toInt(), 20, 20, 30)
        canvas.drawCircle(kx, ky, kr, fill)
        ring.color = if (active) accent else Color.argb((200 * lines).toInt(), 255, 255, 255)
        ring.strokeWidth = (if (active) 2f else 1.2f) * d
        canvas.drawCircle(kx, ky, kr - ring.strokeWidth / 2, ring)
    }

    // A sequence's inputs as text lines: "D B C C ↑", in two lines if long
    // (shrunk to fit when drawn). A very long one (Garou's Deadly Rave) shows
    // how it starts: all of it wouldn't be readable in a button.
    private fun sequenceLines(cmd: String): List<String> {
        val all = toArrows(cmd).split(" ").map { t -> t.map { c -> if (c in keyFor) buttonName(c) else c.toString() }.joinToString("") }
        if (all.size > 8) return listOf(all[0], all.drop(1).take(3).joinToString(" ") + " …")
        val tokens = all
        return if (tokens.size > 3) {
            listOf(tokens.take((tokens.size + 1) / 2).joinToString(" "), tokens.drop((tokens.size + 1) / 2).joinToString(" "))
        } else {
            listOf(tokens.joinToString(" "))
        }
    }

    // Sequences are drawn as text, so mirror their forward/back by hand.
    private fun mirrored(cmd: String) = cmd.map { ch -> if (ch in '1'..'9') ('0' + mirrorDir(ch - '0')) else ch }.joinToString("")

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // ----- Touch -----

    private fun hit(x: Float, y: Float, only: Kind? = null): El? {
        // Round buttons reach a little past their edge (fingers are wide), so
        // where two reaches meet, the tap goes to the button it's nearest to
        // for its size: a tap can't land on a neighbour's side of the line.
        var best: El? = null
        var bestScore = Float.MAX_VALUE
        for (el in elements) {
            if (!el.round || (only != null && el.kind != only)) continue
            val score = hypot(x - el.cx, y - el.cy) / el.r
            val reach = if (el.kind == Kind.FACE) 1.15f else 1.08f
            if (score <= reach && score < bestScore) {
                best = el
                bestScore = score
            }
        }
        if (best != null) return best
        val order = listOf(Kind.PAUSE, Kind.COIN, Kind.FACING, Kind.START, Kind.SLOT)
        for (k in order) {
            if (only != null && k != only) continue
            for (el in elements) {
                if (el.kind != k || el.round) continue
                if (RectF(el.rect).apply { inset(-6 * d, -6 * d) }.contains(x, y)) return el
            }
        }
        return null
    }

    private fun inStickZone(x: Float, y: Float): Boolean {
        val near = hypot(x - homeX, y - homeY)
        // Fixed: the stick stays where it is, so it takes touches on it and around it (generously), not across the screen.
        if (state.stick == StickMode.FIXED) return near <= stickRadius() * FIXED_REACH
        // On the stick wherever your layout put it, or anywhere on its side of the screen.
        if (near <= stickRadius() * 1.5f) return true
        val side = if (state.leftHanded) x > width * 0.58f else x < width * 0.42f
        return side && y > height * 0.2f
    }

    private val vibrator: android.os.Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }

    // The app's own setting decides (Android's touch feedback, often off, would silence it).
    private fun haptic(strong: Boolean = false) {
        if (!state.haptics) return
        val v = vibrator
        if (v != null && v.hasVibrator() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            v.vibrate(android.os.VibrationEffect.createPredefined(if (strong) android.os.VibrationEffect.EFFECT_CLICK else android.os.VibrationEffect.EFFECT_TICK))
        } else {
            performHapticFeedback(if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.KEYBOARD_TAP)
        }
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Saved where it's shown: dragged past the edge, it isn't kept past it.
                dragId?.let { id ->
                    val (ox, oy) = overshoot[id] ?: (0f to 0f)
                    if (ox != 0f || oy != 0f) {
                        val t = tweak(id)
                        tweaks = tweaks + (id to t.copy(dx = t.dx + ox / d, dy = t.dy + oy / d))
                    }
                }
                dragId = null
            }
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
        val frames = listener.onPlay(cmd, el.move)
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
        // Another finger still on the same button keeps it held (mashing with two fingers).
        if (el.key != null && pointerEl.values.any { it.key == el.key }) return
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

    /** Every finger lifted at once (pause, a new layout): no Run press is left counted. */
    private fun stopRunning() {
        if (holdRuns > 0 || stickRun) listener.onRun(false)
        holdRuns = 0
        stickRun = false
    }

    private fun setStickRun(on: Boolean) {
        if (on == stickRun) return
        stickRun = on
        if (on) haptic(strong = true)
        if (holdRuns == 0) listener.onRun(on)
    }

    private fun stickRadius() = 64 * d * state.size * tweak("stick").scale

    // How far from a fixed stick's centre a touch still takes it (in radii).
    private val FIXED_REACH = 2.5f

    private fun startStick(id: Int, x: Float, y: Float) {
        stickPointer = id
        // Fixed: always steered from its own centre, wherever the thumb lands on
        // it; floating (and hidden): the stick comes to the thumb.
        if (state.stick == StickMode.FIXED) {
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
        // pushing against the edge (not a fixed stick: it stays in its place).
        val follow = stickRadius() * 1.4f
        if (dist > follow && state.stick != StickMode.FIXED) {
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
