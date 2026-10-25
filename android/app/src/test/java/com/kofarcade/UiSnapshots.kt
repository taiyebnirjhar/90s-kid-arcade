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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    LibraryRowUi("sengoku3 (1).zip", "Sengoku 3", "sengoku3 (1).zip · 43.9 MB", RowState.Done("This is Sengoku 3 (set 1), but it's missing 1 file the emulator needs:\n• 261-m1.m1 (sound program)", false)),
                    LibraryRowUi("mslug3.zip", "Metal Slug 3", "mslug3.zip · 76.7 MB", RowState.Idle),
                ),
                busy = true,
            ),
            "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun twoPlayerMenu() = shot("twoplayer_menu") {
        MultiplayerHub(emptyList(), "", {}, null, false, {}, {}, {}, {}, {})
    }

    @Test
    fun pickGame() = shot("twoplayer_pick") {
        PickGameScreen(listOf("kof97", "kof2002", "sengoku3", "dino").map { gameById(it)!! to it }, {}, {}, {})
    }

    @Test
    fun joinFound() = shot("twoplayer_join") {
        MultiplayerHub(
            listOf(
                HostChoice("Cadillacs and Dinosaurs", "Pixel 8", true, 2, 3, "K7Q-2M", game = gameById("dino")),
                HostChoice("KOF '97", "Galaxy A54", true, 2, 2, "M4T-XP", watchers = 1, game = gameById("kof97")),
                HostChoice("KOF 2005", "SM-S918B", false, key = "C9H-4R", game = gameById("kof2005")),
            ),
            "K7Q-2M", {}, MpStatus("You need KOF 2005 on this phone first.", "Get it from the library"), false, {}, {}, {}, {}, {},
        )
    }

    // Player 2 dropped out: their seat is kept for them, and the host can free it.
    private val roomUi = RoomUi(
        "K7Q-2M", 3,
        listOf(
            RoomPerson("You", 0, you = true, host = true),
            RoomPerson("Galaxy A54", 1, away = true, holdEnds = System.currentTimeMillis() + 42_400),
            RoomPerson("Pixel 8", -1),
            RoomPerson("SM-S918B", -1),
        ),
        watching = false,
        canFree = true,
    )

    @Test
    fun roomSheet() {
        landscape()
        shot("room_sheet") {
            OverGame {
                SheetFrame(width = 880) { RoomSheetContent(roomUi, first = false, {}, {}, {}, {}, {}, {}) }
            }
        }
    }

    @Test
    fun roomSheetPortrait() = shot("room_sheet_portrait") {
        OverGame {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp), color = Palette.panel, modifier = Modifier.width(400.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                        RoomSheetContent(roomUi, first = false, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun roomOverlay() {
        landscape()
        shot("room_overlay") {
            OverGame {
                Box(Modifier.fillMaxSize().padding(top = 12.dp, end = 14.dp)) {
                    RoomPill(
                        RoomUi("K7Q-2M", 3, listOf(RoomPerson("Rafi", 0, host = true), RoomPerson("Mim", 1, away = true), RoomPerson("You", -1, you = true), RoomPerson("Pixel 8", -1)), watching = true),
                        {}, Modifier.align(Alignment.TopEnd),
                    )
                    AnnouncementBanner(Announcement("You're player 2!", big = true), Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
                }
            }
        }
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

    private fun landscape() = paparazzi.unsafeUpdateConfig(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080),
    )

    /** A stand-in for the game's picture, undimmed. */
    @Composable
    private fun GameBehind(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.blue, Palette.red)))) {
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(0.7f to androidx.compose.ui.graphics.Color.Transparent, 0.7f to androidx.compose.ui.graphics.Color(0xFF3A2A1E)),
                ),
            )
            content()
        }
    }

    /** In-game overlays sit on the game; a stand-in picture behind them. */
    @Composable
    private fun OverGame(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.blue, Palette.red)))) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.88f), 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)))) {
                content()
            }
        }
    }

    @Test
    fun pause() {
        landscape()
        shot("pause") { GameBehind { PauseContent("KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {}) } }
    }

    @Test
    fun pauseTwoPlayers() {
        landscape()
        shot("pause_2p") {
            GameBehind {
                PauseContent(
                    "Sengoku 3", null, true, {}, {}, {}, {}, {}, {}, {}, {},
                    roomInfo = "Room key K7Q-2M · 2 of 2 playing · 1 watching",
                    roomStatus = "Game 3 · you: P1, frame 2214 · P2 2210 in, heard 12 ms · watcher heard 30 ms",
                )
            }
        }
    }

    @Test
    fun roster() {
        landscape()
        val r = com.kofarcade.core.loadRoster("kof97")!!
        shot("roster") {
            OverGame {
                RosterContent(r.teams, listOf("iori", "kyo", null).map { r.character(it) }, 1, {}, {}, {}, {})
            }
        }
    }

    @Test
    fun moveEditor() {
        landscape()
        shot("move_editor") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp), color = Palette.panel, modifier = Modifier.width(680.dp).padding(16.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                        MoveEditorContent(
                            "Iori Yagami",
                            listOf(MoveRowUi("Yaotome", "2363214+A"), MoveRowUi("Yami Barai", "236+A"), MoveRowUi("Broken", "23X+A")),
                            { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {},
                        )
                    }
                }
            }
        }
    }

    private fun controlsShot(name: String, tab: SettingsTab) {
        landscape()
        shot(name) {
            GameBehind {
                ControlsPanel(
                    Look(ScreenMode.SLIGHT, StickMode.FLOATING, 1f, 0.46f, LabelMode.SUPERS, true, false, 2, 4),
                    onLook = {}, canDetect = true, canRapid = true, autoDetect = true, onAutoDetect = {},
                    detectInfo = false, onDetectInfo = {}, onCancel = {}, onSave = {}, startTab = tab,
                )
            }
        }
    }

    @Test
    fun controls() = controlsShot("controls", SettingsTab.SCREEN)

    @Test
    fun controlsButtons() = controlsShot("controls_buttons", SettingsTab.CONTROLS)

    @Test
    fun controlsMoves() = controlsShot("controls_moves", SettingsTab.MOVES)

    @Test
    fun gameError() {
        landscape()
        shot("game_error") {
            OverGame {
                SheetFrame {
                    androidx.compose.material3.Text("This game won't start", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.material3.Text("The emulator couldn't load the ROM.", color = Palette.muted)
                    Box(Modifier.padding(top = 16.dp)) { GoldButton("Back to games", {}, Modifier.fillMaxWidth()) }
                }
            }
        }
    }

    // ----- The on-screen controls (a real HudView) -----

    private val hudListener = object : HudView.Listener {
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

    private fun hudState(): HudView.State {
        val g = gameById("kof97")!!
        val iori = com.kofarcade.core.loadRoster("kof97")!!.character("iori")!!
        return HudView.State(
            layout = g.layout,
            faceButtons = g.faceButtons,
            labels = g.labels,
            moves = iori.moves.take(6),
            system = buildList {
                g.power?.let { add(HudView.SysButton(it.name, it.cmd, it.cmd, power = true)) }
                g.system.forEach { add(HudView.SysButton(it.name, it.cmd, it.cmd)) }
            },
            slots = listOf(HudView.Slot("Iori Yagami", true), HudView.Slot("Kyo Kusanagi", false), HudView.Slot(null, false)),
            block = g.block,
        )
    }

    private fun touch(v: android.view.View, action: Int, x: Float, y: Float) {
        val e = android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** The HUD over the game; [touches] runs once it's laid out, to press things. */
    @Composable
    private fun Hud(editing: Boolean = false, touches: (HudView, Float) -> Unit = { _, _ -> }) {
        GameBehind {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    HudView(ctx, hudListener).apply {
                        state = hudState()
                        alpha = 0.46f
                        this.editing = editing
                        var done = false
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            if (!done && v.width > 0) {
                                done = true
                                touches(this, resources.displayMetrics.density)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // Where the fighter layout puts a face button (see HudView's layout).
    private fun facePos(v: HudView, d: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val s = 62 * d
        val ax = v.width - 1.3f * s - 16 * d
        val ay = v.height - s - 16 * d
        return (ax + fx * s) to (ay + fy * s)
    }

    @Test
    fun hud() {
        landscape()
        shot("hud") { Hud() }
    }

    // Thumb on the stick pushing forward, A held: both glow.
    @Test
    fun hudPressed() {
        landscape()
        shot("hud_pressed") {
            Hud { v, d ->
                val sx = 300 * d
                val sy = v.height - 110 * d
                touch(v, android.view.MotionEvent.ACTION_DOWN, sx, sy)
                touch(v, android.view.MotionEvent.ACTION_MOVE, sx + 50 * d, sy - 4 * d)
                val (ax, ay) = facePos(v, d, -0.44f, 0.6f)
                touch(v, android.view.MotionEvent.ACTION_DOWN, ax, ay)
            }
        }
    }

    @Test
    fun hudEdit() {
        landscape()
        shot("hud_edit") {
            Box(Modifier.fillMaxSize()) {
                Hud(editing = true) { v, d ->
                    val (bx, by) = facePos(v, d, 0.76f, 0.6f)
                    touch(v, android.view.MotionEvent.ACTION_DOWN, bx, by)
                    touch(v, android.view.MotionEvent.ACTION_UP, bx, by)
                }
                EditBarContent("B button", 1.1f, true, {}, {}, {}, {})
            }
        }
    }
}
