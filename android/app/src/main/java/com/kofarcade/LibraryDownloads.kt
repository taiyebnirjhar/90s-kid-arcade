package com.kofarcade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * One library file's download, as every screen sees it: waiting its turn
 * ([queued]), bytes so far, then what the check found ([result]).
 */
data class Download(
    val done: Long = 0,
    val result: String? = null,
    val ok: Boolean = false,
    val retry: Boolean = false,
    val queued: Boolean = false,
)

/**
 * The library's downloads, one after another, apart from any screen: leaving
 * the Library page (or the app) doesn't stop them; only [cancelAll] does.
 * Every file is fetched whole ([fetch] checks its size, picking up a dropped
 * connection), then checked like an import ([install]); a file that didn't
 * come whole is never shown as on the phone. Plain Kotlin, so it's tested
 * without a phone (LibraryDownloadsTest); [LibraryDownloads] runs it in the app.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    /** Fetches a file whole to the given file (throws if it can't). */
    private val fetch: (entry: Library.Entry, to: File, cancelled: () -> Boolean, progress: (Long) -> Unit) -> Unit,
    /** A scratch file for one download. */
    private val scratch: () -> File,
    /** Checks and stores a fetched file: what to say, and whether it's a game (or the BIOS) now on the phone. */
    private val install: (entry: Library.Entry, file: File) -> Pair<String, Boolean>,
    /** Whole tries per file, when a download fails partway. */
    private val tries: Int = 3,
    private val retryMs: Long = 2_000,
) {
    private val _state = MutableStateFlow<Map<String, Download>>(emptyMap())
    /** Each file's download (by its name in the library). */
    val state: StateFlow<Map<String, Download>> = _state.asStateFlow()

    private val _active = MutableStateFlow(false)
    /** Downloads are running (or waiting their turn). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _installed = MutableStateFlow(0)
    /** Goes up each time a file lands on the phone (the home screen looks again). */
    val installed: StateFlow<Int> = _installed.asStateFlow()

    private val waiting = ArrayDeque<Library.Entry>()
    @Volatile private var worker: Job? = null
    /** The file being downloaded now, and its size. */
    @Volatile var current: Library.Entry? = null
        private set

    /** Files waiting, the one downloading now included. */
    val left: Int get() = synchronized(waiting) { waiting.size } + (if (current != null) 1 else 0)

    /** Adds [entries] to the queue (the BIOS first, so the games can be played as soon as they land); ones already in it stay once. */
    fun enqueue(entries: List<Library.Entry>) {
        val fresh = synchronized(waiting) {
            val have = waiting.map { it.name }.toSet() + listOfNotNull(current?.name)
            entries.filter { it.name !in have }
                .sortedBy { if (com.kofarcade.core.setName(it.name).startsWith("neogeo")) 0 else 1 }
                .also { waiting.addAll(it) }
        }
        if (fresh.isEmpty()) return
        _state.update { s -> s + fresh.associate { it.name to Download(queued = true) } }
        _active.value = true
        // A worker cancelled a moment ago may still be finishing: a new one takes over.
        synchronized(waiting) {
            val w = worker
            if (w == null || w.isCancelled || w.isCompleted) worker = scope.launch { run() }
        }
    }

    /** Stops everything: the file downloading now and the ones waiting can be got again. */
    fun cancelAll() {
        val stopped = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        worker?.cancel()
        _state.update { s -> s + stopped.associate { it.name to Download(0, "Cancelled. Tap to try again.", retry = true) } }
        if (worker == null) _active.value = false
    }

    private suspend fun run() {
        val me = coroutineContext[Job]
        try {
            while (coroutineContext.isActive) {
                // Taking the next and finding none, under the lock: a file added
                // meanwhile starts a new worker instead of waiting for ever.
                val entry = synchronized(waiting) {
                    waiting.removeFirstOrNull().also {
                        if (it == null && worker === me) {
                            worker = null
                            _active.value = false
                        }
                    }
                } ?: return
                current = entry
                try {
                    get(entry)
                } finally {
                    current = null
                }
            }
        } finally {
            // Cancelled: unless a new worker took over meanwhile.
            synchronized(waiting) {
                if (worker === me) {
                    worker = null
                    _active.value = false
                }
            }
        }
    }

    private suspend fun get(entry: Library.Entry) {
        _state.update { it + (entry.name to Download()) }
        var attempt = 0
        while (true) {
            // Inside the try: a scratch file that can't be made (the phone's full) fails this one, not the queue.
            var tmp: File? = null
            try {
                tmp = scratch()
                // The card moves by whole percents: not a redraw of the page per 64 KB.
                var shown = -1L
                runInterruptible {
                    fetch(entry, tmp!!, { Thread.currentThread().isInterrupted }) { got ->
                        val pct = if (entry.size > 0) got * 100 / entry.size else got shr 20
                        if (pct != shown) {
                            shown = pct
                            _state.update { it + (entry.name to Download(got)) }
                        }
                    }
                }
                val (message, ok) = runInterruptible { install(entry, tmp!!) }
                _state.update { it + (entry.name to Download(entry.size, message, ok = ok)) }
                if (ok) _installed.update { it + 1 }
                return
            } catch (e: CancellationException) {
                tmp?.delete()
                _state.update { it + (entry.name to Download(0, "Cancelled. Tap to try again.", retry = true)) }
                throw e
            } catch (e: Exception) {
                tmp?.delete()
                if (++attempt < tries && e !is Library.WrongKey) {
                    delay(retryMs * attempt)
                    continue
                }
                val why = e.message ?: e.javaClass.simpleName
                _state.update { it + (entry.name to Download(0, "Download failed: $why. Tap to try again.", retry = true)) }
                return
            }
        }
    }
}
