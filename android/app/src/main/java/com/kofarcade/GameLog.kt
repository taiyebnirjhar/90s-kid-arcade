package com.kofarcade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
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

    /** A frame came out at [nowMs]. */
    @Synchronized
    fun frame(nowMs: Long) {
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

        /** Full-speed stretches as one line ("0:00-2:14 full speed"), slow seconds grouped with their frame counts. */
        fun timeline(perSecond: List<Int>, slowBelow: Int = SpeedMeter.SLOW_BELOW): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < perSecond.size) {
                val slow = perSecond[i] < slowBelow
                var j = i
                while (j + 1 < perSecond.size && (perSecond[j + 1] < slowBelow) == slow) j++
                val range = if (i == j) clock(i.toLong()) else "${clock(i.toLong())}-${clock(j.toLong())}"
                out += if (slow) "$range slow: ${perSecond.subList(i, j + 1).joinToString(", ")} fps" else "$range full speed"
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

    fun send(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "90s Kid Arcade game log")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, "Send the game log")) }
    }

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

    /** The controls' settings in one line. */
    fun look(l: Look) =
        "stick ${l.stick.name.lowercase()}, size ${(l.size * 100).toInt()}%, see-through ${(l.opacity * 100).toInt()}%, " +
            "${if (l.leftHanded) "left" else "right"}-handed, sound ${if (l.sound) "${(l.volume * 100).toInt()}%" else "off"}, " +
            "move timing ${l.stepFrames}/${l.pressFrames} frames"
}
