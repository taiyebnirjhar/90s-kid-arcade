package com.kofarcade

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Plays one PSP game with PPSSPP (docs/psp-plan.md): its settings (kept per
 * game), the PSP's touch controls laid out for the game (or a gamepad), and a
 * speed counter for the plan's rule (full speed in 99% of seconds, no long
 * stalls), measured on the emulator's own thread.
 */
class PspGameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        private const val CORE = "libppsspp_libretro_android.so"
        /** Bump when the bundled PPSSPP assets change, so they're unpacked again. */
        private const val ASSETS_VERSION = 1
    }

    private lateinit var retroView: GLRetroView
    private lateinit var pad: PspPad
    private lateinit var speedText: TextView
    private lateinit var store: PspStore
    private lateinit var settings: PspSettings
    private val meter = SpeedMeter()
    private var showSpeed = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val game = File(intent.getStringExtra(EXTRA_PATH).orEmpty())
        if (!game.isFile) {
            finish()
            return
        }
        store = PspStore(this, game.name)
        settings = store.settings()
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
            gameFilePath = game.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = File(filesDir, "psp/saves").apply { mkdirs() }.absolutePath
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
            shader = shaderFor(settings.picture)
            variables = variables(settings)
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        // Timed on the emulator's thread, right after each frame: a busy main
        // thread can't make a stall that isn't there.
        retroView.onFrame = { meter.frame(SystemClock.elapsedRealtime()) }

        pad = PspPad(
            this,
            PspProfile.forTitle(PspGames.title(game)),
            onKey = { code, down -> retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code) },
            onStick = { x, y -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y) },
            onMenu = ::onMenuButton,
        ).apply {
            moves.putAll(store.moves())
            scale = settings.controlsScale
            opacity = settings.controlsOpacity
        }
        speedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(14, 4, 14, 4)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(pad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(speedText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (44 * resources.displayMetrics.density).toInt() })
            },
        )

        lifecycleScope.launch {
            while (true) {
                delay(500)
                speedText.visibility = if (showSpeed) View.VISIBLE else View.GONE
                speedText.text = "${meter.lastFps} fps · full speed ${meter.fullSpeedPercent}% · slow ${meter.slowSeconds}/${meter.seconds} s · " +
                    "stalls ${meter.stalls.size} (longest ${meter.longestStallMs} ms)"
            }
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code ->
                AlertDialog.Builder(this@PspGameActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("The game couldn't start")
                    .setMessage("Error $code. The file may be damaged or not a PSP game.")
                    .setPositiveButton("Back") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
        onBackPressedDispatcher.addCallback(this) { onMenuButton() }
    }

    private fun variables(s: PspSettings) = PspSpeed.options(s).map { (k, v) -> Variable(k, v) }.toTypedArray()

    private fun shaderFor(p: PspSettings.Picture): ShaderConfig = when (p) {
        PspSettings.Picture.SHARP -> ShaderConfig.Sharp
        PspSettings.Picture.SMOOTH -> ShaderConfig.Default
        PspSettings.Picture.ENHANCED -> ShaderConfig.CUT2()
    }

    /** Saves and applies [s]: the core's options at once, the picture, the controls. */
    private fun apply(s: PspSettings) {
        val old = settings
        settings = s
        store.save(s)
        if (PspSpeed.options(old) != PspSpeed.options(s)) {
            retroView.updateVariables(*variables(s))
            meter.reset()
        }
        if (old.picture != s.picture) retroView.shader = shaderFor(s.picture)
        pad.scale = s.controlsScale
        pad.opacity = s.controlsOpacity
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

    private fun onMenuButton() {
        if (pad.editing) {
            pad.editing = false
            store.saveMoves(pad.moves)
            return
        }
        showMenu()
    }

    private fun showMenu() {
        pad.releaseAll()
        val s = settings
        val items = listOf(
            "Frame skip: ${s.frameSkip.label}" to {
                val all = PspSpeed.FrameSkip.entries
                apply(s.copy(frameSkip = all[(all.indexOf(s.frameSkip) + 1) % all.size]))
            },
            "Sharpness: ${if (s.resolution >= 2) "2x (sharper, slower)" else "1x (PSP's own, fastest)"}" to {
                apply(s.copy(resolution = if (s.resolution >= 2) 1 else 2))
            },
            "Picture: ${s.picture.label}" to {
                val all = PspSettings.Picture.entries
                apply(s.copy(picture = all[(all.indexOf(s.picture) + 1) % all.size]))
            },
            "Faster effects (skips some glows): ${if (s.fasterEffects) "On" else "Off"}" to {
                apply(s.copy(fasterEffects = !s.fasterEffects))
            },
            "Controls size: ${sizeLabel(s.controlsScale)}" to {
                val sizes = listOf(0.8f, 1f, 1.2f)
                apply(s.copy(controlsScale = sizes[(sizes.indexOfFirst { it >= s.controlsScale - 0.01f } + 1).mod(sizes.size)]))
            },
            "Controls see-through: ${opacityLabel(s.controlsOpacity)}" to {
                val levels = listOf(0.25f, 0.45f, 0.7f)
                apply(s.copy(controlsOpacity = levels[(levels.indexOfFirst { it >= s.controlsOpacity - 0.01f } + 1).mod(levels.size)]))
            },
            "Move the controls" to { pad.editing = true },
            "Put the controls back" to {
                pad.moves.clear()
                store.saveMoves(pad.moves)
                pad.relayout()
            },
            "Hide / show the controls" to { pad.visibility = if (pad.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE },
            "${if (showSpeed) "Hide" else "Show"} the speed counter" to { showSpeed = !showSpeed },
            "Speed report" to ::showReport,
            "Restart the speed count" to { meter.reset() },
            "Quit the game" to { finish() },
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PSP")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Back to the game", null)
            .show()
    }

    private fun sizeLabel(v: Float) = when {
        v < 0.9f -> "Small"
        v > 1.1f -> "Big"
        else -> "Normal"
    }

    private fun opacityLabel(v: Float) = when {
        v < 0.35f -> "Most (faint)"
        v > 0.6f -> "Least (solid)"
        else -> "Medium"
    }

    /** What the counter saw: for the owner to screenshot. */
    private fun showReport() {
        val stalls = meter.stalls
        val list = if (stalls.isEmpty()) "none" else stalls.takeLast(12).joinToString("\n") { (sec, ms) -> "  at ${sec / 60}:${"%02d".format(sec % 60)} — $ms ms" }
        val s = settings
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Speed report")
            .setMessage(
                "Played ${meter.seconds / 60} min ${meter.seconds % 60} s\n" +
                    "Full speed: ${meter.fullSpeedPercent}% of seconds (slow: ${meter.slowSeconds})\n" +
                    "Longest stall: ${meter.longestStallMs} ms\n" +
                    "Stalls over ${SpeedMeter.STALL_MS} ms (time, length):\n$list\n\n" +
                    "Settings: frame skip ${s.frameSkip.label}, sharpness ${s.resolution}x, picture ${s.picture.label}, " +
                    "faster effects ${if (s.fasterEffects) "on" else "off"}",
            )
            .setPositiveButton("OK", null)
            .show()
    }

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
        val mapped = if (fromPad) padKey(event.keyCode) else null
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
        if (::pad.isInitialized) pad.releaseAll()
        super.onPause()
    }
}

/** A PSP game's settings and control spots, kept on the phone per game. */
class PspStore(context: Context, private val game: String) {
    private val prefs = context.getSharedPreferences("psp", Context.MODE_PRIVATE)

    fun settings(): PspSettings {
        val d = PspSettings()
        return PspSettings(
            frameSkip = PspSpeed.FrameSkip.entries.firstOrNull { it.name == prefs.getString("$game.skip", null) } ?: d.frameSkip,
            resolution = prefs.getInt("$game.res", d.resolution),
            fasterEffects = prefs.getBoolean("$game.fx", d.fasterEffects),
            picture = PspSettings.Picture.entries.firstOrNull { it.name == prefs.getString("$game.picture", null) } ?: d.picture,
            controlsScale = prefs.getFloat("$game.scale", d.controlsScale),
            controlsOpacity = prefs.getFloat("$game.opacity", d.controlsOpacity),
        )
    }

    fun save(s: PspSettings) {
        prefs.edit()
            .putString("$game.skip", s.frameSkip.name)
            .putInt("$game.res", s.resolution)
            .putBoolean("$game.fx", s.fasterEffects)
            .putString("$game.picture", s.picture.name)
            .putFloat("$game.scale", s.controlsScale)
            .putFloat("$game.opacity", s.controlsOpacity)
            .apply()
    }

    /** "group:x:y;…" as fractions of the screen. */
    fun moves(): Map<String, PointF> =
        prefs.getString("$game.moves", "").orEmpty().split(';').mapNotNull { part ->
            val bits = part.split(':')
            if (bits.size != 3) return@mapNotNull null
            val x = bits[1].toFloatOrNull() ?: return@mapNotNull null
            val y = bits[2].toFloatOrNull() ?: return@mapNotNull null
            bits[0] to PointF(x, y)
        }.toMap()

    fun saveMoves(moves: Map<String, PointF>) {
        prefs.edit().putString("$game.moves", moves.entries.joinToString(";") { (g, p) -> "$g:${p.x}:${p.y}" }).apply()
    }
}
