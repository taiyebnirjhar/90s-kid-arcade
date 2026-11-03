package com.kofarcade

import android.content.Context
import com.kofarcade.core.replay.MatchFile
import com.kofarcade.core.replay.MatchInfo
import com.kofarcade.core.replay.MatchShelf
import com.kofarcade.core.replay.Recording
import com.kofarcade.core.replay.SavedMatch
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File

/**
 * The matches kept on this phone (docs/recording-plan.md), one file each in
 * the app's own storage: the newest [MatchShelf.KEEP], and starred ones for good.
 */
class MatchStore(context: Context) {
    val dir: File = File(context.filesDir, "matches").apply { mkdirs() }
    private val starred = File(dir, "starred.txt")

    fun list(): List<SavedMatch> {
        val stars = starredNames()
        return dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty()
            .map { SavedMatch(it.name, it.lastModified(), it.name in stars) }
            .sortedByDescending { it.savedAt }
    }

    /** Kept (written whole, then put in place), and the oldest past [MatchShelf.KEEP] let go. */
    fun save(info: MatchInfo, rec: Recording) {
        val bytes = MatchFile.write(info, rec)
        val name = "${info.startedAt}$SUFFIX"
        val part = File(dir, "$name.part")
        part.writeBytes(bytes)
        if (!part.renameTo(File(dir, name))) part.delete()
        prune()
    }

    fun prune() {
        for (m in MatchShelf.overflow(list())) File(dir, m.name).delete()
    }

    /** The space all saved matches take, in bytes. */
    fun bytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun star(name: String, on: Boolean) {
        val now = starredNames().let { if (on) it + name else it - name }
        starred.writeText(now.joinToString("\n"))
    }

    private fun starredNames(): Set<String> =
        if (starred.exists()) starred.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    companion object {
        const val SUFFIX = ".match"
    }
}

/**
 * Records the game while saving matches is on: started once the game runs,
 * and each recording kept as it ends (a state loaded, a reset, a new seating
 * in a room, leaving the game), then the next one started. Called on a
 * background thread ([tick] about once a second; [finish] when leaving).
 */
class MatchTaker(
    private val store: MatchStore,
    private val enabled: () -> Boolean,
    /** The game is running and may be recorded now (not while a room seats everyone). */
    private val canStart: () -> Boolean,
    /** What's known about the match now (its game, the room, who's playing). */
    private val describe: (startedAt: Long, frames: Int) -> MatchInfo,
) {
    private var startedAt = 0L

    @Synchronized
    fun tick() {
        val status = LibretroDroid.recStatus()
        if (!enabled()) {
            // Turned off: whatever was being recorded is dropped.
            if (status != NONE) LibretroDroid.recTake()
            return
        }
        when (status) {
            ENDED -> {
                keep(LibretroDroid.recTake())
                start()
            }
            NONE -> start()
        }
    }

    /** Leaving the game: what's recorded is taken now (before the emulator goes) and kept in the background. */
    fun finish() {
        val block = synchronized(this) { if (LibretroDroid.recStatus() != NONE) LibretroDroid.recTake() else null } ?: return
        Thread { keep(block) }.start()
    }

    private fun start() {
        if (!canStart()) return
        if (LibretroDroid.recStart()) startedAt = System.currentTimeMillis()
    }

    private fun keep(block: ByteArray) {
        val rec = Recording.parse(block) ?: return
        if (rec.frames < MatchShelf.MIN_FRAMES) return
        runCatching { store.save(describe(startedAt, rec.frames), rec) }
    }

    private companion object {
        const val NONE = 0
        const val ENDED = 2
    }
}
