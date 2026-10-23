package com.kofarcade

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.lifecycle.lifecycleScope
import com.kofarcade.core.Character
import com.kofarcade.core.Game
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.Pad
import com.kofarcade.core.Roster
import com.kofarcade.core.Timing
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
    data class Error(val message: String) : GameDialog
}

/** Runs one game: FBNeo through LibretroDroid, with our touch controls on top. */
class GameActivity : ComponentActivity(), HudView.Listener {
    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_SET = "set"
        private const val MAX_MOVES = 6
        private const val CORE = "libfbneo_libretro_android.so"
    }

    private lateinit var game: Game
    private lateinit var set: String
    private lateinit var prefs: Prefs
    private lateinit var retroView: GLRetroView
    private lateinit var hud: HudView
    private lateinit var root: FrameLayout
    private lateinit var pad: Pad
    private var roster: Roster? = null

    private var frame = 0L
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
        val roms = RomStore(this)

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
            savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        pad = Pad(send = ::sendKey, clock = { frame })
        hud = HudView(this, this)

        val dialogs = ComposeView(this).apply { setContent { KofTheme { GameDialogs(this@GameActivity) } } }
        root = FrameLayout(this)
        setContentView(
            root.apply {
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
                if (it is GLRetroView.GLRetroEvents.FrameRendered) {
                    frame++
                    pad.onFrame()
                    hud.onFrame(frame)
                }
            }
        }
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
            "FBNeo couldn't load $set.zip. Check it's the right ROM set for FBNeo, and that neogeo.zip is added."
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "This phone's graphics aren't supported."
        else -> "Something went wrong running the game (error $code)."
    }

    // ----- Input -----

    // FBNeo's default Neo Geo layout puts A on RetroPad B, B on A, C on Y and D on X.
    private fun keyCode(key: Key) = when (key) {
        Key.A -> KeyEvent.KEYCODE_BUTTON_B
        Key.B -> KeyEvent.KEYCODE_BUTTON_A
        Key.C -> KeyEvent.KEYCODE_BUTTON_Y
        Key.D -> KeyEvent.KEYCODE_BUTTON_X
        Key.COIN -> KeyEvent.KEYCODE_BUTTON_SELECT
        Key.START -> KeyEvent.KEYCODE_BUTTON_START
        else -> error("not a button: $key")
    }

    private fun sendKey(key: Key, down: Boolean) {
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

    override fun onPlay(cmd: String): Int = pad.play(cmd)

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

    override fun onRapid() {
        game.rapid?.let { pad.setRapid(it, !pad.isRapid(it)) }
        updateHud()
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

    fun movesFor(c: Character): List<Move> = (prefs.customMoves(movesKey(c)) ?: c.moves).take(MAX_MOVES)

    fun defaultMoves(c: Character): List<Move> = c.moves.take(MAX_MOVES)

    fun saveMoves(c: Character, moves: List<Move>) {
        prefs.setCustomMoves(movesKey(c), moves)
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
            game.rapid?.let {
                val on = pad.isRapid(it)
                add(HudView.SysButton("Rapid", if (on) "ON" else "OFF", null, on = on))
            }
        }
        val active = prefs.active(game.id)
        val slots = if (game.layout == Layout.FIGHTER && roster != null) {
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
            size = prefs.size,
            leftHanded = prefs.leftHanded,
            labelMode = prefs.labelMode,
            stick = prefs.stick,
            haptics = prefs.haptics,
        )
    }

    // The game renders into a 4:3 surface; the view it sits in is as wide as
    // the chosen screen mode, and the display stretches the surface to fill it.
    private fun applyScreen() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val aspect = prefs.screen.aspect
        val viewW = if (aspect == null) w else minOf(w, (h * aspect).roundToInt())
        val lp = retroView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != viewW) {
            lp.width = viewW
            retroView.layoutParams = lp
        }
        retroView.holder.setFixedSize((h * 4f / 3f).roundToInt(), h)
    }

    // Layouts are saved per kind of game and per hand.
    private fun layoutKey() = "${game.layout.name}.${if (prefs.leftHanded) "L" else "R"}"

    fun applyPrefs() {
        hud.tweaks = prefs.tweaks(layoutKey())
        applyScreen()
        pad.timing = Timing(step = prefs.stepFrames, press = prefs.pressFrames)
        hud.alpha = prefs.opacity
        updateHud()
    }

    val settings: Prefs get() = prefs

    val gameTitle: String get() = game.title

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
        if (paused) return
        paused = true
        hud.releaseAll()
        pad.releaseAll()
        retroView.onPause()
        LibretroDroid.pause()
    }

    private fun resumeEmulation() {
        if (!paused) return
        paused = false
        LibretroDroid.resume()
        retroView.onResume()
    }

    private val stateFile get() = File(File(filesDir, "states").apply { mkdirs() }, "$set.state")

    // Save states run on the emulation thread, so they're done with the game running.
    fun saveState() = close {
        val bytes = withContext(Dispatchers.IO) { retroView.serializeState() }
        withContext(Dispatchers.IO) { stateFile.writeBytes(bytes) }
        Toast.makeText(this, "State saved", Toast.LENGTH_SHORT).show()
    }

    fun loadState() = close {
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
