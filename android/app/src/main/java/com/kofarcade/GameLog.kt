package com.kofarcade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File

/**
 * How fast the game runs, second by second: for the PSP plan's rule (full
 * speed in 99% of seconds, no stall over a second) and every game's log. A
 * second with fewer than [slowBelow] frames counts as slow.
 */
class SpeedMeter(val slowBelow: Int = SLOW_BELOW) {
    /** Stalls of a quarter second or more: when (seconds into the count) and how long (ms). */
    private val stallList = ArrayList<Pair<Int, Long>>()
    val stalls: List<Pair<Int, Long>> @Synchronized get() = stallList.toList()

    /** Every second's frame count, in order, for the game log's timeline. */
    private val history = ArrayList<Int>()
    val perSecond: List<Int> @Synchronized get() = history.toList()

    var seconds = 0
        private set
    var slowSeconds = 0
        private set
    var lastFps = 0
        private set
    /** The longest gap between two frames so far (ms). */
    var longestStallMs = 0L
        private set

    private var secondStart = -1L
    private var framesThisSecond = 0
    private var lastFrame = -1L

    /**
     * Paused (a menu, the app in the background): frames aren't counted, and
     * the wait isn't a stall; counting starts afresh from the next frame.
     */
    @Synchronized
    fun hold(on: Boolean) {
        held = on
        lastFrame = -1
        secondStart = -1
        framesThisSecond = 0
    }

    private var held = false

    /** A frame came out at [nowMs]. */
    @Synchronized
    fun frame(nowMs: Long) {
        if (held) return
        if (lastFrame >= 0) {
            val gap = nowMs - lastFrame
            longestStallMs = maxOf(longestStallMs, gap)
            if (gap >= STALL_MS) stallList += seconds to gap
        }
        lastFrame = nowMs
        if (secondStart < 0) secondStart = nowMs
        framesThisSecond++
        while (nowMs - secondStart >= 1000) close()
    }

    private fun close() {
        lastFps = framesThisSecond
        history += framesThisSecond
        seconds++
        if (framesThisSecond < slowBelow) slowSeconds++
        framesThisSecond = 0
        secondStart += 1000
    }

    /** Percent of seconds at full speed (100 before the first second). */
    val fullSpeedPercent: Int get() = if (seconds == 0) 100 else ((seconds - slowSeconds) * 100) / seconds

    @Synchronized
    fun reset() {
        stallList.clear()
        history.clear()
        seconds = 0; slowSeconds = 0; lastFps = 0; longestStallMs = 0
        secondStart = -1; framesThisSecond = 0; lastFrame = -1
    }

    companion object {
        /** 95% of 59.94 (the PSP): a second under this the eye sees as slow. */
        const val SLOW_BELOW = 57
        /** Arcade boards run from about 54 to 60 frames a second: under this is slow on any of them. */
        const val ARCADE_SLOW_BELOW = 52
        /** A gap this long between frames counts as a stall. */
        const val STALL_MS = 250L
    }
}

/**
 * A game session's log (any game: arcade alone or in a room, PSP), for the
 * owner to copy into a chat: what the phone is, the game and its settings,
 * a second-by-second speed timeline (full-speed stretches in one line, every
 * slow second with its frame count), every stall, and what was done (pause,
 * save, settings, the room…), each at its time into the session.
 */
class GameLog(private val now: () -> Long) {
    private val start = now()
    private val events = ArrayList<Pair<Long, String>>()

    @Synchronized
    fun event(text: String) {
        events += (now() - start) to text
        if (events.size > 2000) events.removeAt(0)
    }

    /** The whole log: [header] lines first (phone, app, game, settings), then [meter]'s timeline and stalls, then the events. */
    @Synchronized
    fun text(header: List<String>, meter: SpeedMeter, more: List<Pair<String, String>> = emptyList()): String = buildString {
        header.forEach { appendLine(it) }
        appendLine("Session: ${clock((now() - start) / 1000)}")
        appendLine("Full speed: ${meter.fullSpeedPercent}% of ${meter.seconds} s (slow ${meter.slowSeconds}), longest stall ${meter.longestStallMs} ms")
        appendLine()
        appendLine("Speed, second by second (counted since the last restart of the count):")
        timeline(meter.perSecond, meter.slowBelow).forEach { appendLine("  $it") }
        appendLine()
        val stalls = meter.stalls
        appendLine("Stalls over ${SpeedMeter.STALL_MS} ms: ${if (stalls.isEmpty()) "none" else stalls.size}")
        stalls.forEach { (sec, ms) -> appendLine("  at ${clock(sec.toLong())}: $ms ms") }
        appendLine()
        appendLine("What happened:")
        events.forEach { (ms, t) -> appendLine("  ${clock(ms / 1000)} $t") }
        // Other sections (a room's connection log), each under its title.
        for ((title, body) in more) {
            if (body.isBlank()) continue
            appendLine()
            appendLine(title)
            appendLine(body.trimEnd())
        }
    }

    companion object {
        fun clock(seconds: Long) = "${seconds / 60}:${"%02d".format(seconds % 60)}"

        /** Above this many frames a second the game runs fast (a story scene playing like fast-forward). */
        const val FAST_ABOVE = 62

        /**
         * Full-speed stretches as one line ("0:00-2:14 full speed"); slow and
         * fast seconds grouped with their frame counts.
         */
        fun timeline(perSecond: List<Int>, slowBelow: Int = SpeedMeter.SLOW_BELOW): List<String> {
            fun kind(fps: Int) = when {
                fps < slowBelow -> "slow"
                fps > FAST_ABOVE -> "fast"
                else -> "full speed"
            }
            val out = ArrayList<String>()
            var i = 0
            while (i < perSecond.size) {
                val k = kind(perSecond[i])
                var j = i
                while (j + 1 < perSecond.size && kind(perSecond[j + 1]) == k) j++
                val range = if (i == j) clock(i.toLong()) else "${clock(i.toLong())}-${clock(j.toLong())}"
                out += if (k == "full speed") "$range full speed" else "$range $k: ${perSecond.subList(i, j + 1).joinToString(", ")} fps"
                i = j + 1
            }
            return out
        }
    }
}

/** Every game's log: kept per game after quitting, copied or sent from the game's Settings. */
object GameLogs {
    /** The last session of [key] (an arcade game's id, "psp-" and a PSP file's name). */
    fun file(filesDir: File, key: String) = File(filesDir, "logs/$key.txt")

    fun save(file: File, text: String) {
        Thread { runCatching { file.parentFile?.mkdirs(); file.writeText(text) } }.start()
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Game log", text))
        Toast.makeText(context, "Game log copied: paste it in the chat", Toast.LENGTH_SHORT).show()
    }

    /** Sends [text] to our server, where the owner reads it ("Send to us"). */
    fun send(context: Context, game: String, text: String) {
        Toast.makeText(context, "Sending the game log…", Toast.LENGTH_SHORT).show()
        Online.sendGameLog(context, game, text) { error ->
            Toast.makeText(context, error?.let { "Couldn't send it: $it" } ?: "Game log sent: we'll read it", Toast.LENGTH_LONG).show()
        }
    }

    /** A session with a freeze this long (ms) sends its log by itself on leaving (problem reports on). */
    const val BAD_FREEZE_MS = 2_000L

    /** The app and the phone: the log's first lines. */
    fun phone(context: Context, screenHz: Int): List<String> {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val ram = runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            "%.1f GB RAM, %d MB free".format(mi.totalMem / 1e9, mi.availMem / 1_000_000)
        }.getOrDefault("RAM unknown")
        return listOf(
            "90s Kid Arcade ${BuildConfig.VERSION_NAME} · game log",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, $soc, Android ${Build.VERSION.RELEASE}, $ram, screen $screenHz Hz",
        )
    }

    /**
     * How the game runs: its frames' rate and how they're timed (with the
     * screen, or by the clock), frames that ran late and were made up, and
     * gaps in the sound, since it started.
     */
    fun playback(screenHz: Int): String {
        val s = com.swordfish.libretrodroid.LibretroDroid.getPlaybackStats()
        val rate = (s[3] / 10) / 100.0
        val ticks = (s[3] % 10).toInt()
        val pace = when {
            ticks == 1 -> "with the screen"
            ticks > 1 -> "with the screen, 1 in $ticks ticks"
            else -> "by the clock"
        }
        val overflow = if (s[2] > 0) ", ${s[2]} sound frames dropped" else ""
        return "%.2f frames a second, %s (%d Hz). Late frames made up: %d. Sound gaps: %d%s.".format(
            rate, pace, screenHz, s[0], s[1], overflow,
        )
    }

    /**
     * Once a second while the game's on screen: late frames made up and sound
     * gaps, into [log] when there were any (the frames themselves are counted
     * on the emulator's thread, [meter]).
     */
    fun watchPlayback(owner: androidx.lifecycle.LifecycleOwner, log: GameLog, meter: SpeedMeter) {
        owner.lifecycle.coroutineScope.launch {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                var last = com.swordfish.libretrodroid.LibretroDroid.getPlaybackStats()
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val now = com.swordfish.libretrodroid.LibretroDroid.getPlaybackStats()
                    val madeUp = now[0] - last[0]
                    val gaps = now[1] - last[1]
                    val dropped = now[2] - last[2]
                    if (madeUp > 0 || gaps > 0 || dropped > 0) {
                        log.event(
                            listOfNotNull(
                                if (madeUp > 0) "$madeUp late frames made up" else null,
                                if (gaps > 0) "$gaps sound gaps" else null,
                                if (dropped > 0) "$dropped sound frames dropped" else null,
                            ).joinToString(", ") + " (${meter.lastFps} fps)",
                        )
                    }
                    last = now
                }
            }
        }
    }

    /** The controls' settings in one line. */
    fun look(l: Look) =
        "stick ${l.stick.name.lowercase()}, size ${(l.size * 100).toInt()}%, see-through ${(l.opacity * 100).toInt()}%, " +
            "${if (l.leftHanded) "left" else "right"}-handed, sound ${if (l.sound) "${(l.volume * 100).toInt()}%" else "off"}, " +
            "move timing ${l.stepFrames}/${l.pressFrames} frames"
}
