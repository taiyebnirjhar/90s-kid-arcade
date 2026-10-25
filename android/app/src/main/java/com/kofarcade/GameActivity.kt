package com.kofarcade

import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
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
import com.kofarcade.core.Character
import com.kofarcade.core.DetectSpec
import com.kofarcade.core.Detection
import com.kofarcade.core.FACE_KEYS
import com.kofarcade.core.Game
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.Pad
import com.kofarcade.core.PadMap
import com.kofarcade.core.Ram
import com.kofarcade.core.Roster
import com.kofarcade.core.Timing
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.RoomListener
import com.kofarcade.core.detect
import com.kofarcade.core.detectSpec
import com.kofarcade.core.fighterBase
import com.kofarcade.core.gameById
import com.kofarcade.core.loadRoster
import com.kofarcade.core.parseCommand
import com.kofarcade.core.toArrows
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Dialogs that can sit over the game. The game pauses while one is open. */
sealed interface GameDialog {
    data object Pause : GameDialog
    data class Roster(val slot: Int) : GameDialog
    data class Editor(val character: Character) : GameDialog
    data object Controls : GameDialog
    data class Error(val message: String, val title: String = "Can't start the game") : GameDialog
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
)

fun Prefs.look() = Look(screen, stick, size, opacity, labelMode, haptics, leftHanded, stepFrames, pressFrames, rapidFire)

/** Runs one game: FBNeo through LibretroDroid, with our touch controls on top. */
class GameActivity : ComponentActivity(), HudView.Listener {
    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_SET = "set"
        /** Multiplayer: play in [Netplay.room] instead of alone. */
        const val EXTRA_NETPLAY = "netplay"
        private const val MAX_MOVES = 6
        private const val CORE = "libfbneo_libretro_android.so"
        private const val SYSTEM_RAM = 2 // RETRO_MEMORY_SYSTEM_RAM: the Neo Geo's 68000 RAM
        private const val DETECT_MS = 250L
        /** Two players: how far a phone may run on guesses of its partner's input before waiting. */
        private const val MAX_ROLLBACK = 8
    }

    private lateinit var game: Game
    private lateinit var set: String
    private lateinit var prefs: Prefs
    private lateinit var retroView: GLRetroView
    private lateinit var hud: HudView
    private lateinit var root: FrameLayout
    private lateinit var pad: Pad
    private var roster: Roster? = null
    private var ramMap: DetectSpec? = null
    private lateinit var detectInfo: TextView

    private var frame = 0L

    // Multiplayer: the room, and this phone's buttons as a RetroPad mask.
    private var room: Room? = null
    private var localMask = 0
    private var padFrame = 0L
    private var netStarted = false
    private var lastNetFrame = -1
    private var stalledFrames = 0
    private var lastSumFrame = -1L
    private var lastWait = 0L
    private var lastResyncAsk = 0L
    // Watching: the COIN or START that asked for a seat, pressed for real once
    // seated (so the coin drops); then pressed for this many more frames.
    private var askedWith: Key? = null
    private var tapKey: Key? = null
    private var tapFrames = 0
    // Two players: the game waits while either menu is open.
    private var localPaused = false
    private var remotePaused = false
    // While the room seats everyone (someone joined or left, a rematch): the game waits.
    @Volatile private var seating = true
    private lateinit var netStatus: TextView
    private lateinit var roomBanner: TextView
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
        if (game.layout == Layout.FIGHTER) ramMap = detectSpec(game.roster)
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
        if (room != null) holdWifi()
        // The emulator is one object for the whole app: a game left frozen
        // (quit from the menu) mustn't start the next one frozen.
        LibretroDroid.setFrozen(false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        pad = Pad(send = ::sendKey, clock = { padFrame })
        hud = HudView(this, this)

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
        roomBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(28, 10, 28, 10)
            visibility = View.GONE
        }
        val dialogs = ComposeView(this).apply { setContent { KofTheme { GameDialogs(this@GameActivity) } } }
        root = FrameLayout(this)
        setContentView(
            root.apply {
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(detectInfo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                addView(netStatus, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
                addView(
                    roomBanner,
                    FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    },
                )
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

        // One tick per emulated frame drives the macros and rapid fire.
        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect {
                when (it) {
                    is GLRetroView.GLRetroEvents.FrameRendered -> {
                        frame++
                        if (room != null) {
                            onNetFrame()
                        } else {
                            padFrame++
                            pad.onFrame()
                        }
                        hud.onFrame(frame)
                    }
                    is GLRetroView.GLRetroEvents.SurfaceCreated -> room?.let(::startNetplay)
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
        // The emulator resumes itself with the activity; keep it paused if a
        // dialog is still open.
        if (dialog != null || editing) {
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
        Key.START -> 3
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
                // Watching: COIN or START asks for a free seat.
                if (down && (key == Key.COIN || key == Key.START) && askedWith == null) {
                    if (r.hasFreeSeat) {
                        askedWith = key
                        sendNet { it.requestSeat() }
                        showBanner("Taking a seat…")
                    } else {
                        showBanner("All seats are taken: watching")
                    }
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

    /** How the room's connection is doing, for the pause menu. */
    val roomStatus: String? get() = room?.status()

    /** This phone watches the room's game rather than plays. */
    val isWatching: Boolean get() = room?.watching == true

    // The line along the bottom: the key while the host is alone, and how to
    // play while watching.
    private fun updateBanner() {
        val r = room ?: return
        val text = when {
            !netStarted -> null
            r.watching && r.hasFreeSeat -> "Watching · press COIN or START to play"
            r.watching -> "Watching · all seats are taken"
            r.isOwner && r.members.size == 1 && r.key.isNotEmpty() -> "Room key ${r.key} · friends type it in Multiplayer → Join a room"
            else -> null
        }
        roomBanner.text = text
        roomBanner.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun showBanner(text: String) {
        roomBanner.text = text
        roomBanner.visibility = View.VISIBLE
    }

    /** This phone's player: 0 alone or hosting, 1 and up when joined. */
    private val localPlayer: Int get() = room?.localPlayer ?: 0

    // The room runs on its own threads; it seats this phone (from the owner's
    // game) whenever someone joins or leaves, and the game waits meanwhile.
    private fun startNetplay(room: Room) {
        lifecycleScope.launch {
            if (room.isOwner) {
                // The state the room starts from, for rematches and newcomers.
                room.startState = withContext(Dispatchers.IO) { retroView.serializeState() }
            }
            room.start(object : RoomListener {
                override fun onRemote(player: Int, start: Int, masks: IntArray) {
                    masks.forEachIndexed { i, m -> LibretroDroid.pushRemoteInput(player, start + i, m) }
                }

                // The room puts everyone back in step from the host's game by
                // itself (a moment's pause, like someone joining).
                override fun onDesync(frame: Int) = Unit

                override fun onClosed(reason: String) = runOnUiThread { netEnded(reason) }

                override fun onPartnerPaused(player: Int, paused: Boolean) = runOnUiThread {
                    remotePaused = room.partnerPaused
                    applyNetFreeze()
                }

                override fun onNews(message: String) = runOnUiThread {
                    Toast.makeText(this@GameActivity, message, Toast.LENGTH_SHORT).show()
                    // "No free seat": asking again is allowed.
                    if (room.watching) askedWith = null
                    updateBanner()
                }

                // The owner's game, to seat everyone from: stop it first.
                override fun captureState(): ByteArray {
                    seating = true
                    LibretroDroid.setFrozen(true)
                    return retroView.serializeState()
                }

                override fun onSeating(epoch: Int, activeMask: Int, state: ByteArray?) {
                    seating = true
                    LibretroDroid.setFrozen(true)
                    LibretroDroid.setNetplay(true, room.localPlayer, game.players, MAX_ROLLBACK, activeMask)
                    if (state != null) retroView.unserializeState(state)
                    runOnUiThread {
                        lastSumFrame = -1
                        lastNetFrame = -1
                        remotePaused = room.partnerPaused
                        hud.releaseAll()
                        pad.releaseAll()
                        localMask = 0
                        // Seated from watching: the COIN or START that asked goes in now.
                        tapKey = askedWith?.takeIf { !room.watching }
                        tapFrames = if (tapKey != null) 6 else 0
                        if (!room.watching || !room.hasFreeSeat) askedWith = null
                        // A player now, with the menu open: everyone waits for it.
                        if (room.watching) localPaused = false else if (dialog != null && !localPaused) pauseEmulation()
                        netStarted = true
                        seating = false
                        applyNetFreeze()
                        updateBanner()
                    }
                }
            })
        }
    }

    // Each displayed frame: read our buttons for the frames the game will
    // need next, one macro step per frame, and send them.
    private fun onNetFrame() {
        val room = room ?: return
        if (!netStarted || seating || localPaused || remotePaused) return
        val emulated = LibretroDroid.getNetFrame()
        val sample = room.sample(emulated) {
            padFrame++
            pad.onFrame()
            val tap = tapKey
            if (tap != null && tapFrames > 0) {
                tapFrames--
                localMask or (1 shl retroId(tap))
            } else {
                localMask
            }
        }
        sample.masks.forEachIndexed { i, m -> LibretroDroid.pushLocalInput(sample.start + i, m) }
        // Time sync: when this phone's game runs ahead of a partner's (see
        // Room.lead), wait a frame so it catches up. Every phone then guesses
        // as little as possible, and none stalls at the rollback limit.
        room.updateAdvantage(emulated)
        if (room.lead >= 2 && frame - lastWait > 20) {
            lastWait = frame
            LibretroDroid.netWait(1)
        }
        val sum = LibretroDroid.getNetSum()
        if (sum[0] >= 0 && sum[0] != lastSumFrame) {
            lastSumFrame = sum[0]
            room.reportSum(sum[0].toInt(), sum[1])
        }
        // Say so when a partner's inputs stop arriving for a moment.
        stalledFrames = if (emulated == lastNetFrame) stalledFrames + 1 else 0
        lastNetFrame = emulated
        val waiting = stalledFrames > 30
        if (waiting != (netStatus.visibility == View.VISIBLE)) {
            netStatus.text = if (room.watching) "Waiting for the players…" else "Waiting for ${room.peerName}…"
            netStatus.visibility = if (waiting) View.VISIBLE else View.GONE
        }
        // Stuck for 3 seconds with nobody paused: the room puts everyone back
        // in step from the host's game (a moment's pause, as when someone joins).
        if (stalledFrames > 180 && frame - lastResyncAsk > 300) {
            lastResyncAsk = frame
            netStatus.text = "Reconnecting…"
            sendNet { it.requestResync() }
        }
    }

    // The game runs only while no player has the menu open, and not while seating.
    private fun applyNetFreeze() {
        LibretroDroid.setFrozen(seating || localPaused || remotePaused)
        if (remotePaused && !localPaused) {
            netStatus.text = "${room?.pausedName ?: "Someone"} paused the game"
            netStatus.visibility = View.VISIBLE
        } else if (netStarted) {
            netStatus.visibility = View.GONE
        }
        stalledFrames = 0
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
        super.onDestroy()
        wifiLock?.let { if (it.isHeld) it.release() }
        room?.let {
            it.close()
            if (Netplay.room === it) Netplay.room = null
            LibretroDroid.setNetplay(false, 0, 2, 0, 0)
        }
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
                    if (paused || frame == 0L || !(prefs.autoDetect || prefs.detectInfo)) {
                        detectInfo.visibility = View.GONE
                        if (!prefs.autoDetect && superArt != null) {
                            superArt = null
                            updateHud()
                        }
                        continue
                    }
                    val ram = withContext(Dispatchers.Default) { readRam(spec, spec.windows) } ?: continue
                    groundBase = fighterBase(spec, ram, localPlayer)
                    onDetected(detect(spec, ram, localPlayer))
                }
            }
        }
    }

    private fun onDetected(d: Detection) {
        if (prefs.autoDetect && d.inRound) {
            var changed = false
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
            if (changed) updateHud()
        }
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
                if (!prefs.autoDetect) append("   (auto off)")
            }
        }
    }

    // ----- Team and moves -----

    fun team(): List<String?> = prefs.team(game.id, game.team)

    val rosterOrNull: Roster? get() = roster

    val teamSize: Int get() = game.team

    fun activeCharacter(): Character? {
        if (game.layout != Layout.FIGHTER) return null
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
        val detecting = canDetect && prefs.autoDetect
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
        hud.alpha = l.opacity
        updateHud()
    }

    val settings: Prefs get() = prefs

    val gameTitle: String get() = game.fullTitle

    // ----- Dialogs, pause, save states -----

    fun show(d: GameDialog) {
        dialog = d
        pauseEmulation()
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

    // Save states run on the emulation thread, so they're done with the game running.
    fun saveState() = close {
        if (room != null) return@close
        val bytes = withContext(Dispatchers.IO) { retroView.serializeState() }
        withContext(Dispatchers.IO) { stateFile.writeBytes(bytes) }
        Toast.makeText(this, "State saved", Toast.LENGTH_SHORT).show()
    }

    fun loadState() = close {
        if (room != null) return@close
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
