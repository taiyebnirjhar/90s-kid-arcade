package com.swordfish.libretrodroid

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * Tells Android how long each frame's work takes (Android 12 on), so it
 * speeds the CPU up before frames run late. Most frames are light, and the
 * usual governor then slows the CPU (or moves the game to a small core);
 * a multiplayer frame that replays several (a rollback) is suddenly many
 * times the work, and would come too late, a hitch. With hints, Android
 * sees it coming and keeps up.
 */
internal class FrameHints(private val context: Context) {
    private var session: Any? = null
    private var thread = -1

    /** On the GL thread, after each frame: how long its work took. */
    fun report(workNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || workNanos <= 0) return
        runCatching { Api31.report(this, workNanos) }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { (session as? PerformanceHintManager.Session)?.close() }
        session = null
        thread = -1
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun report(hints: FrameHints, workNanos: Long) {
            val me = Process.myTid()
            // The GL thread is new when the view is (attached again).
            if (me != hints.thread) {
                hints.close()
                hints.thread = me
                hints.session = hints.context.getSystemService(PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(me), TARGET_NANOS)
            }
            (hints.session as? PerformanceHintManager.Session)?.reportActualWorkDuration(workNanos)
        }
    }

    private companion object {
        // A frame is 16.7 ms; its work should be done well within it, with
        // room for a frame made up after one ran late.
        const val TARGET_NANOS = 10_000_000L
    }
}
