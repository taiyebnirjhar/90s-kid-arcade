package com.kofarcade

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.kofarcade.core.artSource
import com.kofarcade.core.GAMES
import com.kofarcade.core.gameById
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.URL

/**
 * Renders the app's screens with sample data. `gradle :app:recordPaparazziDebug`
 * writes them to app/src/test/snapshots/images; CI publishes them to the
 * ui-snapshots branch so the look can be checked without a phone.
 */
class UiSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.0)

    private fun shot(name: String, content: @Composable () -> Unit) =
        paparazzi.snapshot(name) {
            CompositionLocalProvider(LocalArtOverride provides TestArt::get, LocalStill provides true) {
                KofTheme { Box(Modifier.fillMaxSize().background(Palette.bg)) { content() } }
            }
        }

    private val sets = mapOf("kof97" to "kof97", "kof2002" to "kof2002", "sengoku3" to "sengoku3")

    private val homeUi get() = HomeUi(true, GAMES.associate { it.id to sets[it.id] }, gameById("sengoku3")!!, "Last played", "Continue")

    @Test
    fun home() = shot("home") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) }

    @Test
    fun homeLandscape() {
        landscape()
        shot("home_landscape") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) }
    }

    private val firstRunUi get() = HomeUi(
        false, emptyMap(), GAMES.first(), "Get started", "Add BIOS",
        "Add the Neo Geo BIOS", "neogeo.zip: every Neo Geo game needs it to start.",
    )

    @Test
    fun homeFirstRun() = shot("home_first_run") {
        HomeScreen(firstRunUi, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryKey() = shot("library_key") {
        LibraryScreen(LibraryUi(askKey = true, error = "Wrong key. Check it and try again."), "secret", {}, Library.DEFAULT_URL, {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    private val libraryUi = LibraryUi(
        askKey = false,
        rows = listOf(
            LibraryRowUi("neogeo (1).zip", "Neo Geo BIOS", "neogeo (1).zip · 1.9 MB", RowState.OnPhone, set = "neogeo"),
            LibraryRowUi("kf2k5uni.zip", "KOF 2005", "kf2k5uni.zip · 41.1 MB", RowState.Downloading(0.42f), gameById("kof2005"), "kf2k5uni"),
            LibraryRowUi("kof97.zip", "KOF '97", "kof97.zip · 27.4 MB", RowState.Done("Added KOF '97: The King of Fighters '97 (NGM-2320).", true), gameById("kof97"), "kof97"),
            LibraryRowUi("sengoku3 (1).zip", "Sengoku 3", "sengoku3 (1).zip · 43.9 MB", RowState.Done("This is Sengoku 3 (set 1), but it's missing 1 file the emulator needs:\n• 261-m1.m1 (sound program)", false), gameById("sengoku3"), "sengoku3"),
            LibraryRowUi("mslug3.zip", "Metal Slug 3", "mslug3.zip · 76.7 MB", RowState.Idle, GAMES.find { "mslug3" in it.sets }, "mslug3"),
            LibraryRowUi("dino.zip", "Cadillacs and Dinosaurs", "dino.zip · 6.2 MB", RowState.OnPhone, gameById("dino"), "dino"),
        ),
        busy = true,
    )

    @Test
    fun libraryList() = shot("library_list") {
        LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryIdle() = shot("library_idle") {
        LibraryScreen(
            libraryUi.copy(rows = libraryUi.rows.map { if (it.state is RowState.OnPhone) it else it.copy(state = RowState.Idle) }, busy = false),
            "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun libraryLandscape() {
        landscape()
        shot("library_landscape") { LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {}) }
    }

    @Test
    fun twoPlayerMenu() = shot("twoplayer_menu") {
        MultiplayerHub(emptyList(), "", {}, null, false, {}, {}, {}, {}, {}, onScan = {})
    }

    private val picks get() = listOf("kof97", "kof2002", "sengoku3", "dino").map { gameById(it)!! to it }

    @Test
    fun pickGame() = shot("twoplayer_pick") { PickGameScreen(picks, {}, {}, {}) }

    @Test
    fun pickGameLandscape() {
        landscape()
        shot("twoplayer_pick_landscape") { PickGameScreen(picks, {}, {}, {}) }
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
    fun roomSheetOnline() {
        landscape()
        shot("room_sheet_online") {
            OverGame {
                SheetFrame(width = 880) {
                    RoomSheetContent(RoomUi("K7Q-2MX", 2, listOf(RoomPerson("You", 0, you = true, host = true)), watching = false, online = true), first = true, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }

    @Test
    fun roomSheetInLine() {
        landscape()
        shot("room_sheet_in_line") {
            OverGame {
                SheetFrame(width = 880) {
                    RoomSheetContent(
                        RoomUi(
                            "K7Q-2MX", 2,
                            listOf(
                                RoomPerson("Rafi", 0, host = true), RoomPerson("Mim", 1),
                                RoomPerson("Tanu", -1, line = 1), RoomPerson("You", -1, you = true, line = 2), RoomPerson("Pixel 8", -1),
                            ),
                            watching = true, online = true, pingMs = 86,
                        ),
                        first = false, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
    }

    @Test
    fun twoPlayerMenuOnline() = shot("twoplayer_menu_online") {
        MultiplayerHub(emptyList(), "K7Q-2MX", {}, MpStatus("Looking for room K7Q-2MX…", bad = false), true, {}, {}, {}, {}, {})
    }

    @Test
    fun twoPlayerMenuNoInternet() = shot("twoplayer_menu_no_internet") {
        MultiplayerHub(emptyList(), "", {}, null, false, {}, {}, {}, {}, {}, internet = false)
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

    /** A small phone, 360dp wide: nothing may clip. */
    private fun small() = paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.NEXUS_5)

    @Test
    fun homeSmall() {
        small()
        shot("home_small") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) }
    }

    @Test
    fun homeFirstRunLandscape() {
        landscape()
        shot("home_first_run_landscape") { HomeScreen(firstRunUi, {}, {}, {}, {}, {}, {}) }
    }

    private val rafi = com.kofarcade.core.online.Profile("Rafi", 2, "K7Q2M9XA", pending = false, savedFor = "u")
    private val rafiPlays = listOf(gameById("kof97")!! to 12, gameById("dino")!! to 3, gameById("snowbros")!! to 1)

    @Test
    fun profile() = shot("profile") { ProfileScreen(ProfileUi(rafi, SyncState.Synced, rafiPlays), {}, {}, { _, _ -> }, {}) }

    @Test
    fun profileFirstSignIn() = shot("profile_signing_in") { ProfileScreen(ProfileUi(null, SyncState.Syncing, emptyList()), {}, {}, { _, _ -> }, {}) }

    @Test
    fun profileOffline() = shot("profile_offline") {
        ProfileScreen(
            ProfileUi(rafi.copy(pending = true), SyncState.Offline("No internet right now. Your profile shows from this phone and syncs when you're back online."), rafiPlays),
            {}, {}, { _, _ -> }, {},
        )
    }

    @Test
    fun profileNeverOnline() = shot("profile_never_online") { ProfileScreen(ProfileUi(null, SyncState.Offline(""), emptyList()), {}, {}, { _, _ -> }, {}) }

    @Test
    fun profilePaused() = shot("profile_paused") {
        ProfileScreen(
            ProfileUi(rafi, SyncState.Failed("Online is paused for now: the free server's limit was reached. Nearby play still works. Try again later."), rafiPlays),
            {}, {}, { _, _ -> }, {},
        )
    }

    @Test
    fun homeWithProfile() = shot("home_profile") { HomeScreen(homeUi.copy(profileName = "Rafi", profileIcon = 2), {}, {}, {}, {}, {}, {}) }

    @Test
    fun librarySmall() {
        small()
        shot("library_small") { LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {}) }
    }

    @Test
    fun libraryLoading() = shot("library_loading") {
        LibraryScreen(LibraryUi(askKey = false, loading = true), "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryError() = shot("library_error") {
        LibraryScreen(LibraryUi(askKey = false, error = "Couldn't reach the library: Unable to resolve host"), "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryEmpty() = shot("library_empty") {
        LibraryScreen(LibraryUi(askKey = false), "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun twoPlayerJoinSmall() {
        small()
        shot("twoplayer_join_small") {
            MultiplayerHub(
                listOf(HostChoice("Cadillacs and Dinosaurs", "Pixel 8", true, 2, 3, "K7Q-2M", game = gameById("dino"))),
                "", {}, null, false, {}, {}, {}, {}, {},
            )
        }
    }

    @Test
    fun pickGameSmall() {
        small()
        shot("twoplayer_pick_small") { PickGameScreen(picks, {}, {}, {}) }
    }

    @Test
    fun pickGameEmpty() = shot("twoplayer_pick_empty") { PickGameScreen(emptyList(), {}, {}, {}) }

    private fun landscape() = paparazzi.unsafeUpdateConfig(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2400, screenHeight = 1080),
    )

    /** A small landscape phone: 640 x 360 dp. */
    private fun smallLandscape() = paparazzi.unsafeUpdateConfig(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1280, screenHeight = 720, density = com.android.resources.Density.XHIGH,
        ),
    )

    /**
     * A real KOF '97 screen, if CI downloaded one (build/test-art, never
     * committed): libretro's thumbnail, stretched to 16:9 like the game.
     */
    // Decoded while rendering: Android's decoder only works inside Paparazzi's snapshot.
    private val gameSnap: androidx.compose.ui.graphics.ImageBitmap? by lazy {
        java.io.File("build/test-art/kof97_snap.png").takeIf { it.isFile }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }

    /** The game's picture (or a stand-in), undimmed. */
    @Composable
    private fun GameBehind(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.blue, Palette.red)))) {
            val snap = gameSnap
            if (snap != null) {
                androidx.compose.foundation.Image(
                    snap, null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(0.7f to androidx.compose.ui.graphics.Color.Transparent, 0.7f to androidx.compose.ui.graphics.Color(0xFF3A2A1E)),
                    ),
                )
            }
            content()
        }
    }

    /** In-game overlays sit on the game; its picture behind them, dimmed. */
    @Composable
    private fun OverGame(content: @Composable () -> Unit) {
        GameBehind {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.88f), 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)))) {
                content()
            }
        }
    }

    @Test
    fun pause() {
        landscape()
        shot("pause") {
            GameBehind {
                PauseContent(
                    "KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {},
                    playback = "60.00 frames a second, with the screen (60 Hz). Late frames made up: 0. Sound gaps: 0.",
                )
            }
        }
    }

    // A fighter's face from the game (here a made-up one) in the pause header and the HUD's team bar.
    @Test
    fun faces() {
        landscape()
        shot("faces") {
            val face = remember { syntheticFace().asImageBitmap() }
            androidx.compose.runtime.CompositionLocalProvider(LocalFaces provides mapOf("Iori Yagami" to face)) {
                Box(Modifier.fillMaxSize()) {
                    Hud(faces = true)
                    PauseContent("KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }

    @Test
    fun pauseSmall() {
        smallLandscape()
        shot("pause_small") { GameBehind { PauseContent("KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {}) } }
    }

    @Test
    fun roomSheetSmall() {
        smallLandscape()
        shot("room_sheet_small") {
            OverGame {
                SheetFrame(width = 880) { RoomSheetContent(roomUi, first = false, {}, {}, {}, {}, {}, {}) }
            }
        }
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

    // Every kind of game's pause menu: a fighter with its face, action games with their picture, and online (the game goes on).
    @Test
    fun pauseEveryKind() {
        landscape()
        for ((id, fighter) in listOf("mslug" to null, "dino" to null, "snowbros" to null, "moomesa" to null, "garou" to "Terry Bogard")) {
            val g = gameById(id)!!
            shot("pause_$id") {
                GameBehind {
                    PauseContent(
                        g.fullTitle, fighter, false, {}, {}, {}, {}, {}, {}, {}, {},
                        playback = "60.00 frames a second, with the screen (60 Hz).",
                        picture = { ArtImage(g, g.sets.first(), com.kofarcade.core.Art.TITLE, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, label = false) },
                    )
                }
            }
        }
    }

    @Test
    fun pauseOnline() {
        landscape()
        shot("pause_online") {
            Box(Modifier.fillMaxSize()) {
                Hud()
                PauseContent(
                    "KOF '97", "Iori Yagami", true, {}, {}, {}, {}, {}, {}, {}, {},
                    roomInfo = "Room K7Q-2MX · 2 of 2 playing", roomStatus = "ping 48 ms", goesOn = true, canChangeGame = false,
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

    private fun controlsShot(name: String, tab: SettingsTab, small: Boolean = false) =
        controlsShotWith(name, tab) { if (small) smallLandscape() else landscape() }

    private fun controlsShotWith(name: String, tab: SettingsTab, device: () -> Unit) {
        device()
        shot(name) {
            GameBehind {
                ControlsPanel(
                    Look(ScreenMode.SLIGHT, StickMode.FLOATING, 1f, 0.46f, LabelMode.SUPERS, true, false, 2, 4),
                    onLook = {}, canDetect = true, canRapid = true,
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
    fun controlsSmall() = controlsShot("controls_small", SettingsTab.CONTROLS, small = true)

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
            // As the game screen sets it: every fighter is detected, so no
            // team bar to pick from and no FACING button.
            block = g.block,
            facingButton = false,
        )
    }

    private fun touch(v: android.view.View, action: Int, x: Float, y: Float) {
        val e = android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /**
     * A made-up 24 x 24 pixel "face" (no game art): a coloured block head with
     * hair, eyes and a mouth, like the faces the game shows. Made while rendering.
     */
    private fun syntheticFace(): android.graphics.Bitmap {
        val n = 24
        val px = IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            when {
                y < 7 || x < 3 || x > 20 -> 0xFF2B1A3A.toInt() // hair
                (y in 10..11) && (x in 7..9 || x in 14..16) -> 0xFF101018.toInt() // eyes
                y == 17 && x in 9..14 -> 0xFF8A2A2A.toInt() // mouth
                else -> 0xFFE0A47A.toInt() // skin
            }
        }
        return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** The HUD over the game; [touches] runs once it's laid out, to press things. */
    @Composable
    private fun Hud(editing: Boolean = false, faces: Boolean = false, stick: StickMode = StickMode.FLOATING, touches: (HudView, Float) -> Unit = { _, _ -> }) {
        GameBehind {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    HudView(ctx, hudListener).apply {
                        if (faces) {
                            val face = syntheticFace()
                            faceOf = { if (it == "Iori Yagami") face else null }
                        }
                        state = hudState().copy(stick = stick)
                        opacity = 0.46f
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

    // A fixed stick, thumb dragged far up and right of it: the stick stays in
    // its place (the knob leans to its edge), it doesn't follow the thumb.
    @Test
    fun hudFixedStick() {
        landscape()
        shot("hud_fixed_stick") {
            Hud(stick = StickMode.FIXED) { v, d ->
                fun home(name: String) = HudView::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(v)
                val hx = home("homeX")
                val hy = home("homeY")
                touch(v, android.view.MotionEvent.ACTION_DOWN, hx + 30 * d, hy + 10 * d)
                touch(v, android.view.MotionEvent.ACTION_MOVE, hx + 220 * d, hy - 160 * d)
                touch(v, android.view.MotionEvent.ACTION_MOVE, hx + 420 * d, hy - 230 * d)
            }
        }
    }

    @Test
    fun hudSmall() {
        smallLandscape()
        shot("hud_small") { Hud() }
    }

    private val editLook = Look(ScreenMode.SLIGHT, StickMode.FIXED, 1f, 0.46f, LabelMode.SUPERS, true, false, 2, 4)

    // The gear's panel: every control's stick, side, size and opacity, from the side away from the buttons.
    @Test
    fun hudEditAll() {
        smallLandscape()
        shot("hud_edit_all") {
            Box(Modifier.fillMaxSize()) {
                Hud(editing = true)
                EditBarContent("Move 3", 1f, true, {}, {}, {}, {}, onAll = {})
                EditAllSheet(editLook, {}, fromLeft = true, onClose = {})
            }
        }
    }

    @Test
    fun hudEditSmall() {
        smallLandscape()
        shot("hud_edit_small") {
            Box(Modifier.fillMaxSize()) {
                Hud(editing = true)
                EditBarContent("Move 3", 1f, true, {}, {}, {}, {}, onAll = {})
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
                EditBarContent("B button", 1.1f, true, {}, {}, {}, {}, onAll = {})
            }
        }
    }

    // ----- Every phone and tablet: big text, the smallest phones, tablets -----

    /** A 360 dp phone with Android's text size turned up ([scale]: 1.5, or 2 on Android 14's largest). */
    private fun bigText(scale: Float = 1.5f, landscape: Boolean = false) = paparazzi.unsafeUpdateConfig(
        deviceConfig = if (landscape) {
            DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1280, screenHeight = 720, density = com.android.resources.Density.XHIGH, fontScale = scale)
        } else {
            DeviceConfig.NEXUS_5.copy(fontScale = scale)
        },
    )

    /** The smallest phones still sold: 320 x 568 dp. */
    private fun tiny() = paparazzi.unsafeUpdateConfig(
        deviceConfig = DeviceConfig.NEXUS_5.copy(screenWidth = 640, screenHeight = 1136, density = com.android.resources.Density.XHIGH),
    )

    /** A tablet (or an unfolded phone), landscape: 1280 x 800 dp. */
    private fun tablet(portrait: Boolean = false) = paparazzi.unsafeUpdateConfig(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            orientation = if (portrait) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE,
            screenWidth = if (portrait) 1600 else 2560, screenHeight = if (portrait) 2560 else 1600, density = com.android.resources.Density.XHIGH,
        ),
    )

    private val onlineRooms = listOf(
        OnlineChoice("KOF '97", "Rafi", true, 2, 2, open = true, game = gameById("kof97"), set = "kof97"),
        OnlineChoice("Cadillacs and Dinosaurs", "Mim", true, 1, 3, open = false, game = gameById("dino"), set = "dino"),
        OnlineChoice("KOF 2005", "Tanu", false, 1, 2, open = true, game = gameById("kof2005"), set = "kf2k5uni"),
    )

    @Composable
    private fun OnlineHub() = MultiplayerHub(
        listOf(HostChoice("Cadillacs and Dinosaurs", "Pixel 8", true, 2, 3, "K7Q-2M", game = gameById("dino"))),
        "", {}, null, false, {}, {}, {}, {}, {}, onScan = {}, online = onlineRooms,
    )

    @Test
    fun onlineRooms() = shot("twoplayer_online_rooms") { OnlineHub() }

    @Test
    fun pickGameOnline() = shot("twoplayer_pick_online") { PickGameScreen(picks, {}, {}, {}, online = true) }

    @Test
    fun roomSheetOpen() {
        landscape()
        shot("room_sheet_open") {
            OverGame {
                SheetFrame(width = 880) {
                    RoomSheetContent(
                        RoomUi("K7Q-2MX", 2, listOf(RoomPerson("You", 0, you = true, host = true)), watching = false, canFree = true, online = true, open = true, canOpen = true),
                        first = true, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
    }

    @Test fun bigTextHome() { bigText(); shot("big_text_home") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) } }
    @Test fun bigTextHomeFirstRun() { bigText(); shot("big_text_home_first_run") { HomeScreen(firstRunUi, {}, {}, {}, {}, {}, {}) } }
    @Test fun biggestTextHome() { bigText(2f); shot("biggest_text_home") { HomeScreen(homeUi.copy(profileName = "Rafi", profileIcon = 2), {}, {}, {}, {}, {}, {}) } }
    @Test fun bigTextLibrary() { bigText(); shot("big_text_library") { LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {}) } }
    @Test fun bigTextLibraryKey() { bigText(); shot("big_text_library_key") { LibraryScreen(LibraryUi(askKey = true, error = "Wrong key. Check it and try again."), "secret", {}, Library.DEFAULT_URL, {}, false, {}, {}, {}, {}, {}, {}, {}) } }
    @Test fun bigTextOnline() { bigText(); shot("big_text_online") { OnlineHub() } }
    @Test fun biggestTextOnline() { bigText(2f); shot("biggest_text_online") { OnlineHub() } }
    @Test fun bigTextPickOnline() { bigText(); shot("big_text_pick_online") { PickGameScreen(picks, {}, {}, {}, online = true) } }
    @Test fun bigTextProfile() { bigText(); shot("big_text_profile") { ProfileScreen(ProfileUi(rafi, SyncState.Synced, rafiPlays), {}, {}, { _, _ -> }, {}) } }
    @Test fun bigTextDialog() {
        bigText(2f)
        shot("biggest_text_dialog") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DialogCard("Remove KOF '97?", "Deletes the ROM from this app. You can add it again any time.", "Keep it" to {}, "Remove" to {}, Tone.BAD, dangerSecondary = true)
            }
        }
    }
    @Test fun bigTextPause() { bigText(1.5f, landscape = true); shot("big_text_pause") { GameBehind { PauseContent("KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {}) } } }
    @Test fun bigTextRoomSheet() {
        bigText(1.5f, landscape = true)
        shot("big_text_room_sheet") { OverGame { SheetFrame(width = 880) { RoomSheetContent(roomUi.copy(online = true, open = false), first = false, {}, {}, {}, {}, {}, {}) } } }
    }
    @Test fun bigTextControls() = controlsShotWith("big_text_controls", SettingsTab.CONTROLS) { bigText(1.5f, landscape = true) }
    @Test fun bigTextHud() { bigText(1.5f, landscape = true); shot("big_text_hud") { Hud() } }

    @Test fun tinyHome() { tiny(); shot("tiny_home") { HomeScreen(homeUi.copy(profileName = "Rafi", profileIcon = 2), {}, {}, {}, {}, {}, {}) } }
    @Test fun tinyLibrary() { tiny(); shot("tiny_library") { LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {}) } }
    @Test fun tinyOnline() { tiny(); shot("tiny_online") { OnlineHub() } }
    @Test fun tinyPickOnline() { tiny(); shot("tiny_pick_online") { PickGameScreen(picks, {}, {}, {}, online = true) } }
    @Test fun tinyProfile() { tiny(); shot("tiny_profile") { ProfileScreen(ProfileUi(rafi, SyncState.Synced, rafiPlays), {}, {}, { _, _ -> }, {}) } }

    @Test fun tabletHome() { tablet(); shot("tablet_home") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) } }
    @Test fun tabletHomePortrait() { tablet(portrait = true); shot("tablet_home_portrait") { HomeScreen(homeUi, {}, {}, {}, {}, {}, {}) } }
    @Test fun tabletLibrary() { tablet(); shot("tablet_library") { LibraryScreen(libraryUi, "", {}, "", {}, false, {}, {}, {}, {}, {}, {}, {}) } }
    @Test fun tabletOnline() { tablet(); shot("tablet_online") { OnlineHub() } }
    @Test fun tabletPickOnline() { tablet(); shot("tablet_pick_online") { PickGameScreen(picks, {}, {}, {}, online = true) } }
    @Test fun tabletProfile() { tablet(); shot("tablet_profile") { ProfileScreen(ProfileUi(rafi, SyncState.Synced, rafiPlays), {}, {}, { _, _ -> }, {}) } }
    @Test fun tabletPause() { tablet(); shot("tablet_pause") { GameBehind { PauseContent("KOF '97", "Iori Yagami", false, {}, {}, {}, {}, {}, {}, {}, {}) } } }
    @Test fun tabletRoomSheet() { tablet(); shot("tablet_room_sheet") { OverGame { SheetFrame(width = 880) { RoomSheetContent(roomUi, first = false, {}, {}, {}, {}, {}, {}) } } } }
    @Test fun tabletControls() = controlsShotWith("tablet_controls", SettingsTab.CONTROLS) { tablet() }
    @Test fun tabletHud() { tablet(); shot("tablet_hud") { Hud() } }

    // ----- Room chat -----

    private val chatSample = listOf(
        ChatShown(1, 1, "Rafi", "d1", "GG", true, false, 1_000),
        ChatShown(2, 0, "You", "me", "Rematch?", true, true, 2_000),
        ChatShown(3, 2, "Tanu", "d2", "that last combo was clean, how do you do the 2nd super", false, false, 3_000),
        ChatShown(4, 1, "Rafi", "d1", "623+P then cancel into the DM", false, false, 4_000),
        ChatShown(5, 3, "Mim", "d3", "Wait a sec", true, false, 5_000),
    )

    @Test
    fun chatPanel() {
        landscape()
        shot("chat_panel") {
            GameBehind {
                ChatPanel(chatSample, muted = setOf("d3"), canReport = true, onSay = { _, _ -> }, onMute = { _, _ -> }, onReport = { _, _ -> }, onClose = {})
            }
        }
    }

    @Test
    fun chatBubbles() {
        landscape()
        shot("chat_bubbles") {
            Box(Modifier.fillMaxSize()) {
                Hud()
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp)) {
                    MicButton(on = true, talking = false, pushToTalk = false, onToggle = {}, onTalk = {})
                    ChatButton(2, {})
                    RoomPill(roomUi.copy(online = true, pingMs = 48), {})
                }
                Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 60.dp)) {
                    SpeakingChips(listOf("Rafi"), Modifier.padding(bottom = 4.dp))
                    ChatBubbles(chatSample.take(4), max = 2, now = { 4_500 })
                }
            }
        }
    }

    @Test
    fun roomSheetVoiceHost() {
        landscape()
        shot("room_sheet_voice_host") {
            OverGame {
                SheetFrame(width = 880) {
                    RoomSheetContent(
                        RoomUi(
                            "K7Q-2MX", 2,
                            listOf(
                                RoomPerson("You", 0, you = true, host = true, id = 0), RoomPerson("Mim", 1, id = 1, voiceMuted = true),
                                RoomPerson("Tanu", -1, id = 2), RoomPerson("Pixel 8", -1, id = 3),
                            ),
                            watching = false, canFree = true, online = true, pingMs = 40, open = true, canOpen = true, voiceHost = true, watchersListen = true,
                        ),
                        first = false, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
    }

    @Test
    fun onlineFriends() {
        val ui = FriendsUi(
                    myCode = "K7Q2MX8P",
                    friends = listOf(
                        com.kofarcade.core.online.Friend("1", "Tanu", 4, com.kofarcade.core.online.Friend.Status.INCOMING),
                        com.kofarcade.core.online.Friend("2", "Rafi", 2, com.kofarcade.core.online.Friend.Status.FRIEND, online = true, playing = "kof97", room = "K7Q2MX", roomSpace = true),
                        com.kofarcade.core.online.Friend("3", "Mim", 9, com.kofarcade.core.online.Friend.Status.FRIEND, online = true),
                        com.kofarcade.core.online.Friend("4", "Shuvo", 30, com.kofarcade.core.online.Friend.Status.FRIEND),
                        com.kofarcade.core.online.Friend("5", "Nabil", 12, com.kofarcade.core.online.Friend.Status.OUTGOING),
                    ),
                    invites = listOf(com.kofarcade.core.online.Invite(9, "2", "Rafi", 2, "K7Q2MX", "kof97", 0)),
                )
        shot("online_friends") {
            Column(Modifier.padding(16.dp)) {
                InviteCards(ui.invites, false, {}, {})
                FriendsSection(ui, false, "", {}, {}, {}, { _, _ -> }, {}, {}, {})
            }
        }
    }

    @Test fun gameOnJoining() = shot("game_on_joining") { GameOnLoading("Connecting to Rafi's room…", onCancel = {}) }

    @Test
    fun gameOnLandscape() {
        landscape()
        shot("game_on_landscape") { GameBehind { GameOnLoading("Joining Rafi's room…", onCancel = {}, overGame = true) } }
    }

    // The room as it opens (first) and the GAME ON screen, at every kind of screen: nothing cut, the main button in view.
    @Composable
    private fun FirstRoom() = OverGame {
        val ui = RoomUi("K7Q-2MX", 2, listOf(RoomPerson("You", 0, you = true, host = true)), watching = false, online = true, open = true, canOpen = true, voiceHost = true)
        SheetFrame(width = 880, footer = { RoomSheetActions(ui, true, {}, {}, {}, {}) }) {
            RoomSheetContent(ui, first = true, {}, {}, {}, {}, {}, {}, withActions = false)
        }
    }

    @Test fun breakpointsSmallLandscape() {
        smallLandscape()
        shot("bp_room_small_landscape") { FirstRoom() }
        shot("bp_gameon_small_landscape") { GameOnLoading("Connecting to Rafi's room…", onCancel = {}, overGame = true) }
    }
    @Test fun breakpointsPhoneLandscape() {
        landscape()
        shot("bp_room_phone_landscape") { FirstRoom() }
    }
    @Test fun breakpointsTiny() {
        tiny()
        shot("bp_room_tiny") { FirstRoom() }
        shot("bp_gameon_tiny") { GameOnLoading("Finding room K7Q-2MX…", onCancel = {}) }
    }
    @Test fun breakpointsTablet() {
        tablet()
        shot("bp_room_tablet") { FirstRoom() }
        shot("bp_gameon_tablet") { GameOnLoading("Making your room…", onCancel = {}) }
    }
    @Test fun breakpointsTabletPortrait() {
        tablet(portrait = true)
        shot("bp_room_tablet_portrait") { FirstRoom() }
    }
    @Test fun breakpointsBigText() {
        bigText(1.5f, landscape = true)
        shot("bp_room_big_text") { FirstRoom() }
        shot("bp_gameon_big_text") { GameOnLoading("Connecting to Rafi's room…", onCancel = {}, overGame = true) }
    }

    @Test fun smallChatPanel() {
        smallLandscape()
        shot("small_chat_panel") {
            GameBehind {
                ChatPanel(chatSample, muted = emptySet(), canReport = false, onSay = { _, _ -> }, onMute = { _, _ -> }, onReport = { _, _ -> }, onClose = {})
            }
        }
    }
}

/**
 * The games' pictures for the snapshots (by artFile): the ones the build
 * fetched for the app (build/generated/gameArt), else downloaded once into
 * build/test-art (never committed: they're the games' own pictures).
 * Offline, a picture is null and the stand-in tile shows.
 */
private object TestArt {
    private val dir = File("build/test-art").apply { mkdirs() }
    private val cache = HashMap<String, ImageBitmap?>()

    fun get(path: String): ImageBitmap? {
        if (path in cache) return cache[path]
        File("build/generated/gameArt/art", path).takeIf { it.isFile }?.let { built ->
            return BitmapFactory.decodeFile(built.path)?.asImageBitmap().also { cache[path] = it }
        }
        val url = artSource(path)
        val picture = runCatching {
            val file = File(dir, path.replace('/', '_'))
            if (!file.exists()) {
                val conn = URL(url).openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                }
                val tmp = File(dir, file.name + ".part")
                conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }.getOrNull()
        cache[url] = picture
        return picture
    }
}
