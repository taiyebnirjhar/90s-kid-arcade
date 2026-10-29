package com.kofarcade

import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kofarcade.core.BUTTONS
import com.kofarcade.core.Character
import com.kofarcade.core.DetectSpec
import com.kofarcade.core.Detection
import com.kofarcade.core.FACE_KEYS
import com.kofarcade.core.FaceKeeper
import com.kofarcade.core.Game
import com.kofarcade.core.SeatWatch
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.Pad
import com.kofarcade.core.Picture
import com.kofarcade.core.PortraitSpec
import com.kofarcade.core.PadMap
import com.kofarcade.core.Ram
import com.kofarcade.core.Roster
import com.kofarcade.core.Timing
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.RoomListener
import com.kofarcade.core.net.NetLog
import com.kofarcade.core.net.BeaconListener
import com.kofarcade.core.net.lanAddress
import com.kofarcade.core.detect
import com.kofarcade.core.detectSpec
import com.kofarcade.core.portraitSpec
import com.kofarcade.core.fightHud
import com.kofarcade.core.shows
import com.kofarcade.core.fighterBase
import com.kofarcade.core.gameById
import com.kofarcade.core.loadRoster
import com.kofarcade.core.parseCommand
import com.kofarcade.core.toArrows
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.math.roundToInt

/** Dialogs that can sit over the game. The game pauses while one is open. */
sealed interface GameDialog {
    data object Pause : GameDialog
    data class Roster(val slot: Int) : GameDialog
    data class Editor(val character: Character) : GameDialog
    data object Controls : GameDialog
    data class Error(val message: String, val title: String = "Can't start the game") : GameDialog

    /** Multiplayer: the room (key, seats, who watches). [first]: it just opened, here. */
    data class Room(val first: Boolean = false) : GameDialog

    /** Multiplayer: load this phone's saved state for everyone? */
    data object LoadForEveryone : GameDialog
}

/** How the game screen looks and plays: saved in [Prefs], or previewed while Controls is open. */
data class Look(
    val screen: ScreenMode,
    val stick: StickMode,
    val size: Float,
    val opacity: Float,
    val labelMode: LabelMode,
    val haptics: Boolean,
    val leftHanded: Boolean,
    val stepFrames: Int,
    val pressFrames: Int,
    val rapidFire: Boolean = true,
    val sound: Boolean = true,
    val volume: Float = 1f,
)

fun Prefs.look() = Look(screen, stick, size, opacity, labelMode, haptics, leftHanded, stepFrames, pressFrames, rapidFire, soundOn, volume)

/** Runs one game: FBNeo through LibretroDroid, with our touch controls on top. */
class GameActivity : ComponentActivity(), HudView.Listener {
    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_SET = "set"
        /** Multiplayer: play in [Netplay.room] instead of alone. */
        const val EXTRA_NETPLAY = "netplay"
        private const val MAX_MOVES = 6
        private const val CORE = "libfbneo_libretro_android.so"
        // How often a seat's "in the fight" byte is looked at (Game.seats with a countdown).
        private const val SEAT_SAMPLE_MS = 500L
        private const val SYSTEM_RAM = 2 // RETRO_MEMORY_SYSTEM_RAM: the Neo Geo's 68000 RAM
        private const val DETECT_MS = 250L
        // Looking for a lost room: as long as a seat is held, every couple of seconds.
        private const val RECONNECT_MS = 65_000L
        private const val RECONNECT_EVERY_MS = 1_000L
        // How long everyone waits for a player who dropped out before playing on.
        private const val HELD_WAIT_MS = 5_000L
        private const val FACE_GRAB_MS = 40L
        private const val FACE_CHECK_EVERY = 4
        // Alone: the game's kept every minute, once it's run for a few seconds.
        private const val AUTO_STATE_EVERY_MS = 60_000L
        private const val AUTO_STATE_MIN_FRAMES = 300L
        // A watcher this close to the players is caught up.
        private const val WATCH_NEAR = 8
        private const val FIGHT_GONE_CHECKS = 12
        /**
         * Multiplayer: how far a phone may run on guesses of its partners'
         * inputs before waiting: a fifth of a second, longer than most Wi-Fi
         * hiccups (SmoothnessTest), so the game plays through them.
         */
        private const val MAX_ROLLBACK = 12
    }

    private lateinit var game: Game
    private lateinit var set: String
    private lateinit var prefs: Prefs
    private lateinit var retroView: GLRetroView
    private lateinit var hud: HudView
    private lateinit var root: FrameLayout
    private lateinit var pad: Pad
    private var roster: Roster? = null

    /** Fighters' faces taken from the game's screen (null: a game without fighters). */
    var faces: Faces? = null
        private set
    private var faceSpec: PortraitSpec? = null
    private var ramMap: DetectSpec? = null
    private lateinit var detectInfo: TextView

    private var frame = 0L

    // Multiplayer: the room, and this phone's buttons as a RetroPad mask.
    // (Read on the emulator's thread too, every frame: see onNetFrame.)
    @Volatile private var room: Room? = null
    @Volatile private var localMask = 0
    @Volatile private var padFrame = 0L
    @Volatile private var netStarted = false
    @Volatile private var lastNetFrame = -1
    @Volatile private var stalledFrames = 0
    @Volatile private var lastSumFrame = -1L
    @Volatile private var lastResyncAsk = 0L
    private var netStartRequested = false
    // Watching: the COIN or START that asked for a seat, pressed for real once
    // seated (so the coin drops); then pressed for this many more frames.
    @Volatile private var askedWith: Key? = null
    @Volatile private var tapKey: Key? = null
    @Volatile private var tapFrames = 0
    // Two players: the game waits while either menu is open.
    @Volatile private var localPaused = false
    @Volatile private var remotePaused = false
    // While the room seats everyone (someone joined or left, a rematch): the game waits.
    @Volatile private var seating = true

    // The room was lost (Wi-Fi went): the game waits while this phone finds it again.
    @Volatile private var netLost = false
    private var reconnecting: Job? = null
    // Wakes the reconnect loop early: the Wi-Fi is back.
    private val rejoinNow = Channel<Unit>(Channel.CONFLATED)
    private var lanLink: LanLink? = null

    // The address that says which Wi-Fi the room is on: the host's, or this host's own.
    private fun roomAddress(): String? {
        val r = room ?: return null
        return if (r.isOwner) lanAddress()?.hostAddress else r.members[r.owner]?.host?.takeIf { it.isNotEmpty() }
    }

    private fun tryRejoinNow() {
        rejoinNow.trySend(Unit)
    }

    // A player dropped out and their seat is held: the game waits for them (the host can free it).
    // Everyone waits a moment for them (most Wi-Fi blips are over by then, and
    // they slot straight back in), then plays on: their seat stays theirs,
    // their fighter stands still, and they're back in it the moment they are.
    private fun heldWaiting(): List<String> = room?.let { r -> r.held.values.filter { r.heldFor(it.id) < HELD_WAIT_MS }.map { it.name } }.orEmpty()
    private val waitingForHeld: Boolean get() = heldWaiting().isNotEmpty()
    private val heldCheck = Runnable { applyNetFreeze() }
    private var playedOnFor: Set<String> = emptySet()
    private lateinit var netStatus: TextView
    private var paused = false
    private val dpad = mutableMapOf(Key.UP to false, Key.DOWN to false, Key.LEFT to false, Key.RIGHT to false)

    var dialog by mutableStateOf<GameDialog?>(null)
        private set

    /** Layout editor state, shown in the bar along the top. */
    var editing by mutableStateOf(false)
        private set
    var editSelected by mutableStateOf<String?>(null)
        private set
    var editScale by mutableFloatStateOf(1f)
        private set
    private var editBackup: Map<String, HudView.Tweak> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = gameById(intent.getStringExtra(EXTRA_GAME).orEmpty())
        val s = intent.getStringExtra(EXTRA_SET)
        if (g == null || s == null) {
            finish()
            return
        }
        game = g
        set = s
        prefs = Prefs(this)
        prefs.lastGame = game.id
        roster = game.roster?.let(::loadRoster)
        if (game.layout == Layout.FIGHTER) {
            ramMap = detectSpec(game.roster)
            faceSpec = portraitSpec(game.roster)
            faces = Faces(this, game.id, roster)
        }
        val roms = RomStore(this)
        room = if (intent.getBooleanExtra(EXTRA_NETPLAY, false)) Netplay.room else null
        if (intent.getBooleanExtra(EXTRA_NETPLAY, false) && room == null) {
            finish()
            return
        }
        // Before the emulator starts: in a room, frames run from every phone's
        // inputs (rollback netcode, see LibretroDroid::setNetplay). Until the
        // first seating the game runs on its own (watching nobody).
        LibretroDroid.setNetplay(room != null, room?.localPlayer ?: 0, game.players, MAX_ROLLBACK, 0)
        if (room != null) {
            holdWifi()
            // Multiplayer stays on the room's Wi-Fi, whatever Android prefers (LanLink).
            lanLink = LanLink(this, target = ::roomAddress, onBack = { runOnUiThread { tryRejoinNow() } })
        }
        // The emulator is one object for the whole app: a game left frozen
        // (quit from the menu) mustn't start the next one frozen.
        LibretroDroid.setFrozen(false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The phone's volume buttons set the game's (media) volume, not the ringer's.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir, CORE).absolutePath
            // FBNeo finds neogeo.zip next to the game, in the same folder.
            gameFilePath = roms.file("$set.zip").absolutePath
            systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
            // Two-player games start from the host's state, saves included;
            // keep them apart from your own.
            savesDirectory = File(filesDir, if (room != null) "netplay-saves" else "saves").apply { mkdirs() }.absolutePath
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
            // The cabinet's settings (four players, say): the same on every phone.
            variables = game.coreOptions(set).map { (key, value) -> Variable(key, value) }.toTypedArray()
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        // Every frame, straight after it on the emulator's thread (never late
        // because the main thread was busy drawing): multiplayer's inputs, and
        // alone the macros and rapid fire, so a move's every step lands on its frame.
        retroView.onFrame = {
            if (room != null) {
                onNetFrame()
            } else {
                padFrame++
                pad.onFrame()
            }
        }

        pad = Pad(send = ::sendKey, clock = { padFrame })
        hud = HudView(this, this)
        hud.faceOf = { name -> faces?.bitmap(name) }

        detectInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(12, 6, 12, 6)
            visibility = View.GONE
        }
        netStatus = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(36, 20, 36, 20)
            visibility = if (room != null) View.VISIBLE else View.GONE
            text = room?.let { r -> if (r.isOwner) "Opening the room…" else "Joining ${r.nameOf(r.owner)}'s room…" }
        }
        val dialogs = ComposeView(this).apply {
            setContent {
                KofTheme {
                    CompositionLocalProvider(LocalFaces provides (faces?.byName ?: emptyMap())) { GameDialogs(this@GameActivity) }
                }
            }
        }
        root = FrameLayout(this)
        setContentView(
            root.apply {
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(detectInfo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                addView(netStatus, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
                // Full width so the layout editor's bar can centre itself; it
                // takes no space (and no touches) when nothing is shown.
                addView(dialogs, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            },
        )
        hud.onSelect = { id ->
            editSelected = id
            editScale = id?.let { hud.tweaks[it]?.scale } ?: 1f
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) applyScreen()
        }
        applyPrefs()

        // One tick per emulated frame for the screen (the macros and rapid
        // fire go on the emulator's thread: retroView.onFrame).
        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect {
                when (it) {
                    is GLRetroView.GLRetroEvents.FrameRendered -> {
                        frame++
                        if (frame == 1L && room == null) resumeWhereLeft()
                        hud.onFrame(frame)
                    }
                    // Once: the surface is made again if the GL context is lost.
                    is GLRetroView.GLRetroEvents.SurfaceCreated -> if (!netStartRequested) {
                        netStartRequested = true
                        room?.let(::startNetplay)
                    }
                }
            }
        }
        ramMap?.let(::startDetection)
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code -> show(GameDialog.Error(errorMessage(code))) }
        }
        onBackPressedDispatcher.addCallback(this) { if (editing) finishEditing(save = false) else show(GameDialog.Pause) }
    }

    override fun onResume() {
        super.onResume()
        // Back in this game (from another app, say): it's the one played last.
        prefs.lastGame = game.id
        // The emulator resumes itself with the activity; keep it paused if a
        // dialog is still open.
        if ((dialog != null && dialog !is GameDialog.Room) || editing) {
            paused = false
            window.decorView.post { pauseEmulation() }
        }
    }

    private fun errorMessage(code: Int) = when (code) {
        GLRetroView.ERROR_LOAD_LIBRARY -> "The FBNeo emulator core is missing from this build."
        GLRetroView.ERROR_LOAD_GAME ->
            "FBNeo couldn't load $set.zip. Check it's the right ROM set for FBNeo" +
                if (game.neoGeo) ", and that neogeo.zip is added." else "."
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "This phone's graphics aren't supported."
        else -> "Something went wrong running the game (error $code)."
    }

    // ----- Input -----

    // The Android key for each RetroPad button id (LibretroDroid maps them back).
    private fun keyCode(key: Key) = when (retroId(key)) {
        0 -> KeyEvent.KEYCODE_BUTTON_B
        1 -> KeyEvent.KEYCODE_BUTTON_Y
        2 -> KeyEvent.KEYCODE_BUTTON_SELECT
        3 -> KeyEvent.KEYCODE_BUTTON_START
        8 -> KeyEvent.KEYCODE_BUTTON_A
        9 -> KeyEvent.KEYCODE_BUTTON_X
        10 -> KeyEvent.KEYCODE_BUTTON_L1
        11 -> KeyEvent.KEYCODE_BUTTON_R1
        else -> error("not a button: $key")
    }

    // RetroPad button ids: the game's layout for A-F (Game.pad), then coin, start and the stick.
    private fun retroId(key: Key) = when (key) {
        Key.COIN -> 2 // SELECT
        // A cabinet without START: the fire button players join with.
        Key.START -> game.startWith?.let { game.pad.id(BUTTONS.indexOf(it)) } ?: 3
        Key.UP -> 4
        Key.DOWN -> 5
        Key.LEFT -> 6
        Key.RIGHT -> 7
        else -> game.pad.id(FACE_KEYS.indexOf(key))
    }

    private fun sendKey(key: Key, down: Boolean) {
        if (retroId(key) < 0) return // a button this game doesn't have
        room?.let { r ->
            if (r.watching) {
                // Watching: COIN or START asks for a free seat (on the main thread:
                // a macro can press it from the emulator's).
                if (down && netStarted && (key == Key.COIN || key == Key.START) && askedWith == null) runOnUiThread {
                    if (askedWith != null) return@runOnUiThread
                    // A free seat, or an idle player's: the host decides (first come).
                    askedWith = key
                    sendNet { it.requestSeat() }
                    announce("Asking for a seat…")
                    // No answer (the host was changing, say): ask again freely.
                    root.postDelayed({ if (askedWith == key && r.watching) askedWith = null }, 5_000)
                }
                return
            }
            val bit = 1 shl retroId(key)
            localMask = if (down) localMask or bit else localMask and bit.inv()
            return
        }
        if (key in dpad) {
            dpad[key] = down
            val x = (if (dpad[Key.RIGHT] == true) 1f else 0f) - (if (dpad[Key.LEFT] == true) 1f else 0f)
            val y = (if (dpad[Key.DOWN] == true) 1f else 0f) - (if (dpad[Key.UP] == true) 1f else 0f)
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
        } else {
            retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode(key))
        }
    }

    override fun onDir(dir: Int) = pad.setDir(dir)

    override fun onKey(key: Key, down: Boolean) = pad.press(key, down)

    override fun onPlay(cmd: String, move: Move?): Int {
        // The move itself, not a lookup by input: Benimaru's Raijin Ken and
        // its air version share 236+A.
        val air = move?.airOnly == true
        return pad.play(cmd, jumpFirst = air && onGround())
    }

    /** The fighter is on the floor (detection's grounded word is 0; KOF: their height). */
    private fun onGround(): Boolean {
        val spec = ramMap ?: return true
        // The fighter's block as detection last saw it (tag teams move it):
        // one small read, as this runs on the touch.
        val addr = (groundBase ?: (spec.player + spec.playerStride * localPlayer)) + spec.grounded
        val ram = readRam(spec, listOf((addr and 3.inv()) to 4)) ?: return true
        return ram.word(addr) == 0
    }

    /** Reads [windows] of the game's RAM (start, length: CPU addresses), or null if the core can't. */
    private fun readRam(spec: DetectSpec, windows: List<Pair<Int, Int>>): Ram? {
        val read = windows.map { (start, length) ->
            start to (LibretroDroid.readMemory(SYSTEM_RAM, start - spec.memoryBase, length) ?: return null)
        }
        return Ram(read, spec.swap)
    }

    override fun onMenu() = show(GameDialog.Pause)

    override fun onFlip() {
        pad.facingLeft = !pad.facingLeft
        updateHud()
    }

    override fun onSlot(index: Int) {
        val team = team()
        val c = roster?.character(team[index])
        if (c != null && index != prefs.active(game.id)) {
            prefs.setActive(game.id, index)
            updateHud()
            Toast.makeText(this, c.name, Toast.LENGTH_SHORT).show()
        } else {
            show(GameDialog.Roster(index))
        }
    }

    override fun onBlock(relDir: Int?) = pad.setHold(relDir)

    override fun onRun(on: Boolean) = if (on) pad.startRun() else pad.stopRun()

    override fun onRapid() {
        game.rapid?.let { pad.setRapid(it, !pad.isRapid(it)) }
        updateHud()
    }

    // ----- Two players -----

    val isNetplay: Boolean get() = room != null

    /** The room, for the pause menu: its key and who's in. */
    val roomInfo: String?
        get() = room?.let { r ->
            (if (r.key.isNotEmpty()) "Room key ${r.key} · " else "") +
                "${r.seats.size} of ${r.maxPlayers} playing" +
                if (r.watcherCount > 0) " · ${r.watcherCount} watching" else ""
        }

    /** How the room's connection is doing, for the pause menu's details. */
    val roomStatus: String? get() = room?.status()

    /** The connection's story (NetLog) and this phone, to send when multiplayer misbehaves. */
    fun shareConnectionReport() {
        val report = buildString {
            appendLine(if (room != null) "90s Kid Arcade connection report" else "90s Kid Arcade performance report")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, screen ${screenHz()} Hz")
            appendLine("Game: ${game.title} ($set)")
            appendLine(playbackStatus)
            room?.let { appendLine(it.status()) }
            appendLine()
            append(NetLog.dump())
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, if (room != null) "90s Kid Arcade connection report" else "90s Kid Arcade performance report")
            .putExtra(Intent.EXTRA_TEXT, report)
        runCatching { startActivity(Intent.createChooser(send, if (room != null) "Send connection report" else "Send report")) }
    }

    /**
     * How the game runs, for the pause menu and reports: its frames' rate and
     * how they're timed (with the screen, or by the clock), frames that ran
     * late and were made up, and gaps in the sound, since it started.
     */
    val playbackStatus: String
        get() {
            val s = LibretroDroid.getPlaybackStats()
            val rate = (s[3] / 10) / 100.0
            val ticks = (s[3] % 10).toInt()
            val pace = when {
                ticks == 1 -> "with the screen"
                ticks > 1 -> "with the screen, 1 in $ticks ticks"
                else -> "by the clock"
            }
            val overflow = if (s[2] > 0) ", ${s[2]} sound frames dropped" else ""
            return "%.2f frames a second, %s (%d Hz). Late frames made up: %d. Sound gaps: %d%s.".format(
                rate, pace, screenHz(), s[0], s[1], overflow,
            )
        }

    @Suppress("DEPRECATION")
    private fun screenHz(): Int = Math.round(windowManager.defaultDisplay.refreshRate)

    /** The room as the screens show it (pill, room sheet); null alone. */
    var roomUi by mutableStateOf<RoomUi?>(null)
        private set

    /** News over the game, gone by itself. */
    var announcement by mutableStateOf<Announcement?>(null)
        private set

    fun announce(text: String, big: Boolean = false) {
        announcement = Announcement(text, big)
    }

    fun clearAnnouncement(a: Announcement) {
        if (announcement == a) announcement = null
    }

    // Whether this phone watched at the last seating, to say when that changes.
    private var wasWatching: Boolean? = null

    private fun updateRoomUi() {
        val r = room ?: return
        lanLink?.refresh()
        val members = r.members.values
        val people = if (members.isEmpty()) {
            // The host before its first game: just itself, in seat 1.
            listOf(RoomPerson("You", if (r.isOwner) 0 else -1, you = true, host = r.isOwner))
        } else {
            members.map {
                RoomPerson(
                    if (it.id == r.localId) "You" else it.name,
                    it.seat,
                    you = it.id == r.localId,
                    host = it.id == r.owner,
                    away = it.away,
                    holdEnds = if (it.away) r.holdEnds(it.id) else 0L,
                )
            }
        }
        roomUi = RoomUi(r.key, r.maxPlayers, people, watching = members.isNotEmpty() && r.watching, canFree = r.isOwner)
    }

    /** Opens the room sheet (the game goes on: it's not the pause menu). */
    fun openRoom() {
        updateRoomUi()
        show(GameDialog.Room())
    }

    /** Sends the room's key through any app (messages, WhatsApp…). */
    fun shareRoomKey() {
        val key = roomKey ?: return
        val text = "Play with me on 90s Kid Arcade! Room key: $key\n" +
            "Open Multiplayer and type the key (same Wi-Fi or hotspot).\n" +
            "Get the app: https://taiyebnirjhar.github.io/90s-kid-arcade/"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, "Share the room key")) }
    }

    /** Watching: asks for a seat, as COIN or START does. */
    fun takeSeat() {
        val r = room ?: return
        if (!r.watching || askedWith != null) return
        askedWith = Key.COIN
        sendNet { it.requestSeat() }
        announce("Asking for a seat…")
        close()
    }

    /** The host lets go of a seat kept for a player that dropped out: a watcher can take it now. */
    fun freeHeldSeat(seat: Int) {
        sendNet { it.freeHeldSeat(seat) }
    }

    /** Playing: gives the seat up, to watch. */
    fun watchInstead() {
        sendNet { it.requestWatch() }
        close()
    }

    /** This phone watches the room's game rather than plays. */
    val isWatching: Boolean get() = room?.watching == true

    /** The room's key, to copy (pause menu); null if there's none. */
    val roomKey: String? get() = room?.key?.takeIf { it.isNotEmpty() }

    /** Copies the room's key, to send to friends. */
    fun copyRoomKey() {
        val key = roomKey ?: return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Room key", key))
        announce("Room key $key copied")
    }

    /** This phone's player: 0 alone, its seat in a room (-1 while watching). */
    private val localPlayer: Int get() = room?.localPlayer ?: 0

    // The room runs on its own threads; it seats this phone (from the owner's
    // game) whenever someone joins or leaves, and the game waits meanwhile.
    private fun startNetplay(room: Room) {
        // Who's in each seat, from the game itself (Game.seats). A game whose
        // byte only says "in the fight" is watched over time, so its seat goes
        // free once the game's own countdown has run (SeatStatus.outForMs).
        val seats = game.seats
        val seatWatch = seats?.let { SeatWatch(it) }
        fun readByte(offset: Int): Int? = LibretroDroid.readMemory(SYSTEM_RAM, offset, 1)?.firstOrNull()?.toInt()?.and(0xFF)
        if (seats != null && seats.outForMs > 0) lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && this@GameActivity.room === room) {
                delay(SEAT_SAMPLE_MS)
                val now = System.currentTimeMillis()
                for (seat in seats.offsets.indices) seats.isOut(seat, ::readByte)?.let { seatWatch?.sample(seat, it, now) }
            }
        }
        lifecycleScope.launch {
            if (room.isOwner) {
                // The state the room starts from, for rematches and newcomers.
                room.startState = withContext(Dispatchers.IO) { retroView.serializeState() }
                // The key to share; the game waits until it's closed.
                if (room.members.isEmpty()) {
                    updateRoomUi()
                    show(GameDialog.Room(first = true))
                }
            }
            room.start(object : RoomListener {
                override fun onRemote(player: Int, start: Int, masks: IntArray) {
                    var taken = true
                    masks.forEachIndexed { i, m -> taken = LibretroDroid.pushRemoteInput(player, start + i, m) && taken }
                    // A watcher's game too far behind to take more (it was in
                    // the background, say): the game again, from now.
                    if (!taken && room.watching) sendNet { it.requestResync() }
                }

                // The room puts everyone back in step from the host's game by
                // itself (a moment's pause, like someone joining).
                override fun onDesync(frame: Int) = Unit

                override fun onClosed(reason: String) = runOnUiThread { netEnded(reason) }

                override fun onLost(reason: String) = runOnUiThread { reconnect(room, reason) }

                override fun onPartnerPaused(player: Int, paused: Boolean) = runOnUiThread {
                    remotePaused = room.partnerPaused
                    applyNetFreeze()
                }

                override fun onNews(message: String) = runOnUiThread {
                    announce(message)
                    // "All seats are in use": asking again is allowed.
                    if (room.watching) askedWith = null
                    updateRoomUi()
                }

                override fun onRoster() = runOnUiThread { updateRoomUi() }

                // The game's own word: this seat's player never started, or
                // their game is over. Null where the game can't say.
                override fun seatFree(seat: Int): Boolean? {
                    val out = seats?.isOut(seat, ::readByte) ?: return null
                    return seatWatch?.sample(seat, out, System.currentTimeMillis())
                }

                // A saved game fits this game if it's a state of the same size:
                // another game's or another version's isn't.
                override fun canLoad(state: ByteArray): Boolean {
                    val now = LibretroDroid.getNetState()
                    val size = if (now.size > 4) now.size - 4 else runCatching { retroView.serializeState(useEmulationThread = false).size }.getOrDefault(-1)
                    return state.size == size
                }

                // A newcomer watches from the newest frame every input is in for.
                override fun snapshot(): Pair<Int, ByteArray>? {
                    val raw = LibretroDroid.getNetState()
                    if (raw.size <= 4) return null
                    val frame = java.nio.ByteBuffer.wrap(raw, 0, 4).int
                    return frame to raw.copyOfRange(4, raw.size)
                }

                // The owner's game, to seat everyone from: stop it first.
                override fun captureState(): ByteArray {
                    seating = true
                    LibretroDroid.setFrozen(true)
                    // Straight from here (see onSeating): the emulator's lock keeps it apart from a frame.
                    return retroView.serializeState(useEmulationThread = false)
                }

                override fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?, startFrame: Int) {
                    seating = true
                    LibretroDroid.setFrozen(true)
                    LibretroDroid.setNetplay(true, room.localPlayer, game.players, MAX_ROLLBACK, activeMask)
                    if (startFrame > 0) LibretroDroid.netStartAt(startFrame)
                    // Straight from here, not queued for the emulator's thread: that
                    // thread may be waiting on the room right now (onNetFrame), which
                    // waits for this. The emulator's own lock keeps it apart from a frame.
                    if (state != null) retroView.unserializeState(state, useEmulationThread = false)
                    runOnUiThread {
                        // In (again): whatever was lost is found.
                        netLost = false
                        lastSumFrame = -1
                        lastNetFrame = -1
                        remotePaused = room.partnerPaused
                        hud.releaseAll()
                        pad.releaseAll()
                        localMask = 0
                        // Seated from watching: the COIN or START that asked goes in now.
                        tapKey = askedWith?.takeIf { !room.watching }
                        tapFrames = if (tapKey != null) 6 else 0
                        // Seated, or a seating that didn't seat us: ask again freely.
                        askedWith = null
                        // A player now, with the menu open: everyone waits for it.
                        when {
                            room.watching -> localPaused = false
                            dialog != null && !localPaused -> pauseEmulation()
                            // A seating clears everyone's pauses: ours still holds.
                            localPaused -> sendNet { it.sendPause(true) }
                        }
                        netStarted = true
                        seating = false
                        catchingUp = room.watching
                        applyNetFreeze()
                        updateRoomUi()
                        // Said big when it's about this phone.
                        val before = wasWatching
                        if (before == true && !room.watching) announce("You're player ${room.localPlayer + 1}!", big = true)
                        if (before == false && room.watching) announce("You're watching now")
                        if (before == null && room.watching) announce("You're watching. Press COIN or START to play")
                        // Came back to a seat that was kept for this phone.
                        if (before == null && !room.watching && !room.isOwner) announce("You're player ${room.localPlayer + 1}!", big = true)
                        wasWatching = room.watching
                    }
                }
            })
        }
    }

    // Watching, just in or put back in step: the game skips ahead to the
    // players' moment unseen (LibretroDroid), behind "Joining…" until it's there.
    @Volatile private var catchingUp = false
    // Frames seen by onNetFrame (the emulator's thread), and whether "Waiting for…" is up.
    @Volatile private var netFrames = 0L
    @Volatile private var showingWait = false

    // Each displayed frame: read our buttons for the frames the game will
    // need next, one macro step per frame, and send them.
    // On the emulator's thread, after every frame.
    private fun onNetFrame() {
        netFrames++
        val room = room ?: return
        if (!netStarted || seating || localPaused || remotePaused || netLost || waitingForHeld) return
        val sample = room.sample(
            emulated = { LibretroDroid.getNetFrame() },
            input = {
                padFrame++
                pad.onFrame()
                val tap = tapKey
                if (tap != null && tapFrames > 0) {
                    tapFrames--
                    localMask or (1 shl retroId(tap))
                } else {
                    localMask
                }
            },
            push = { start, masks -> masks.forEachIndexed { i, m -> LibretroDroid.pushLocalInput(start + i, m) } },
        )
        val emulated = sample.emulated
        if (catchingUp) {
            val upTo = room.watchedUpTo
            if (!room.watching || upTo == Int.MAX_VALUE || upTo.toLong() - emulated <= WATCH_NEAR) {
                catchingUp = false
                runOnUiThread { applyNetFreeze() }
            } else {
                runOnUiThread {
                    if (catchingUp && netStatus.visibility != View.VISIBLE) {
                        netStatus.text = "Joining the game…"
                        netStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
        // Time sync: when this phone's game has got ahead of a partner's, wait
        // so it catches up (Room.timeSync: only for a real lead, as a wait shows).
        // Every phone then guesses as little as possible, and none stalls at the rollback limit.
        room.updateAdvantage(emulated)
        val wait = room.timeSync()
        if (wait > 0) {
            waits++
            LibretroDroid.netWait(wait)
        }
        val sum = LibretroDroid.getNetSum()
        if (sum[0] >= 0 && sum[0] != lastSumFrame) {
            lastSumFrame = sum[0]
            room.reportSum(sum[0].toInt(), sum[1])
        }
        // Say so when a partner's inputs stop arriving for a moment.
        if (emulated == lastNetFrame) stalls++
        logQuality(room)
        stalledFrames = if (emulated == lastNetFrame) stalledFrames + 1 else 0
        lastNetFrame = emulated
        val waiting = stalledFrames > 30
        if (waiting != showingWait) {
            showingWait = waiting
            runOnUiThread {
                if (!catchingUp && !netLost && waiting != (netStatus.visibility == View.VISIBLE)) {
                    netStatus.text = if (room.watching) "Waiting for the players…" else "Waiting for ${room.peerName}…"
                    netStatus.visibility = if (waiting) View.VISIBLE else View.GONE
                }
            }
        }
        // Stuck for 3 seconds with nobody paused: back in step from the host's
        // game (a watcher alone; players with a moment's pause). A watcher
        // that fell far behind the players gets the game again, too.
        val behind = room.watching && room.watchedUpTo.toLong() - emulated > 600
        if ((stalledFrames > 180 || behind) && netFrames - lastResyncAsk > 300) {
            lastResyncAsk = netFrames
            runOnUiThread { netStatus.text = "Reconnecting…" }
            sendNet { it.requestResync() }
        }
    }

    // How smooth the last 10 seconds were, in the connection log: frames
    // replayed after a wrong guess (rollbacks), frames the game stood still
    // for a late input (stalls), waits to let a partner catch up, the input
    // delay. Only when something's off, so the log stays readable.
    @Volatile private var stalls = 0
    @Volatile private var waits = 0
    @Volatile private var qualityFrom = 0L
    @Volatile private var lastStats = LongArray(3)
    @Volatile private var lastPlayback = LongArray(4)

    private fun logQuality(room: Room) {
        if (netFrames - qualityFrom < 600) return
        qualityFrom = netFrames
        val stats = LibretroDroid.getNetStats()
        val replayed = stats[0] - lastStats[0]
        val rollbacks = stats[1] - lastStats[1]
        val slowestMs = stats[2] / 1000
        lastStats = stats
        val playback = LibretroDroid.getPlaybackStats()
        val madeUp = playback[0] - lastPlayback[0]
        val soundGaps = playback[1] - lastPlayback[1]
        lastPlayback = playback
        val hiccups = room.takeHiccups()
        if (stalls > 0 || waits > 1 || rollbacks > 30 || hiccups.isNotEmpty() || slowestMs > 30 || madeUp > 0 || soundGaps > 0) {
            val heard = hiccups.entries.joinToString("") { (who, ms) -> ", nothing from $who for $ms ms" }
            NetLog.add(
                "Last 10 s: $rollbacks rollbacks ($replayed frames replayed), $stalls frames stalled, " +
                    "$waits waits, delay ${room.delay}, slowest frame $slowestMs ms, " +
                    "$madeUp late frames made up, $soundGaps sound gaps$heard",
            )
        }
        stalls = 0
        waits = 0
    }

    // The game runs only while no player has the menu open, and not while
    // seating, finding the room again or waiting for a player who dropped out.
    private fun applyNetFreeze() {
        val waiting = heldWaiting()
        LibretroDroid.setFrozen(seating || localPaused || remotePaused || netLost || waiting.isNotEmpty())
        // Check again when the wait's over.
        root.removeCallbacks(heldCheck)
        if (waiting.isNotEmpty()) root.postDelayed(heldCheck, 500)
        val held = room?.held?.values?.map { it.name }?.toSet().orEmpty()
        val playingOn = held - waiting.toSet()
        if (playingOn.isNotEmpty() && playingOn != playedOnFor) {
            announce("Playing on: ${playingOn.joinToString(" & ")}'s seat is kept for when they're back")
        }
        playedOnFor = playingOn
        when {
            netLost -> {
                netStatus.text = "Connection lost. Reconnecting…"
                netStatus.visibility = View.VISIBLE
            }
            waiting.isNotEmpty() && !localPaused -> {
                netStatus.text = "Waiting for ${waiting.joinToString(" & ")} to reconnect…"
                netStatus.visibility = View.VISIBLE
            }
            remotePaused && !localPaused -> {
                netStatus.text = "${room?.pausedName ?: "Someone"} paused the game"
                netStatus.visibility = View.VISIBLE
            }
            // "Joining…" holds until the game's caught up (onNetFrame).
            catchingUp -> Unit
            netStarted -> netStatus.visibility = View.GONE
        }
        stalledFrames = 0
    }

    /**
     * The room was lost ([RoomListener.onLost]: this phone's Wi-Fi went, or
     * the host's with nobody to take over): the game waits, and this phone
     * looks for the room again, its host or whoever took it over, every few
     * seconds while its seat is held. Back in, the room seats it again.
     */
    private fun reconnect(lost: Room, reason: String) {
        if (isFinishing || room !== lost || reconnecting?.isActive == true) return
        netLost = true
        applyNetFreeze()
        announce(reason)
        reconnecting = lifecycleScope.launch {
            // The host's beacon says where it is now, should its address have changed.
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val multicast = runCatching { wifi?.createMulticastLock("90s-kid-arcade:rejoin")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            val beacons = runCatching {
                BeaconListener { beacon, from ->
                    lost.heard(beacon, from)
                    if (beacon.owner >= 0) tryRejoinNow()
                }
            }.getOrNull()
            try {
                val until = android.os.SystemClock.elapsedRealtime() + RECONNECT_MS
                while (isActive && android.os.SystemClock.elapsedRealtime() < until) {
                    val back = withContext(Dispatchers.IO) { runCatching { lost.rejoin() }.getOrNull() }
                    if (back != null) {
                        room = back
                        Netplay.room = back
                        netStarted = false
                        wasWatching = null
                        netStatus.text = "Back in the room…"
                        startNetplay(back)
                        updateRoomUi()
                        return@launch
                    }
                    // Again in a moment, or at once when the Wi-Fi comes back.
                    withTimeoutOrNull(RECONNECT_EVERY_MS) { rejoinNow.receive() }
                }
                NetLog.add("Gave up getting back into the room")
                netEnded("Couldn't get back into the room. Check the Wi-Fi, then join again with the room key.")
            } finally {
                beacons?.close()
                runCatching { multicast?.release() }
            }
        }
    }

    private fun sendNet(action: (Room) -> Unit) {
        val r = room ?: return
        lifecycleScope.launch(Dispatchers.IO) { action(r) }
    }

    /**
     * Pause menu: restart the game on every phone, from the state the room
     * started from. Rarely needed: KOF's own challenger mode and continues
     * already give a rematch; this is for a game stuck somewhere.
     */
    fun restartGame() {
        if (room == null) return startOver()
        sendNet { it.requestRematch() }
        close()
    }

    private fun netEnded(reason: String) {
        if (isFinishing) return
        room?.close()
        netStatus.visibility = View.GONE
        show(GameDialog.Error(reason, title = "Multiplayer game ended"))
    }

    // Two players: Wi-Fi normally naps between packets to save battery, so an
    // input can sit for tens of milliseconds and the partner's game has to
    // guess and correct. This keeps the radio awake while the game is on screen.
    private var wifiLock: WifiManager.WifiLock? = null

    private fun holdWifi() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching {
            wifi.createWifiLock(mode, "90s-kid-arcade:two-players").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        // The room first: its threads may still ask the emulator for its
        // state, which the emulator can't answer once it's gone.
        room?.let {
            it.close()
            if (Netplay.room === it) Netplay.room = null
            LibretroDroid.setNetplay(false, 0, 2, 0, 0)
        }
        super.onDestroy()
        wifiLock?.let { if (it.isHeld) it.release() }
        lanLink?.stop()
    }

    override fun onPause() {
        // Leaving the app mid-game: the others wait, as for the menu.
        if (room != null && !isWatching && (dialog == null || dialog is GameDialog.Room) && !editing && !isFinishing) show(GameDialog.Pause)
        // Alone: the game is kept as it is, to go on from next time (once it's
        // run a while: a game that never started has nothing to keep).
        if (room == null && frame - runFrom >= AUTO_STATE_MIN_FRAMES) {
            // Before the emulator's own pause (the GL thread may already be
            // stopped, so straight from here: the core takes its lock).
            runCatching { retroView.serializeState(useEmulationThread = false) }.getOrNull()?.let(::keepAutoState)
        }
        super.onPause()
    }

    // ----- Fighter detection -----

    val canDetect: Boolean get() = ramMap != null

    val hasRapidFire: Boolean get() = game.rapid != null

    /**
     * Reads the game's RAM a few times a second, off the main thread (a read
     * waits for the emulator to finish its frame), while the game is on screen.
     */
    private fun startDetection(spec: DetectSpec) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(DETECT_MS)
                    if (paused || frame == 0L) {
                        detectInfo.visibility = View.GONE
                        continue
                    }
                    // Watching: no fighter of our own to follow.
                    val me = localPlayer
                    if (me < 0) continue
                    val ram = withContext(Dispatchers.Default) { readRam(spec, spec.windows) } ?: continue
                    val found = runCatching { fighterBase(spec, ram, me) to detect(spec, ram, me) }.getOrNull() ?: continue
                    groundBase = found.first
                    fightMarks?.let { seeFight(found.second, it) }
                    onDetected(found.second)
                    takeFaces(spec)
                }
            }
        }
    }

    // Double Dragon: a fight is told by its HUD on the picture (fightHud), as
    // its RAM reads the same on the select screen. Once seen it holds through
    // a moment without it (a super's flash greys the HUD), and ends on the
    // select screen, after a few seconds without it, or with the round.
    private val fightMarks by lazy { fightHud(game.roster) }
    private var fightSeen = false
    private var fightMissed = 0

    private fun seeFight(d: Detection, hud: List<com.kofarcade.core.Anchor>) {
        if (!d.inRound) {
            fightSeen = false
            return
        }
        val raw = LibretroDroid.grabFrame() ?: return
        val picture = Picture(raw[0], raw[1], raw.copyOfRange(2, raw.size))
        when {
            shows(picture, hud, raw[0]) -> {
                fightSeen = true
                fightMissed = 0
            }
            faceSpec?.showing(picture, 0) == true -> fightSeen = false
            ++fightMissed >= FIGHT_GONE_CHECKS -> fightSeen = false
        }
    }

    // Which face to keep for each fighter; it puts a wrongly kept one right.
    private val faceKeeper by lazy { faces?.let { f -> FaceKeeper({ f.pixels(it) }, { f.allPixels() }) } }
    private var faceTick = 0

    /**
     * Takes the faces of the fighters on screen from the game's own picture:
     * only while its HUD shows them (Portraits.kt), and only a face seen the
     * same a few times, never another fighter's (FaceKeeper). Faces already
     * kept are looked at again, less often, for a while each session, so one
     * taken at the wrong moment (a fighter changing over) puts itself right.
     */
    private suspend fun takeFaces(spec: DetectSpec) {
        val faceSpec = faceSpec ?: return
        val faces = faces ?: return
        val keeper = faceKeeper ?: return
        fun wanted(ram: Ram): List<Pair<Int, String>> = (0..1).mapNotNull { side ->
            val d = runCatching { detect(spec, ram, side) }.getOrNull() ?: return@mapNotNull null
            val id = if (faceSpec.selectScreen) d.team.firstOrNull() else d.active.takeIf { d.inRound }
            id?.takeIf { faces.knows(it) && keeper.wants(it) }?.let { side to it }
        }
        val before = withContext(Dispatchers.Default) { readRam(spec, spec.windows) } ?: return
        val looking = wanted(before)
        if (looking.isEmpty()) return
        // Only checking faces already kept: now and then is enough.
        if (looking.all { (_, id) -> faces.pixels(id) != null } && faceTick++ % FACE_CHECK_EVERY != 0) return
        // The first grab asks for the next frame; the second, a moment later, gets it.
        LibretroDroid.grabFrame()
        delay(FACE_GRAB_MS)
        val raw = LibretroDroid.grabFrame() ?: return
        val after = withContext(Dispatchers.Default) { readRam(spec, spec.windows) } ?: return
        // Who's on screen must be the same either side of the picture.
        val now = wanted(after).takeIf { it == looking } ?: return
        val picture = Picture(raw[0], raw[1], raw.copyOfRange(2, raw.size))
        for ((side, id) in now) {
            if (!faceSpec.showing(picture, side)) continue
            keeper.seen(id, faceSpec.crop(picture, side))?.let {
                faces.save(id, it, faceSpec.w, faceSpec.h)
                hud.invalidate()
            }
        }
    }

    private fun onDetected(d: Detection) {
        // A fighter shows (name, face, moves) only while one is really
        // playing: on the select screen the picks are still changing, and the
        // team saved last time may not be this one.
        val live = d.inRound && d.active != null && (fightMarks == null || fightSeen)
        var changed = live != fighterLive
        fighterLive = live
        if (d.inRound) {
            // Only in a round: on the select screen the picks are still changing.
            if (d.teamKnown && d.team.size == game.team && d.team != team()) {
                prefs.setTeam(game.id, d.team)
                changed = true
            }
            val slot = d.activeSlot
            if (slot != null && slot != prefs.active(game.id)) {
                prefs.setActive(game.id, slot)
                changed = true
            }
            if (d.facingLeft != pad.facingLeft) {
                pad.facingLeft = d.facingLeft
                changed = true
            }
            if (d.superArt != null && d.superArt != superArt) {
                superArt = d.superArt
                changed = true
            }
        }
        if (changed) updateHud()
        detectInfo.visibility = if (prefs.detectInfo) View.VISIBLE else View.GONE
        if (prefs.detectInfo) {
            fun hex(v: Int) = "%02X".format(v)
            fun name(id: String?) = roster?.character(id)?.name ?: "?"
            detectInfo.text = buildString {
                append(if (d.inRound) "ROUND" else "no round")
                append("   team ")
                append(d.teamRaw.zip(d.team).joinToString("  ") { (raw, id) -> "${hex(raw)} ${name(id)}" })
                append("\non screen ${hex(d.activeRaw)} ${d.active?.let(::name) ?: "(not in team)"}")
                append("   facing ${if (d.facingLeft) "←" else "→"}   x ${d.x} vs ${d.otherX}")
                d.superArt?.let { append("   Super Art $it") }
            }
        }
    }

    // ----- Team and moves -----

    fun team(): List<String?> = prefs.team(game.id, game.team)

    val rosterOrNull: Roster? get() = roster

    val teamSize: Int get() = game.team

    /** A fighter of ours is in a round now (detection saw it): until then none is shown. */
    @Volatile private var fighterLive = false

    fun activeCharacter(): Character? {
        if (game.layout != Layout.FIGHTER) return null
        if (canDetect && !fighterLive) return null
        val team = team()
        if (team.isEmpty()) return null
        return roster?.character(team[prefs.active(game.id).coerceIn(0, team.size - 1)])
    }

    private fun movesKey(c: Character) = "${game.roster}/${c.id}"

    /** Street Fighter III: the Super Art picked this fight (detected), or null to show all three. */
    private var superArt: Int? = null

    /** The block of our fighter on screen, as detection last read it. */
    @Volatile private var groundBase: Int? = null

    fun movesFor(c: Character): List<Move> = (prefs.customMoves(movesKey(c)) ?: c.moves)
        .filter { superArt == null || it.superArt == null || it.superArt == superArt }
        .take(MAX_MOVES)

    fun defaultMoves(c: Character): List<Move> = c.moves
        .filter { superArt == null || it.superArt == null || it.superArt == superArt }
        .take(MAX_MOVES)

    /** The editor shows the moves on the ring; the other Super Arts' stay in the list. */
    fun saveMoves(c: Character, moves: List<Move>) {
        val hidden = (prefs.customMoves(movesKey(c)) ?: c.moves)
            .filter { superArt != null && it.superArt != null && it.superArt != superArt }
        prefs.setCustomMoves(movesKey(c), moves + hidden)
        updateHud()
    }

    fun pickCharacter(slot: Int, id: String) {
        val team = team().toMutableList()
        team[slot] = id
        prefs.setTeam(game.id, team)
        prefs.setActive(game.id, slot)
        updateHud()
    }

    fun pickTeam(ids: List<String>) {
        prefs.setTeam(game.id, List(game.team) { ids.getOrNull(it) })
        prefs.setActive(game.id, 0)
        updateHud()
    }

    private fun subLabel(cmd: String): String {
        val parsed = parseCommand(cmd)
        return if (parsed.parts.isEmpty()) parsed.buttons.toList().joinToString("+") else toArrows(cmd)
    }

    private fun updateHud() {
        val c = activeCharacter()
        val moves = if (game.layout == Layout.FIGHTER) c?.let(::movesFor).orEmpty() else game.moves
        val system = buildList {
            game.power?.let { add(HudView.SysButton(it.name, it.cmd, it.cmd, power = true)) }
            game.system.forEach { add(HudView.SysButton(it.name, subLabel(it.cmd), it.cmd)) }
            if (game.run) add(HudView.SysButton("Run", "hold", null, hold = true))
        }
        val active = prefs.active(game.id)
        // With detection on, the game says who's fighting and which way they
        // face: no team slots or FACING button to tap.
        val detecting = canDetect
        val slots = if (game.layout == Layout.FIGHTER && roster != null && !detecting) {
            team().mapIndexed { i, id -> HudView.Slot(roster?.character(id)?.name, i == active) }
        } else {
            emptyList()
        }
        hud.state = HudView.State(
            layout = game.layout,
            faceButtons = game.faceButtons,
            labels = game.labels,
            moves = moves,
            system = system,
            slots = slots,
            facingLeft = pad.facingLeft,
            size = look.size,
            leftHanded = look.leftHanded,
            labelMode = look.labelMode,
            stick = look.stick,
            haptics = look.haptics,
            block = game.block,
            run = game.run,
            facingButton = !detecting,
        )
    }

    // The game renders into a 4:3 surface; the view it sits in is as wide as
    // the chosen screen mode, and the display stretches the surface to fill it.
    private fun applyScreen() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val aspect = look.screen.aspect
        val viewW = if (aspect == null) w else minOf(w, (h * aspect).roundToInt())
        val lp = retroView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != viewW) {
            lp.width = viewW
            retroView.layoutParams = lp
        }
        retroView.holder.setFixedSize((h * 4f / 3f).roundToInt(), h)
    }

    // Layouts are saved per kind of game and per hand.
    // Six-button games have their own: a four-button layout's moves don't fit them.
    private fun layoutKey() =
        "${game.layout.name}${if (game.pad == PadMap.CAPCOM_6) "6" else ""}.${if (look.leftHanded) "L" else "R"}"

    /** What's on screen now: the saved settings, or a preview from Controls. */
    private var look: Look = Look(ScreenMode.WIDE, StickMode.FLOATING, 1f, 0.46f, LabelMode.SUPERS, true, false, 2, 4)

    /** Applies the saved settings. */
    fun applyPrefs() = applyLook(prefs.look())

    /** Shows [preview] on the game without saving it (Controls, as you change things). */
    fun preview(preview: Look) = applyLook(preview)

    private fun applyLook(l: Look) {
        look = l
        hud.tweaks = prefs.tweaks(layoutKey())
        applyScreen()
        pad.timing = Timing(step = l.stepFrames, press = l.pressFrames)
        game.rapid?.let { pad.setRapid(it, l.rapidFire) }
        hud.opacity = l.opacity
        applySound(l.sound, l.volume)
        updateHud()
    }

    /** Sound on, for the pause menu's quick switch. */
    var soundOn by mutableStateOf(true)
        private set

    // Squared, so each step up sounds like the same step (ears hear loudness that way).
    private fun applySound(on: Boolean, volume: Float) {
        soundOn = on
        LibretroDroid.setVolume(if (on) volume * volume else 0f)
    }

    /** The pause menu's switch: sound off, or back on at its volume. Saved. */
    fun toggleSound() {
        prefs.soundOn = !prefs.soundOn
        applySound(prefs.soundOn, prefs.volume)
    }

    val settings: Prefs get() = prefs

    val gameTitle: String get() = game.fullTitle

    // ----- Dialogs, pause, save states -----

    fun show(d: GameDialog) {
        dialog = d
        // The room sheet sits over the game without stopping it (for anyone).
        if (d !is GameDialog.Room) pauseEmulation()
    }

    /** Closes the open dialog and resumes, then runs [after] once the game is running. */
    // ----- Layout editor -----

    fun startEditing() {
        dialog = null
        editBackup = hud.tweaks
        editing = true
        hud.editing = true
    }

    fun changeEditScale(v: Float) {
        val id = editSelected ?: return
        editScale = v
        hud.setScale(id, v)
    }

    fun resetLayout() {
        hud.tweaks = emptyMap()
        editScale = 1f
    }

    fun finishEditing(save: Boolean) {
        if (save) prefs.setTweaks(layoutKey(), hud.tweaks) else hud.tweaks = editBackup
        editing = false
        editSelected = null
        hud.editing = false
        resumeEmulation()
    }

    fun close(after: (suspend () -> Unit)? = null) {
        dialog = null
        resumeEmulation()
        if (after != null) {
            lifecycleScope.launch {
                delay(120)
                after()
            }
        }
    }

    private fun pauseEmulation() {
        room?.let { r ->
            // Everyone waits while a player's menu is open (a watcher's stops nobody).
            hud.releaseAll()
            pad.releaseAll()
            localMask = 0
            if (!localPaused && !r.watching) {
                localPaused = true
                sendNet { it.sendPause(true) }
                applyNetFreeze()
            }
            return
        }
        if (paused) return
        paused = true
        hud.releaseAll()
        pad.releaseAll()
        // Frozen rather than stopped, so the picture still redraws: Controls
        // shows screen-size changes live while the game waits.
        LibretroDroid.pause()
        LibretroDroid.setFrozen(true)
    }

    private fun resumeEmulation() {
        if (room != null) {
            if (localPaused) {
                localPaused = false
                sendNet { it.sendPause(false) }
                applyNetFreeze()
            }
            return
        }
        if (!paused) return
        paused = false
        LibretroDroid.setFrozen(false)
        LibretroDroid.resume()
    }

    private val stateFile get() = File(File(filesDir, "states").apply { mkdirs() }, "$set.state")

    // Alone, the game goes on where you left it: kept as you leave it (and
    // every minute, in case the app is stopped), loaded as it starts.
    private val autoStateFile get() = File(File(filesDir, "states").apply { mkdirs() }, "$set.auto.state")

    private val autoStateLock = Any()

    // Off the main thread, whole or not at all.
    private fun keepAutoState(bytes: ByteArray) {
        if (bytes.isEmpty() || frame - runFrom < AUTO_STATE_MIN_FRAMES || startingOver) return
        val file = autoStateFile
        Thread {
            synchronized(autoStateLock) {
                runCatching {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                }
            }
        }.start()
    }

    private fun resumeWhereLeft() {
        val file = autoStateFile.takeIf { it.exists() }
        lifecycleScope.launch {
            if (file != null) {
                val ok = withContext(Dispatchers.IO) { runCatching { retroView.unserializeState(file.readBytes()) }.getOrDefault(false) }
                if (ok) announce("Continuing where you left off")
            }
            while (isActive) {
                delay(AUTO_STATE_EVERY_MS)
                // Only while on screen: the emulation thread is stopped otherwise.
                if (paused || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                withContext(Dispatchers.IO) { runCatching { retroView.serializeState() }.getOrNull() }?.let(::keepAutoState)
            }
        }
    }

    // Set while starting over, so a save racing it doesn't bring the old game back.
    @Volatile private var startingOver = false

    // The frame this run of the game began at (it starts over from the menu).
    private var runFrom = 0L

    /** Alone: the game from the start, the kept one dropped. */
    fun startOver() = close {
        startingOver = true
        withContext(Dispatchers.IO) {
            autoStateFile.delete()
            retroView.reset()
        }
        runFrom = frame
        startingOver = false
        announce("Started over")
    }

    // Save states run on the emulation thread, so they're done with the game running.
    fun saveState() = close {
        val bytes = withContext(Dispatchers.IO) {
            // In a room: the game at the newest frame every input is in for, not a guess.
            room?.let { LibretroDroid.getNetState().takeIf { raw -> raw.size > 4 }?.let { raw -> raw.copyOfRange(4, raw.size) } } ?: retroView.serializeState()
        }
        withContext(Dispatchers.IO) { stateFile.writeBytes(bytes) }
        Toast.makeText(this, "State saved", Toast.LENGTH_SHORT).show()
    }

    fun loadState() {
        if (room != null) {
            if (!stateFile.exists()) {
                close()
                Toast.makeText(this, "No saved state yet: save one first", Toast.LENGTH_SHORT).show()
            } else {
                show(GameDialog.LoadForEveryone)
            }
            return
        }
        loadAlone()
    }

    /** Multiplayer: everyone carries on from this phone's saved state, once the host has checked it. */
    fun loadForEveryone() = close {
        val r = room ?: return@close
        val bytes = withContext(Dispatchers.IO) { runCatching { stateFile.readBytes() }.getOrNull() }
        val asked = bytes != null && withContext(Dispatchers.IO) { r.loadForEveryone(bytes) }
        if (!asked) Toast.makeText(this, "Couldn't reach the host: try again in a moment", Toast.LENGTH_SHORT).show()
    }

    private fun loadAlone() = close {
        val file = stateFile
        if (!file.exists()) {
            Toast.makeText(this, "No saved state yet", Toast.LENGTH_SHORT).show()
            return@close
        }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val ok = withContext(Dispatchers.IO) { retroView.unserializeState(bytes) }
        Toast.makeText(this, if (ok) "State loaded" else "Couldn't load the state", Toast.LENGTH_SHORT).show()
    }

    /** Fires a move from the editor so you can check it in the game. */
    fun testMove(cmd: String) = close { pad.play(cmd) }
}
