package com.kofarcade

import android.graphics.RectF
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.Layout
import com.kofarcade.core.loadRoster
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.hypot

/**
 * Every game's controls, laid out as the game screen sets them (a fighter
 * with the longest move list on the ring), on a phone, a small phone and a
 * tablet, at the default button size and the largest: no two controls touch
 * (a gap of [GAP_DP] at least, so a thumb presses the one it means) and all
 * are on screen. Each layout is saved as a picture too (build/reports/paparazzi).
 */
@RunWith(Parameterized::class)
class HudLayoutTest(private val screen: String) {
    companion object {
        const val GAP_DP = 4f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf("phone", "small", "tablet")
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device(screen), maxPercentDifference = 0.0)

    private fun device(screen: String): DeviceConfig = when (screen) {
        // A 20:9 phone, as the controls were laid out on.
        "phone" -> DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080)
        // 640 x 360 dp.
        "small" -> DeviceConfig.PIXEL_6.copy(
            orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1280, screenHeight = 720, density = Density.XHIGH,
        )
        // 1280 x 800 dp.
        else -> DeviceConfig.PIXEL_C.copy(
            orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2560, screenHeight = 1600, density = Density.XHIGH,
        )
    }

    private val listener = object : HudView.Listener {
        override fun onDir(dir: Int) = Unit
        override fun onKey(key: com.kofarcade.core.Key, down: Boolean) = Unit
        override fun onPlay(cmd: String, move: com.kofarcade.core.Move?) = 30
        override fun onMenu() = Unit
        override fun onFlip() = Unit
        override fun onSlot(index: Int) = Unit
        override fun onRapid() = Unit
        override fun onBlock(relDir: Int?) = Unit
        override fun onRun(on: Boolean) = Unit
    }

    /** What the game screen shows for [game] (GameActivity.updateHud), with its longest move list. */
    private fun state(game: Game, size: Float, leftHanded: Boolean): HudView.State {
        val moves = if (game.layout == Layout.FIGHTER) {
            loadRoster(game.roster!!)!!.characters.maxBy { it.moves.size }.moves.take(6)
        } else {
            game.moves
        }
        return HudView.State(
            layout = game.layout,
            faceButtons = game.faceButtons,
            labels = game.labels,
            moves = moves,
            system = buildList {
                game.power?.let { add(HudView.SysButton(it.name, it.cmd, it.cmd, power = true)) }
                game.system.forEach { add(HudView.SysButton(it.name, it.cmd, it.cmd)) }
                if (game.run) add(HudView.SysButton("Run", "hold", null, hold = true))
            },
            block = game.block,
            run = game.run,
            facingButton = false,
            size = size,
            leftHanded = leftHanded,
        )
    }

    /** How far apart two controls are (px): negative where they overlap. */
    private fun gap(a: Pair<RectF, Boolean>, b: Pair<RectF, Boolean>): Float {
        val (ra, roundA) = a
        val (rb, roundB) = b
        if (roundA && roundB) {
            return hypot(ra.centerX() - rb.centerX(), ra.centerY() - rb.centerY()) - ra.width() / 2 - rb.width() / 2
        }
        if (roundA != roundB) {
            val (c, r) = if (roundA) ra to rb else rb to ra
            val nx = c.centerX().coerceIn(r.left, r.right)
            val ny = c.centerY().coerceIn(r.top, r.bottom)
            return hypot(c.centerX() - nx, c.centerY() - ny) - c.width() / 2
        }
        val dx = maxOf(rb.left - ra.right, ra.left - rb.right)
        val dy = maxOf(rb.top - ra.bottom, ra.top - rb.bottom)
        return if (dx > 0 && dy > 0) hypot(dx, dy) else maxOf(dx, dy)
    }

    private fun problems(game: Game, size: Float, leftHanded: Boolean = false): List<String> {
        val view = HudView(paparazzi.context, listener)
        view.state = state(game, size, leftHanded)
        paparazzi.snapshot(view, "${game.id}_${(size * 100).toInt()}${if (leftHanded) "_left" else ""}")
        val density = view.resources.displayMetrics.density
        val spots = view.controlSpots()
        val out = mutableListOf<String>()
        for (i in spots.indices) {
            val (id, r) = spots[i]
            if (r.left < -0.5f || r.top < -0.5f || r.right > view.width + 0.5f || r.bottom > view.height + 0.5f) out += "$id is off screen"
            for (j in i + 1 until spots.size) {
                val (other, ro, roundO) = spots[j]
                if (other == id) continue // the pills of one bar
                val g = gap(r to spots[i].third, ro to roundO) / density
                if (g < GAP_DP) out += "$id and $other: ${"%.1f".format(g)} dp apart"
            }
        }
        return out.map { "${game.id} at ${(size * 100).toInt()}%${if (leftHanded) ", left-handed" else ""}: $it" }
    }

    @Test
    fun everyGamesControlsHaveRoomToPressCleanly() {
        val found = GAMES.flatMap { problems(it, HudView.DEFAULT_SIZE) + problems(it, 1f) }
        assertTrue("$screen:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theLargestButtonsAndTheLeftHandedLayoutToo() {
        val found = GAMES.flatMap { problems(it, 1.4f) + problems(it, HudView.DEFAULT_SIZE, leftHanded = true) }
        assertTrue("$screen:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
