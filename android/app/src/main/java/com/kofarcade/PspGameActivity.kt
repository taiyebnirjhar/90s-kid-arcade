package com.kofarcade

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kofarcade.core.Key
import com.kofarcade.core.Layout
import com.kofarcade.core.Move
import com.kofarcade.core.Pad
import com.kofarcade.core.Timing
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Plays one PSP game with PPSSPP (docs/psp-plan.md), dressed like the arcade
 * games: the KOF 2002 controls ([HudView]) with this game's own buttons on
 * top ([PspProfile]), the arcade's pause menu, Settings (shared with the
 * arcade games: stick, size, see-through, left-handed, vibration, sound) and
 * layout editor, plus this game's Graphics & speed (kept per game). A gamepad
 * works too. A speed counter checks the plan's rule (full speed in 99% of
 * seconds, no long stalls), timed on the emulator's own thread.
 */
class PspGameActivity : ComponentActivity(), HudView.Listener {
    companion object {
        const val EXTRA_PATH = "path"
        private const val CORE = "libppsspp_libretro_android.so"
        /** Bump when the bundled PPSSPP assets change, so they're unpacked again. */
        private const val ASSETS_VERSION = 1
        /** Kept only once the game has run this long (10 s): before, there's nothing to keep. */
        private const val KEEP_AFTER_FRAMES = 600L
        /** Goes on from where it was left once the core has booted (1 s in). */
        private const val RESUME_AT_FRAME = 60L
        /** RETRO_MEMORY_SYSTEM_RAM: the PSP's main memory, from 0x08000000. */
        private const val SYSTEM_RAM = 2
        /** The stick's dead zone (of its reach): a resting thumb doesn't walk. */
        private const val STICK_DEAD = 0.12f
    }

    /** What's open over the game. */
    enum class Sheet { PAUSE, SETTINGS, GRAPHICS, LOG }

    private lateinit var retroView: GLRetroView
    private lateinit var hud: HudView
    private lateinit var pad: Pad
    private lateinit var profile: PspProfile
    private lateinit var store: PspStore
    private lateinit var prefs: Prefs
    private lateinit var gameFile: File
    @Volatile private var padFrame = 0L
    private var frame = 0L
    /** Directions held by the stick, for the analog stick. */
    private val dirs = HashSet<Key>()
    /** The stick works the PSP's D-pad (menus, a game's D-pad picks) instead of its analog stick: the top bar's D-PAD switch. */
    @Volatile private var stickIsDpad = false
    /** A PSP step sequence playing ([PspSteps]): the keys held on each frame still to come. */
    @Volatile private var steps: List<Set<Int>> = emptyList()
    private var stepsHeld: Set<Int> = emptySet()
    private val meter = SpeedMeter()
    private val log = GameLog(SystemClock::elapsedRealtime)
    private var paused = false

    var title by mutableStateOf("")
        private set
    var sheet by mutableStateOf<Sheet?>(null)
        private set
    /** The settings in use now: what the player chose, or lower for a while ([PspGuard]). */
    var graphics by mutableStateOf(PspSettings())
        private set
    /** What the player chose in Graphics & speed (saved); Auto never goes past it. */
    var chosen by mutableStateOf(PspSettings())
        private set
    /** The speed counter over the game: off unless chosen (Graphics & speed); the game log has it all. */
    var showSpeed by mutableStateOf(false)
        private set
    var speedLine by mutableStateOf("")
        private set
    /** What's on screen now: the shared settings, or a preview while Settings or the editor is open. */
    var look by mutableStateOf(Look(ScreenMode.WIDE, StickMode.FLOATING, 1f, DEFAULT_OPACITY, LabelMode.SUPERS, true, false, 2, 4))
        private set
    var editing by mutableStateOf(false)
        private set
    var editSelected by mutableStateOf<String?>(null)
        private set
    var editScale by mutableFloatStateOf(1f)
        private set
    private var editBackup: Map<String, HudView.Tweak> = emptyMap()
    private var editStartLook: Look? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameFile = File(intent.getStringExtra(EXTRA_PATH).orEmpty())
        if (!gameFile.isFile) {
            finish()
            return
        }
        title = PspGames.title(gameFile)
        prefs = Prefs(this)
        store = PspStore(this, gameFile.name)
        graphics = store.settings()
        chosen = graphics
        profile = PspProfile.forTitle(title)
        val system = File(filesDir, "system").apply { mkdirs() }
        unpackAssets(system)
        LibretroDroid.setFrozen(false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask Android for steady top speed (no throttling mid-fight) where it allows it.
        runCatching { window.setSustainedPerformanceMode(true) }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir, CORE).absolutePath
            gameFilePath = gameFile.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = File(filesDir, "psp/saves").apply { mkdirs() }.absolutePath
            // PPSSPP hands its sound over unevenly (0 to ~2,000 samples a frame,
            // backlogs of ~2,000 in Ghost of Sparta's play, measured in the game):
            // the low-latency buffer (6 frames) overflowed and dropped sound
            // (crackles, the owner's log). The regular one holds 12 frames.
            preferLowLatencyAudio = false
            rumbleEventsEnabled = false
            shader = shaderFor(graphics.picture)
            variables = variables(graphics)
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        // Timed on the emulator's thread, right after each frame: a busy main
        // thread can't make a stall that isn't there.
        retroView.onFrame = {
            meter.frame(SystemClock.elapsedRealtime())
            padFrame++
            pad.onFrame()
            stepFrame()
        }

        pad = Pad(send = ::sendKey, clock = { padFrame })
        hud = HudView(this, this)
        hud.onSelect = { id ->
            editSelected = id
            editScale = id?.let { hud.tweaks[it]?.scale } ?: 1f
        }
        val overlay = ComposeView(this).apply { setContent { KofTheme { Overlays() } } }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            },
        )
        applyLook(prefs.look())
        log.event("Started ${gameFile.name} (${gameFile.length() / 1_000_000} MB), ${settingsLine(graphics)}")
        GameLogs.watchPlayback(this, log, meter)
        guardSpeed()
        followTheGame()

        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect {
                if (it is GLRetroView.GLRetroEvents.FrameRendered) {
                    hud.onFrame(++frame)
                    if (frame == RESUME_AT_FRAME) goOnWhereLeft()
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(500)
                speedLine = "${meter.lastFps} fps · full speed ${meter.fullSpeedPercent}% · " +
                    "slow ${meter.slowSeconds}/${meter.seconds} s · stalls ${meter.stalls.size} (longest ${meter.longestStallMs} ms)"
            }
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code ->
                log.event("Emulator error $code")
                saveLog()
                Toast.makeText(this@PspGameActivity, "The game couldn't start (error $code): the file may be damaged", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                editing -> finishEditing(save = false)
                sheet != null -> close()
                else -> open(Sheet.PAUSE)
            }
        }
    }

    // ----- Settings -----

    private fun variables(s: PspSettings) = PspSpeed.options(s).map { (k, v) -> Variable(k, v) }.toTypedArray()

    private fun shaderFor(p: PspSettings.Picture): ShaderConfig = when (p) {
        PspSettings.Picture.SHARP -> ShaderConfig.Sharp
        PspSettings.Picture.SMOOTH -> ShaderConfig.Default
        PspSettings.Picture.ENHANCED -> ShaderConfig.CUT2()
    }

    /**
     * Auto ([PspSettings.auto]): every 5 seconds of play, the picture steps
     * down when the phone can't keep up and back up when it runs smooth
     * ([PspGuard]). A step up that's taken back within a minute makes the
     * next wait twice as long (up to 5 minutes), so a scene on the edge
     * doesn't flicker between the two.
     */
    private fun guardSpeed() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var steady = PspGuard.STEADY
                var upAt = 0L
                while (true) {
                    delay(5_000)
                    if (paused || !chosen.auto) continue
                    val now = SystemClock.elapsedRealtime()
                    val down = PspGuard.stepDown(graphics, meter.perSecond)
                    if (down != null) {
                        if (now - upAt < 60_000) steady = (steady * 2).coerceAtMost(300)
                        autoStep(down.first, down.second)
                        continue
                    }
                    if (upAt != 0L && now - upAt > 120_000) steady = PspGuard.STEADY
                    val up = PspGuard.stepUp(graphics, chosen, meter.perSecond, steady) ?: continue
                    upAt = now
                    autoStep(up.first, up.second)
                }
            }
        }
    }

    private fun autoStep(s: PspSettings, why: String) {
        log.event("Auto: $why")
        apply(s)
        // The count starts again: the next step waits for the new settings' own seconds.
        meter.reset()
        Toast.makeText(this, why, Toast.LENGTH_SHORT).show()
    }

    /** Graphics & speed changed by the player: saved for this game, applied at once. */
    fun applyGraphics(s: PspSettings) {
        chosen = s
        store.save(s)
        apply(s)
    }

    private fun apply(s: PspSettings) {
        val old = graphics
        graphics = s
        if (old != s) log.event("Graphics & speed: ${settingsLine(s)}")
        if (PspSpeed.options(old) != PspSpeed.options(s)) {
            retroView.updateVariables(*variables(s))
            meter.reset()
        }
        if (old.picture != s.picture) retroView.shader = shaderFor(s.picture)
    }

    /** The shared settings (or a preview of them) on the controls and the sound. */
    fun applyLook(l: Look) {
        look = l
        hud.tweaks = prefs.tweaks(layoutKey())
        pad.timing = Timing(step = l.stepFrames, press = l.pressFrames)
        hud.opacity = l.opacity
        LibretroDroid.setVolume(if (l.sound) l.volume * l.volume else 0f)
        updateHud()
    }

    /** Settings' Save: kept for every game, as the arcade's own Settings does. */
    fun saveLook(l: Look) {
        prefs.stick = l.stick
        prefs.size = l.size
        prefs.opacity = l.opacity
        prefs.labelMode = l.labelMode
        prefs.haptics = l.haptics
        prefs.leftHanded = l.leftHanded
        prefs.stepFrames = l.stepFrames
        prefs.pressFrames = l.pressFrames
        prefs.soundOn = l.sound
        prefs.volume = l.volume
        log.event("Settings saved: ${GameLogs.look(l)}")
        applyLook(prefs.look())
    }

    /** Settings closed without Save: what was previewed goes back to what's saved. */
    fun cancelSettings() {
        applyLook(prefs.look())
        close()
    }

    fun toggleSound() {
        prefs.soundOn = !prefs.soundOn
        applyLook(prefs.look())
    }

    fun toggleSpeed() {
        showSpeed = !showSpeed
    }

    /** This game's layout, per hand: its buttons aren't any other game's. */
    private fun layoutKey() = "PSP-${gameFile.name}.${if (look.leftHanded) "L" else "R"}"

    /** The arcade controls for this game: the base layout and the game's own buttons. */
    private fun updateHud() {
        hud.state = HudView.State(
            layout = Layout.FIGHTER,
            faceButtons = listOf('A', 'B', 'C', 'D'),
            labels = profile.labels,
            moves = profile.moves,
            system = profile.system.map { m ->
                when (m.cmd) {
                    PspProfile.HOLD_L -> HudView.SysButton(m.name, profile.shoulderHints.first.ifEmpty { "hold" }, null, holdKey = Key.E)
                    PspProfile.HOLD_R -> HudView.SysButton(m.name, profile.shoulderHints.second.ifEmpty { "hold" }, null, holdKey = Key.F)
                    // Tap on, tap off: lit while R is held.
                    PspProfile.LATCH_R -> HudView.SysButton(m.name, if (rLatched) "on · tap off" else profile.shoulderHints.second.ifEmpty { "tap" }, m.cmd, on = rLatched)
                    // "held:FB": both held while pressed (Assassin's Creed's Sprint: R and ✕).
                    else -> if (m.cmd.startsWith("held:") && m.cmd.length == 7) {
                        val (a, b) = m.cmd.removePrefix("held:").map { c -> Key.valueOf(c.toString()) }
                        HudView.SysButton(m.name, "hold " + listOf(a, b).joinToString("+") { PspProfile.FACE_NAMES[it.name[0]] ?: it.name }, null, holdKey = a, alsoHold = b)
                    } else {
                        HudView.SysButton(m.name, sysHint(m.cmd), m.cmd)
                    }
                }
            },
            block = profile.blockHoldsL,
            // The menus only answer the D-pad: the stick switches to it by
            // itself for a game that says when it's played (God of War), and
            // this switch stays as a fallback.
            facingButton = true,
            switchLabel = "D-PAD",
            switchOn = stickIsDpad,
            size = look.size,
            leftHanded = look.leftHanded,
            labelMode = look.labelMode,
            stick = look.stick,
            haptics = look.haptics,
            coinLabel = "SELECT",
            showCoin = profile.select,
            faceNames = PspProfile.FACE_NAMES,
        )
    }

    /** A pill's keys as the PSP calls them ("L+R", "✚↓"). */
    private fun sysHint(cmd: String): String = when {
        cmd == PspProfile.MASH_LR -> "L R L R"
        cmd == PspProfile.MASH_O -> "○ ○ ○"
        cmd.startsWith("pad:") -> "✚" + com.kofarcade.core.toArrows(cmd.removePrefix("pad:"))
        else -> cmd.map { c -> PspProfile.FACE_NAMES[c] ?: c.toString() }.joinToString("+")
    }

    // ----- Pause, sheets, save states -----

    fun open(s: Sheet) {
        if (sheet == null && !paused) {
            // The game's menus don't want R held.
            setRLatched(false)
            log.event("Paused (${s.name.lowercase()})")
            keepPlace()
        }
        sheet = s
        pauseGame()
    }

    // ----- Where you left off -----

    /** The game as it was when last paused or left: opening it goes on from there. */
    private val placeFile get() = File(File(filesDir, "psp/states").apply { mkdirs() }, "${gameFile.name}.auto.state")

    /**
     * The game's paused (a menu, or the app left): kept, so the next time it
     * opens it goes on from here, as the arcade games do. Not in the first
     * seconds (nothing to keep yet), and written whole or not at all.
     */
    private fun keepPlace() {
        if (frame < KEEP_AFTER_FRAMES) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = retroView.serializeState()
                if (bytes.isEmpty()) return@runCatching
                val tmp = File(placeFile.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(placeFile)) tmp.delete()
            }.onFailure { log.event("Couldn't keep the place: ${it.message}") }
        }
    }

    /**
     * Leaving the app: the game kept right here, not queued for the
     * emulator's thread (Android stops that thread as the app goes, and may
     * close the app in the background before it would run), as the arcade
     * games do. The core takes its own lock; checked in the game: a state
     * taken this way loads back.
     */
    private fun keepPlaceNow() {
        if (frame < KEEP_AFTER_FRAMES) return
        val bytes = runCatching { retroView.serializeState(useEmulationThread = false) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return
        Thread {
            runCatching {
                val tmp = File(placeFile.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(placeFile)) tmp.delete()
            }
        }.start()
        log.event("Kept the place (${bytes.size / 1_000_000} MB)")
    }

    /**
     * Goes on from where the game was left, once it's running (the core
     * loads a state only after it has booted; it shows a few frames later).
     */
    private fun goOnWhereLeft() {
        val file = placeFile
        if (!file.isFile) return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return@launch
            repeat(5) {
                val ok = withContext(Dispatchers.IO) { runCatching { retroView.unserializeState(bytes) }.getOrDefault(false) }
                if (ok) {
                    log.event("Went on from where it was left")
                    Toast.makeText(this@PspGameActivity, "Back where you left off", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                delay(500)
            }
            log.event("Couldn't go on from where it was left: started afresh")
        }
    }

    fun close() {
        if (sheet != null) log.event("Back to the game")
        sheet = null
        applyLook(prefs.look()) // a Settings preview that wasn't saved goes back
        resumeGame()
    }

    private fun pauseGame() {
        if (paused) return
        paused = true
        meter.hold(true)
        hud.releaseAll()
        pad.cancel()
        // Frozen rather than stopped, so the picture still redraws behind the menu.
        LibretroDroid.pause()
        LibretroDroid.setFrozen(true)
    }

    private fun resumeGame() {
        if (!paused) return
        paused = false
        meter.hold(false)
        LibretroDroid.setFrozen(false)
        LibretroDroid.resume()
    }

    private val stateFile get() = File(File(filesDir, "psp/states").apply { mkdirs() }, "${gameFile.name}.state")

    fun saveState() {
        close()
        lifecycleScope.launch {
            delay(120)
            val bytes = withContext(Dispatchers.IO) { retroView.serializeState() }
            withContext(Dispatchers.IO) { stateFile.writeBytes(bytes) }
            log.event("State saved (${bytes.size / 1_000_000} MB)")
            Toast.makeText(this@PspGameActivity, "State saved", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadState() {
        close()
        lifecycleScope.launch {
            delay(120)
            val bytes = withContext(Dispatchers.IO) { runCatching { stateFile.readBytes() }.getOrNull() }
            val ok = bytes != null && withContext(Dispatchers.IO) { retroView.unserializeState(bytes) }
            log.event(if (ok) "State loaded" else "Load state: none saved")
            Toast.makeText(this@PspGameActivity, if (ok) "State loaded" else "No saved state yet: save one first", Toast.LENGTH_SHORT).show()
        }
    }

    fun startOver() {
        log.event("Started over")
        placeFile.delete()
        close()
        retroView.reset()
        meter.reset()
    }

    /** The whole game log, to paste into a chat. */
    fun logText(): String = log.text(logHeader(), meter)

    fun copyLog(text: String) = GameLogs.copy(this, text)

    fun sendLog(text: String) = GameLogs.send(this, title, text)

    /** Kept on the phone after quitting (the PSP games list copies it): the last session per game. */
    private fun saveLog() = GameLogs.save(PspGames.logFile(filesDir, gameFile.name), logText())

    /** The phone, the app, the game and its settings: the log's first lines. */
    private fun logHeader(): List<String> {
        val hz = runCatching { display?.refreshRate?.toInt() }.getOrNull() ?: 0
        return GameLogs.phone(this, hz) + listOf(
            "PSP game: $title (${gameFile.name}, ${gameFile.length() / 1_000_000} MB)",
            "Graphics & speed: ${settingsLine(graphics)}",
            "Playback: ${GameLogs.playback(hz)}",
            "Controls: ${GameLogs.look(look)}",
        )
    }

    private fun settingsLine(s: PspSettings) =
        "frame skip ${s.frameSkip.label}, sharpness ${s.resolution}x, picture ${s.picture.label}, faster effects ${if (s.fasterEffects) "on" else "off"}, auto ${if (s.auto) "on" else "off"}"

    fun restartCount() {
        log.event("Speed count restarted")
        meter.reset()
    }

    // ----- Layout editor (the arcade's) -----

    fun startEditing() {
        sheet = null
        editBackup = hud.tweaks
        editStartLook = look
        editing = true
        hud.editing = true
        pauseGame()
    }

    fun changeEditLook(l: Look) {
        if (l.leftHanded != look.leftHanded) {
            prefs.setTweaks(layoutKey(), hud.tweaks)
            look = l
            hud.tweaks = prefs.tweaks(layoutKey())
        } else {
            look = l
        }
        hud.opacity = l.opacity
        updateHud()
    }

    fun changeEditScale(v: Float) {
        val id = editSelected ?: return
        editScale = v
        hud.setScale(id, v)
    }

    fun resetLayout() {
        hud.tweaks = emptyMap()
        editScale = 1f
        changeEditLook(look.copy(size = 1f, opacity = DEFAULT_OPACITY))
    }

    fun finishEditing(save: Boolean) {
        log.event(if (save) "Button layout saved" else "Button layout: cancelled")
        if (save) {
            prefs.setTweaks(layoutKey(), hud.tweaks)
            prefs.stick = look.stick
            prefs.size = look.size
            prefs.leftHanded = look.leftHanded
            prefs.opacity = look.opacity
        } else {
            editStartLook?.let { look = it }
            hud.tweaks = editBackup
            hud.opacity = look.opacity
            updateHud()
        }
        editStartLook = null
        editing = false
        editSelected = null
        hud.editing = false
        resumeGame()
    }

    // ----- Keys to the PSP -----

    /** A key from the controls (or a move playing) to the PSP: buttons as keys, directions to the analog stick. */
    private fun sendKey(key: Key, down: Boolean) {
        val code = PspProfile.keyCode(key)
        if (code != null) {
            retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
            return
        }
        synchronized(dirs) {
            if (down) dirs += key else dirs -= key
            if (stickIsDpad) {
                dpadCode(key)?.let { retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, it) }
                return
            }
            sendStick()
        }
    }

    /** One frame of a PSP step sequence, on the emulator's thread: press what's new, let go what's done. */
    /** Where the thumb holds the on-screen stick, -1..1 (its real angle and push, not 8 ways). */
    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f

    /** The analog stick where the thumb holds it. */
    private fun sendStick() {
        val (x, y) = if (stickIsDpad) 0f to 0f else stickX to stickY
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
    }

    /**
     * The on-screen stick as a real analog stick: its angle and how far it's
     * pushed (the owner's "rotate the stick" prompt failed with 8 directions
     * at full push; and Kratos walks any way, at any speed). A small dead zone.
     */
    override fun onStick(x: Float, y: Float) {
        val far = kotlin.math.hypot(x, y)
        val k = if (far < STICK_DEAD) 0f else ((far - STICK_DEAD) / (1 - STICK_DEAD)).coerceAtMost(1f) / far
        stickX = x * k
        stickY = y * k
        if (!stickIsDpad) sendStick()
    }

    /** R held by its pill between taps ([PspProfile.LATCH_R]): Thera's Bane's fire for every combo. */
    @Volatile private var rLatched = false

    private fun setRLatched(on: Boolean) {
        if (on == rLatched) return
        rLatched = on
        sendKey(Key.F, on)
        log.event(if (on) "R held (fire / aim)" else "R let go")
        runOnUiThread { updateHud() }
    }

    private fun stepFrame() {
        val queue = steps
        val now = queue.firstOrNull() ?: emptySet()
        if (queue.isNotEmpty()) steps = queue.drop(1)
        if (now == stepsHeld) return
        for (k in stepsHeld - now) retroView.sendKeyEvent(KeyEvent.ACTION_UP, k)
        for (k in now - stepsHeld) retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, k)
        stepsHeld = now
    }

    // HudView.Listener: the arcade controls.
    override fun onDir(dir: Int) = pad.setDir(dir)
    override fun onKey(key: Key, down: Boolean) = pad.press(key, down)
    override fun onPlay(cmd: String, move: Move?): Int {
        if (cmd == PspProfile.LATCH_R) {
            setRLatched(!rLatched)
            return 1
        }
        // Mash goes frame by frame too (its timing is the one checked in the game).
        if (!PspSteps.isSteps(cmd) && cmd !in PspProfile.MASHES) return pad.play(cmd)
        val frames = PspSteps.frames(cmd)
        steps = frames
        return frames.size
    }
    override fun onMenu() = if (editing) finishEditing(save = true) else open(Sheet.PAUSE)
    /** The D-PAD switch: the stick moves between the analog stick and the D-pad (nothing stays held across). */
    /** A tap on the D-PAD switch: holds until the game next goes between play and a menu. */
    override fun onFlip() = setStickDpad(!stickIsDpad, " (the D-PAD switch)")

    /** The stick to the D-pad or the analog stick; a direction held goes on in the new one. */
    private fun setStickDpad(on: Boolean, why: String) {
        synchronized(dirs) {
            if (on == stickIsDpad) return
            val held = dirs.toList()
            for (k in held) sendKey(k, false)
            stickIsDpad = on
            for (k in held) sendKey(k, true)
        }
        log.event((if (on) "Stick: D-pad" else "Stick: analog") + why)
        runOnUiThread { updateHud() }
    }

    /**
     * A game that says when it's played (its [PspProfile.inPlay] byte): the
     * stick follows it, ten times a second, off the main thread (a read waits
     * for the emulator's frame). No button to press.
     */
    private fun followTheGame() {
        val (offset, menuValue) = profile.inPlay ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Only a change of the game's state moves the stick, so a tap
                // on the D-PAD switch (the fallback) holds until the next one.
                var wasPlayed: Boolean? = null
                while (true) {
                    delay(100)
                    if (paused) continue
                    val b = LibretroDroid.readMemory(SYSTEM_RAM, offset, 1)?.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                    // Only the menu value is a menu: play read 4 on the boat, and
                    // might read otherwise elsewhere in the game.
                    val played = b != menuValue
                    if (played != wasPlayed) {
                        wasPlayed = played
                        setStickDpad(!played, (if (played) " (playing" else " (a menu") + ", game byte $b)")
                    }
                }
            }
        }
    }

    private fun dpadCode(key: Key): Int? = when (key) {
        Key.UP -> KeyEvent.KEYCODE_DPAD_UP
        Key.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Key.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Key.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> null
    }
    override fun onSlot(index: Int) = Unit
    override fun onRapid() = Unit
    override fun onBlock(relDir: Int?) {
        // God of War blocks with L held (a tap parries).
        if (profile.blockHoldsL) pad.press(Key.E, relDir != null)
    }
    override fun onRun(on: Boolean) = Unit

    // A gamepad: Android's A (bottom) is the RetroPad's B (bottom, PPSSPP's
    // cross), and so on round the diamond.
    private fun padKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> KeyEvent.KEYCODE_BUTTON_L1
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_BUTTON_R1
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> code
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        val mapped = if (fromPad && sheet == null) padKey(event.keyCode) else null
        if (mapped != null && ::retroView.isInitialized) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) retroView.sendKeyEvent(event.action, mapped)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && ::retroView.isInitialized) {
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        // Leaving the app: the menu comes up, so the game waits for you.
        if (::pad.isInitialized) {
            log.event("Left the app")
            if (sheet == null && !editing) open(Sheet.PAUSE)
            keepPlaceNow()
            saveLog()
        }
        super.onPause()
    }

    override fun finish() {
        if (::pad.isInitialized) {
            log.event("Quit")
            saveLog()
            // A session with a bad freeze sends its log by itself (problem reports on).
            if (meter.longestStallMs >= GameLogs.BAD_FREEZE_MS) Online.sendGameLog(this, title, logText(), auto = true)
        }
        super.finish()
    }

    /** PPSSPP's fonts and shaders (bundled as assets/PPSSPP.zip) into the system folder, once. */
    private fun unpackAssets(system: File) {
        val marker = File(system, "PPSSPP/.unpacked-$ASSETS_VERSION")
        if (marker.exists()) return
        runCatching {
            assets.open("PPSSPP.zip").use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { e ->
                        // The zip holds a PPSSPP/ folder; anything else goes inside one.
                        val path = if (e.name.startsWith("PPSSPP/")) e.name else "PPSSPP/${e.name}"
                        val out = File(system, path).canonicalFile
                        if (!out.path.startsWith(system.canonicalPath)) return@forEach
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("ok")
        }
    }

    // ----- What's drawn over the game -----

    @Composable
    private fun Overlays() {
        if (showSpeed && sheet == null && !editing) SpeedLine(speedLine)
        if (editing) PspEditBar(this)
        when (sheet) {
            Sheet.PAUSE -> Overlay(onDismiss = ::close, scrim = false) {
                PauseContent(
                    title = title,
                    fighter = null,
                    netplay = false,
                    playback = speedLine.ifEmpty { null },
                    onReport = { sendLog(logText()) },
                    soundOn = look.sound,
                    onSound = ::toggleSound,
                    onResume = ::close,
                    onRestart = ::startOver,
                    onSave = ::saveState,
                    onLoad = ::loadState,
                    onEditMoves = {},
                    onLayout = ::startEditing,
                    onControls = { sheet = Sheet.SETTINGS },
                    onQuit = ::finish,
                    extra = listOf(
                        "Graphics & speed" to { sheet = Sheet.GRAPHICS },
                        "Game log" to { sheet = Sheet.LOG },
                    ),
                )
            }
            Sheet.SETTINGS -> PspSettingsSheet(this)
            Sheet.LOG -> PspSettingsSheet(this, SettingsTab.LOG)
            Sheet.GRAPHICS -> Overlay(onDismiss = ::close, scrim = false) { PspGraphicsPanel(this) }
            null -> Unit
        }
    }
}

/** The speed counter: small, under the top bar, out of the way. */
@Composable
private fun SpeedLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 46.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Settings: the arcade's own panel (stick, size, see-through, side, vibration, sound, move timing), shared with every game. */
@Composable
private fun PspSettingsSheet(game: PspGameActivity, startTab: SettingsTab = SettingsTab.CONTROLS) {
    var look by remember { mutableStateOf(game.look) }
    Overlay(onDismiss = game::cancelSettings, scrim = false) {
        ControlsPanel(
            look = look,
            onLook = {
                look = it
                game.applyLook(it)
            },
            canDetect = false,
            detectInfo = false,
            onDetectInfo = {},
            onCancel = game::cancelSettings,
            onSave = {
                game.saveLook(look)
                game.close()
            },
            startTab = startTab,
            tabs = listOf(SettingsTab.CONTROLS, SettingsTab.MOVES, SettingsTab.SOUND, SettingsTab.LOG),
            gameLog = game::logText,
            onCopyLog = game::copyLog,
            onSendLog = game::sendLog,
        )
    }
}

/** This game's Graphics & speed, kept per game: the arcade panel's look and rows. */
@Composable
private fun PspGraphicsPanel(game: PspGameActivity) {
    val s = game.chosen
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxSize().widthIn(max = 420.dp).slantPanel(left = 28.dp, right = 0.dp)
                .verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 24.dp, top = 20.dp, bottom = 20.dp),
        ) {
            Text("GRAPHICS & SPEED", color = Palette.gold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(game.title, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                "Auto", s.auto, { game.applyGraphics(s.copy(auto = it)) },
                sub = "Lowers the picture in heavy scenes and puts it back when the game runs smooth, never past what you pick below",
            )
            if (s.auto && game.graphics != s) {
                val g = game.graphics
                Text(
                    "Now, for a heavy scene: sharpness ${g.resolution}x, ${g.picture.label}, faster effects ${if (g.fasterEffects) "on" else "off"}",
                    color = Palette.gold, fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
            PickRow(
                "Frame skip", PspSpeed.FrameSkip.entries, s.frameSkip, { it.label },
                sub = "Auto skips a picture only when behind, so the game never slows",
            ) { game.applyGraphics(s.copy(frameSkip = it)) }
            PickRow(
                "Sharpness", listOf(1, 2), s.resolution, { if (it >= 2) "2x" else "1x (PSP's own)" },
                sub = "2x is sharper but heavy (with Auto on, it drops to 1x in heavy scenes and comes back)",
            ) { game.applyGraphics(s.copy(resolution = it)) }
            PickRow("Picture", PspSettings.Picture.entries, s.picture, { it.label }, sub = "How the picture is stretched to the screen") {
                game.applyGraphics(s.copy(picture = it))
            }
            ToggleRow("Faster effects", s.fasterEffects, { game.applyGraphics(s.copy(fasterEffects = it)) }, sub = "Skips some glows and blur, for speed")
            ToggleRow("Speed counter on screen", game.showSpeed, { game.toggleSpeed() }, sub = "Off keeps the game clear; the game log has it all")
            Spacer(Modifier.height(14.dp))
            Row {
                GhostButton("Restart the count", { game.restartCount() }, compact = true)
                Spacer(Modifier.padding(4.dp))
                GhostButton("Game log", { game.open(PspGameActivity.Sheet.LOG) }, compact = true)
                Spacer(Modifier.padding(4.dp))
                GoldButton("Done", { game.close() }, compact = true)
            }
        }
    }
}

/** The arcade's layout editor bar, on a PSP game. */
@Composable
private fun PspEditBar(game: PspGameActivity) {
    var allFromLeft by remember { mutableStateOf<Boolean?>(null) }
    EditBarContent(
        picked = game.editSelected?.let(::pspControlName),
        scale = game.editScale,
        canResize = game.editSelected.let { it != null && it != "top" },
        onScale = game::changeEditScale,
        onAll = { allFromLeft = !game.look.leftHanded },
        onReset = game::resetLayout,
        onCancel = { game.finishEditing(save = false) },
        onSave = { game.finishEditing(save = true) },
    )
    allFromLeft?.let { fromLeft ->
        androidx.activity.compose.BackHandler { allFromLeft = null }
        EditAllSheet(game.look, game::changeEditLook, fromLeft, onClose = { allFromLeft = null })
    }
}

/** Friendly names for the editor, with the PSP's buttons. */
private fun pspControlName(id: String): String = when {
    id == "stick" -> "Stick"
    id == "pause" -> "Pause button"
    id == "top" -> "Select / Start"
    id == "block" -> "Block button"
    id.startsWith("face:") -> "${PspProfile.FACE_NAMES[id.removePrefix("face:").first()] ?: id} button"
    id.startsWith("move:") -> "Move ${id.removePrefix("move:").toInt() + 1}"
    id.startsWith("sys:") -> "Quick button ${id.removePrefix("sys:").toInt() + 1}"
    else -> id
}

/** A PSP game's graphics settings, kept on the phone per game. */
class PspStore(context: Context, private val game: String) {
    private val prefs = context.getSharedPreferences("psp", Context.MODE_PRIVATE)

    fun settings(): PspSettings {
        val d = PspSettings()
        return PspSettings(
            frameSkip = PspSpeed.FrameSkip.entries.firstOrNull { it.name == prefs.getString("$game.skip", null) } ?: d.frameSkip,
            resolution = prefs.getInt("$game.res", d.resolution),
            fasterEffects = prefs.getBoolean("$game.fx", d.fasterEffects),
            picture = PspSettings.Picture.entries.firstOrNull { it.name == prefs.getString("$game.picture", null) } ?: d.picture,
            auto = prefs.getBoolean("$game.auto", d.auto),
        )
    }

    fun save(s: PspSettings) {
        prefs.edit()
            .putString("$game.skip", s.frameSkip.name)
            .putInt("$game.res", s.resolution)
            .putBoolean("$game.fx", s.fasterEffects)
            .putString("$game.picture", s.picture.name)
            .putBoolean("$game.auto", s.auto)
            .apply()
    }
}
