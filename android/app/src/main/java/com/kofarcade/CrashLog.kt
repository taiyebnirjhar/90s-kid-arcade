package com.kofarcade

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Starts the crash log with the app. */
class ArcadeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // How the app last closed, if Android closed it (a crash in the emulators'
        // own code, a freeze, too little memory): sent to us with the last game log.
        Thread({ runCatching { ExitReport.check(this) } }, "exit-report").apply { isDaemon = true }.start()
    }
}

/**
 * If the app crashes, the error is saved here and offered (to share) the
 * next time the app opens, instead of only Android's "keeps stopping".
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText(
                    "90s Kid Arcade crash\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                        "Thread: ${thread.name}\n\n${error.stackTraceToString()}",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash's report, once (it's deleted when read). */
    fun take(context: Context): String? {
        val f = file(context)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}

/**
 * Why Android closed the app last time (Android 11 and later keep this):
 * a crash in native code (an emulator core: the Java handler above never
 * sees those), a freeze (ANR), or too little memory. Found once on the next
 * start and sent to us with the newest game log, with problem reports on
 * (the owner, 2026-11-08: "Make sure it never crush again": so each one
 * that happens can be found and fixed).
 */
object ExitReport {
    private const val SEEN = "exit.lastSeen"

    fun check(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val prefs = app.getSharedPreferences("exit", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5).filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        prefs.edit().putLong(SEEN, exits.maxOf { it.timestamp }).apply()
        // The first time this runs, the older ones were before it existed: only note where things are.
        if (seen == 0L) return
        val bad = exits.filter { it.reason in BAD }.maxByOrNull { it.timestamp } ?: return
        val text = buildString {
            appendLine("90s Kid Arcade ${BuildConfig.VERSION_NAME} · how the app last closed")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("Reason: ${name(bad.reason)}${bad.description?.let { " ($it)" } ?: ""}")
            appendLine("When: ${java.util.Date(bad.timestamp)}")
            appendLine("On screen: ${bad.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND}")
            appendLine("Memory: ${bad.pss / 1024} MB (pss), ${bad.rss / 1024} MB (rss)")
            // A freeze's report is text (the threads at the time); a native crash's is a binary tombstone, left out.
            if (bad.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching { bad.traceInputStream?.use { input -> readUpTo(input, 60_000) } }.getOrNull()?.let {
                    appendLine()
                    appendLine("Threads at the freeze:")
                    appendLine(it)
                }
            }
            // The newest game log: the session it closed in.
            val last = File(app.filesDir, "logs").listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            if (last != null) {
                appendLine()
                appendLine("The last game log (${last.name}):")
                append(runCatching { last.readText() }.getOrDefault("").takeLast(100_000))
            }
        }
        Online.sendGameLog(app, "app closed: ${name(bad.reason)}", text, auto = true)
    }

    // InputStream.readNBytes is Android 13 and later only.
    private fun readUpTo(input: java.io.InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private val BAD = setOf(
        android.app.ApplicationExitInfo.REASON_CRASH,
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
        android.app.ApplicationExitInfo.REASON_ANR,
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    private fun name(reason: Int) = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in an emulator (native)"
        android.app.ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for memory"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too much"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "couldn't start"
        else -> "reason $reason"
    }
}
