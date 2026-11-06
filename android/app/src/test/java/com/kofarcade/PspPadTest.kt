package com.kofarcade

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** The PSP controls, as the game screen shows them (pictures in build/reports/paparazzi). */
class PspPadTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080),
        maxPercentDifference = 0.0,
    )

    private fun pad(profile: PspProfile) = PspPad(paparazzi.context, profile, { _, _ -> }, { _, _ -> }, {})

    @Test
    fun godOfWar() = paparazzi.snapshot(pad(PspProfile.GOD_OF_WAR), "psp_god_of_war")

    @Test
    fun anyGame() = paparazzi.snapshot(pad(PspProfile()), "psp_default")
}
