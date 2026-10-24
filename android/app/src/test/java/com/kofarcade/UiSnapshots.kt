package com.kofarcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.kofarcade.core.GAMES
import com.kofarcade.core.gameById
import org.junit.Rule
import org.junit.Test

/**
 * Renders the app's screens with sample data. `gradle :app:recordPaparazziDebug`
 * writes them to app/src/test/snapshots/images; CI publishes them to the
 * ui-snapshots branch so the look can be checked without a phone.
 */
class UiSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.0)

    private fun shot(name: String, content: @Composable () -> Unit) =
        paparazzi.snapshot(name) { KofTheme { Box(Modifier.fillMaxSize().background(Palette.bg)) { content() } } }

    private val sets = mapOf("kof97" to "kof97", "kof2002" to "kof2002", "sengoku3" to "sengoku3")

    @Test
    fun home() = shot("home") {
        HomeScreen(
            HomeUi(true, GAMES.associate { it.id to sets[it.id] }, gameById("sengoku3")!!, "Continue", "Play"),
            {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun homeFirstRun() = shot("home_first_run") {
        HomeScreen(HomeUi(false, emptyMap(), GAMES.first(), "Get started", "Add BIOS"), {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryKey() = shot("library_key") {
        LibraryScreen(LibraryUi(askKey = true, error = "Wrong key. Check it and try again."), "secret", {}, Library.DEFAULT_URL, {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryList() = shot("library_list") {
        LibraryScreen(
            LibraryUi(
                askKey = false,
                rows = listOf(
                    LibraryRowUi("neogeo (1).zip", "Neo Geo BIOS", "neogeo (1).zip · 1.9 MB", RowState.OnPhone),
                    LibraryRowUi("kf2k5uni.zip", "KOF 2005", "kf2k5uni.zip · 41.1 MB", RowState.Downloading(0.42f)),
                    LibraryRowUi("kof97.zip", "KOF '97", "kof97.zip · 27.4 MB", RowState.Done("Added KOF '97: The King of Fighters '97 (NGM-2320).", true)),
                    LibraryRowUi("sengoku3.zip", "sengoku3", "43.9 MB", RowState.Done("This is Sengoku 3 (set 1), but it's missing 1 file the emulator needs:\n• 261-m1.m1 (sound program)", false)),
                    LibraryRowUi("mslug3.zip", "mslug3", "76.7 MB", RowState.Idle),
                ),
                busy = true,
            ),
            "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun twoPlayerMenu() = shot("twoplayer_menu") { TwoPlayerMenu({}, {}, {}) }

    @Test
    fun pickGame() = shot("twoplayer_pick") {
        PickGameScreen(listOf(GameChoice("KOF '97", "The King of Fighters"), GameChoice("Sengoku 3", "Sengoku")), true, {}, {}, {})
    }

    @Test
    fun hostingWaiting() = shot("twoplayer_hosting") {
        HostingScreen("Sengoku 3", "Waiting for player 2", false, listOf("192.168.43.1"), null, {}, {}, {})
    }

    @Test
    fun hostingRequest() = shot("twoplayer_request") {
        HostingScreen("Sengoku 3", "Waiting for player 2", false, listOf("192.168.43.1"), "SM-S918B", {}, {}, {})
    }

    @Test
    fun joinLooking() = shot("twoplayer_join_empty") {
        JoinScreen(emptyList(), "", {}, null, false, false, {}, {}, {}, {})
    }

    @Test
    fun joinFound() = shot("twoplayer_join") {
        JoinScreen(
            listOf(HostChoice("Sengoku 3", "Pixel 8", true), HostChoice("KOF 2005", "SM-S918B", false)),
            "", {}, "You need KOF 2005 on this phone first.", true, false, {}, {}, {}, {},
        )
    }

    @Test
    fun dialogAdd() = shot("dialog_add") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialogCard(
                "Add Metal Slug",
                "Pick its zip from your phone (any file name works: the app checks what's inside), or get it from your library.",
                "From my library" to {},
                "Choose file" to {},
            )
        }
    }

    @Test
    fun dialogBadRom() = shot("dialog_bad_rom") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialogCard(
                "This zip won't run",
                "This is Sengoku 3 (set 1), but it's missing 1 file the emulator needs:\n• 261-m1.m1 (sound program)\n\nIt's from an older or different ROM set. You need a copy made for current FBNeo.",
                "OK" to {},
                tone = Tone.BAD,
            )
        }
    }

    @Test
    fun dialogRemove() = shot("dialog_remove") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialogCard("Remove KOF '97?", "Deletes the ROM from this app. You can add it again any time.", "Keep it" to {}, "Remove" to {}, Tone.BAD, dangerSecondary = true)
        }
    }

    @Test
    fun controls() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080))
        shot("controls") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(shape = RoundedCornerShape(18.dp), color = Palette.panel.copy(alpha = 0.94f), modifier = Modifier.width(420.dp).padding(16.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                        ControlsPanel(
                            Look(ScreenMode.SLIGHT, StickMode.FLOATING, 1f, 0.8f, LabelMode.SUPERS, true, false, 2, 4),
                            {}, true, true, {}, false, {}, {}, {},
                        )
                    }
                }
            }
        }
    }
}
