package com.kofarcade

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.gameById
import com.kofarcade.core.loadRoster
import com.kofarcade.core.parseCommand
import com.kofarcade.core.toArrows
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Not committed: real game frames under the real controls, with the presses as real touches (marketing video). */
class RealPlay {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2408, screenHeight = 1080, density = Density.create(420)),
        maxPercentDifference = 1.0,
    )
    private val listener = object : HudView.Listener {
        override fun onDir(dir: Int) = Unit
        override fun onKey(key: Key, down: Boolean) = Unit
        override fun onPlay(cmd: String, move: Move?) = 12
        override fun onMenu() = Unit
        override fun onFlip() = Unit
        override fun onSlot(index: Int) = Unit
        override fun onRapid() = Unit
        override fun onBlock(relDir: Int?) = Unit
        override fun onRun(on: Boolean) = Unit
    }
    private fun sub(cmd: String): String { val p = parseCommand(cmd); return if (p.parts.isEmpty()) p.buttons.toList().joinToString("+") else toArrows(cmd) }
    private val DIRV = mapOf(1 to (-1 to 1), 2 to (0 to 1), 3 to (1 to 1), 4 to (-1 to 0), 5 to (0 to 0), 6 to (1 to 0), 7 to (-1 to -1), 8 to (0 to -1), 9 to (1 to -1))

    private fun run(scene: String, gameId: String, character: String?) {
        val game = gameById(gameId)!!
        val c = character?.let { game.roster?.let(::loadRoster)?.character(it) }
        val moves = if (game.layout == Layout.FIGHTER) c?.moves.orEmpty().take(6) else game.moves
        val system = buildList {
            game.power?.let { add(HudView.SysButton(it.name, it.cmd, it.cmd, power = true)) }
            game.system.forEach { add(HudView.SysButton(it.name, sub(it.cmd), it.cmd)) }
            if (game.run) add(HudView.SysButton("Run", "hold", null, hold = true))
        }
        val ctx = paparazzi.context
        val w = 2408; val h = 1080; val gw = h * 16 / 9
        val root = FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(img, FrameLayout.LayoutParams(gw, h).apply { leftMargin = (w - gw) / 2 })
        val hud = HudView(ctx, listener)
        hud.state = HudView.State(layout = game.layout, faceButtons = game.faceButtons, labels = game.labels, moves = moves,
            system = system, block = game.block, run = game.run, facingButton = false, size = 1f)
        hud.opacity = DEFAULT_OPACITY
        root.addView(hud, FrameLayout.LayoutParams(w, h))
        fun layout() { root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h) }
        layout()
        val spots = hud.controlSpots().associate { it.first to it.second }
        // Pointers now down: id -> (x, y). 0 = the stick, 1-2 = buttons.
        val down = linkedMapOf<Int, Pair<Float, Float>>()
        val t0 = SystemClock.uptimeMillis()
        var tick = 0L
        fun send(action: Int, actionId: Int) {
            val ids = down.keys.toList()
            val props = ids.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = ids.map { val (x, y) = down[it]!!; MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }.toTypedArray()
            val idx = ids.indexOf(actionId).coerceAtLeast(0)
            val act = if (action == MotionEvent.ACTION_MOVE) action else if (ids.size > 1) (if (action == MotionEvent.ACTION_DOWN) MotionEvent.ACTION_POINTER_DOWN else MotionEvent.ACTION_POINTER_UP) or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
            val e = MotionEvent.obtain(t0, t0 + tick, act, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            hud.dispatchTouchEvent(e); e.recycle()
        }
        val lines = File(System.getenv("RP_DIR"), "realplay_${scene}.txt").readLines()
        lines.forEachIndexed { n, line ->
            val (path, dirS, btnS, mvS) = line.split("|")
            tick += 33
            img.setImageBitmap(BitmapFactory.decodeFile(path))
            val st = spots["stick"]!!
            val want = linkedMapOf<Int, Pair<Float, Float>>()
            val (dx, dy) = DIRV[dirS.toInt()]!!
            if (mvS.isEmpty() && (dx != 0 || dy != 0)) {
                val r = st.width() / 2 * 0.6f; val nrm = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                want[0] = (st.centerX() + dx / nrm * r) to (st.centerY() + dy / nrm * r)
            }
            val targets = if (mvS.isNotEmpty()) listOf(if (mvS == "sys0") "sys:0" else "move:$mvS") else btnS.split(",").filter { it in listOf("A", "B", "C", "D") }.map { "face:$it" }
            targets.take(2).forEachIndexed { i, id -> spots[id]?.let { want[i + 1] = it.centerX() to it.centerY() } }
            // Up whatever let go or moved to another control; move the stick; down what's new.
            for (id in down.keys.toList()) {
                val now = want[id]
                if (now == null || (id != 0 && now != down[id])) { send(if (down.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id); down.remove(id) }
            }
            if (want[0] != null && down[0] != null && want[0] != down[0]) { down[0] = want[0]!!; send(MotionEvent.ACTION_MOVE, 0) }
            for ((id, p) in want) if (id !in down) { down[id] = p; send(if (down.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id) }
            layout()
            paparazzi.snapshot(root, "${scene}_%04d".format(n))
        }
    }

    @Test fun kof() = run("kof", "kof2002mp", "kyo")
    @Test fun online() = run("online", "kof2002mp", "kyo")
    @Test fun dino() = run("dino", "dino", null)
    @Test fun mslug() = run("mslug", "mslugx", null)
    @Test fun sg3() = run("sg3", "sengoku3", null)
}
