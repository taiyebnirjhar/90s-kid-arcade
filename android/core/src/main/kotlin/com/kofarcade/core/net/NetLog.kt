package com.kofarcade.core.net

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened to this phone's multiplayer, newest last: who came and went,
 * drops, reconnects, host changes, games put back in step, and every
 * network error. Kept across rooms (a phone that rejoins is in a new one),
 * for the pause menu's connection report.
 */
object NetLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** For tests: several phones in one process, each line says whose it is; and lines are echoed here. */
    @Volatile var echo: ((String) -> Unit)? = null

    fun add(text: String) {
        echo?.invoke(text)
        val line = synchronized(time) { time.format(Date()) } + "  " + text
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }
}
