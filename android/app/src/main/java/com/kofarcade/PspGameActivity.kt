package com.kofarcade

import android.graphics.Color
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
import android.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Plays one PSP game with PPSSPP (docs/psp-plan.md, P1): the fast settings,
 * the PSP's touch controls (or a gamepad), and a speed counter for the
 * plan's rule (full speed in 99% of seconds, no long stalls).
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
    private val meter = SpeedMeter()
    private var frameSkip = PspSpeed.FrameSkip.OFF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val game = File(intent.getStringExtra(EXTRA_PATH).orEmpty())
        if (!game.isFile) {
            finish()
            return
        }
        val system = File(filesDir, "system").apply { mkdirs() }
        unpackAssets(system)
        LibretroDroid.setFrozen(false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            variables = PspSpeed.options(frameSkip).map { (k, v) -> Variable(k, v) }.toTypedArray()
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        pad = PspPad(
            this,
            onKey = { code, down -> retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code) },
            onStick = { x, y -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y) },
            onMenu = ::showMenu,
        )
        speedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(16, 6, 16, 6)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(retroView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                addView(pad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(speedText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (56 * resources.displayMetrics.density).toInt() })
            },
        )

        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) meter.frame(SystemClock.elapsedRealtime()) }
        }
        lifecycleScope.launch {
            while (true) {
                delay(500)
                speedText.text = "${meter.lastFps} fps · full speed ${meter.fullSpeedPercent}% · slow ${meter.slowSeconds}/${meter.seconds} s · " +
                    "longest stall ${meter.longestStallMs} ms · skip ${frameSkip.label}"
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
        onBackPressedDispatcher.addCallback(this) { showMenu() }
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

    private fun showMenu() {
        pad.releaseAll()
        val skips = PspSpeed.FrameSkip.entries
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PSP")
            .setItems(
                arrayOf(
                    "Frame skip: ${frameSkip.label} (tap to change)",
                    "Restart the speed count",
                    "Hide / show the controls",
                    "Quit the game",
                ),
            ) { _, which ->
                when (which) {
                    0 -> {
                        frameSkip = skips[(skips.indexOf(frameSkip) + 1) % skips.size]
                        retroView.updateVariables(*PspSpeed.options(frameSkip).map { (k, v) -> Variable(k, v) }.toTypedArray())
                        meter.reset()
                    }
                    1 -> meter.reset()
                    2 -> pad.visibility = if (pad.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                    3 -> finish()
                }
            }
            .setNegativeButton("Back to the game", null)
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
