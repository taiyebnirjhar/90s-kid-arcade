package com.kofarcade

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A PSP game on the arcade controls, as its game screen sets them (pictures in build/reports/paparazzi). */
class PspHudTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080),
        maxPercentDifference = 0.0,
    )

    private val listener = object : HudView.Listener {
        override fun onDir(dir: Int) = Unit
        override fun onKey(key: Key, down: Boolean) = Unit
        override fun onPlay(cmd: String, move: Move?) = 30
        override fun onMenu() = Unit
        override fun onFlip() = Unit
        override fun onSlot(index: Int) = Unit
        override fun onRapid() = Unit
        override fun onBlock(relDir: Int?) = Unit
        override fun onRun(on: Boolean) = Unit
    }

    private fun shot(name: String, p: PspProfile) {
        val hud = HudView(paparazzi.context, listener)
        hud.state = HudView.State(
            layout = Layout.FIGHTER, faceButtons = listOf('A', 'B', 'C', 'D'), labels = p.labels, moves = p.moves,
            system = p.system.map {
                when (it.cmd) {
                    PspProfile.HOLD_L -> HudView.SysButton(it.name, p.shoulderHints.first.ifEmpty { "hold" }, null, holdKey = Key.E)
                    PspProfile.HOLD_R -> HudView.SysButton(it.name, p.shoulderHints.second.ifEmpty { "hold" }, null, holdKey = Key.F)
                    PspProfile.LATCH_R -> HudView.SysButton(it.name, p.shoulderHints.second, it.cmd)
                    in PspProfile.LATCHES -> HudView.SysButton(it.name, "tap on", it.cmd)
                    else -> HudView.SysButton(it.name, it.cmd.replace("pad:2", "✚↓").replace("EF", "L+R"), it.cmd)
                }
            }, block = p.blockHoldsL,
            facingButton = false, coinLabel = "SELECT", showCoin = p.select, faceNames = PspProfile.FACE_NAMES,
        )
        hud.anchors = p.layout
        hud.opacity = DEFAULT_OPACITY
        paparazzi.snapshot(hud, name)
        // No two controls on top of each other (the arcade layout's rule: 4 dp apart).
        val d = hud.resources.displayMetrics.density
        val spots = hud.controlSpots()
        for (i in spots.indices) for (j in i + 1 until spots.size) {
            val (a, ra) = spots[i]
            val (b, rb) = spots[j]
            if (a == b) continue
            val gap = maxOf(rb.left - ra.right, ra.left - rb.right, rb.top - ra.bottom, ra.top - rb.bottom)
            assertTrue("$name: $a and $b overlap", gap > -0.5f * d || !android.graphics.RectF.intersects(ra, rb) ||
                kotlin.math.hypot(ra.centerX() - rb.centerX(), ra.centerY() - rb.centerY()) >= (ra.width() + rb.width()) / 2 + 4 * d)
        }
    }

    @Test
    fun godOfWar() = shot("psp_hud_god_of_war", PspProfile.GOD_OF_WAR)

    @Test
    fun chainsOfOlympus() = shot("psp_hud_chains_of_olympus", PspProfile.CHAINS_OF_OLYMPUS)

    @Test
    fun needForSpeed() = shot("psp_hud_need_for_speed", PspProfile.NEED_FOR_SPEED)

    /** Prints where each control sits on the owner's phone (2408 x 1080, 420 dpi, size 85%), for the default layout. */
    @Test
    fun printSpotsOwnersPhone() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2408, screenHeight = 1080, density = com.android.resources.Density.create(420)))
        val p = PspProfile.GOD_OF_WAR
        val hud = HudView(paparazzi.context, listener)
        hud.state = HudView.State(
            layout = Layout.FIGHTER, faceButtons = listOf('A', 'B', 'C', 'D'), labels = p.labels, moves = p.moves,
            system = p.system.map { HudView.SysButton(it.name, "", it.cmd) }, block = p.blockHoldsL,
            facingButton = false, coinLabel = "SELECT", showCoin = p.select, faceNames = PspProfile.FACE_NAMES, size = 0.85f,
        )
        hud.anchors = p.layout
        paparazzi.snapshot(hud, "spots")
        for ((id, r, _) in hud.controlSpots()) println("SPOT $id ${r.centerX()} ${r.centerY()} ${r.width() / 2}")
    }

    @Test
    fun assassinsCreed() = shot("psp_hud_assassins_creed", PspProfile.ASSASSINS_CREED)

    @Test
    fun anyGame() = shot("psp_hud_base", PspProfile())

    @Test
    fun pauseMenu() = paparazzi.snapshot(name = "psp_pause") {
        // Still: the menu's slide-in shows finished.
        androidx.compose.runtime.CompositionLocalProvider(LocalStill provides true) { KofTheme {
            PauseContent(
                title = "God of War - Ghost of Sparta (USA)", fighter = null, netplay = false,
                playback = "60 fps · full speed 99% · slow 3/478 s · stalls 1 (longest 300 ms)",
                onResume = {}, onRestart = {}, onSave = {}, onLoad = {}, onEditMoves = {}, onLayout = {}, onControls = {}, onQuit = {},
                extra = listOf("Graphics & speed" to {}, "Speed report" to {}),
            )
        } }
    }
}
