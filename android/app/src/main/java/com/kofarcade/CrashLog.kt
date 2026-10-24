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
