package com.kofarcade

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
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
            system = p.system.map { HudView.SysButton(it.name, it.cmd, it.cmd) }, block = p.blockHoldsR,
            facingButton = false, coinLabel = "SELECT",
        )
        hud.opacity = PspSettings().controlsOpacity
        paparazzi.snapshot(hud, name)
    }

    @Test
    fun godOfWar() = shot("psp_hud_god_of_war", PspProfile.GOD_OF_WAR)

    @Test
    fun anyGame() = shot("psp_hud_base", PspProfile())
}
