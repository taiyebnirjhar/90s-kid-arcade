package com.kofarcade.core.net

// The few java.util.concurrent pieces the room code uses, on [Monitor].

/** Runs tasks one at a time on a thread of its own, now or after a delay. */
class TaskQueue(name: String) {
    private val monitor = Monitor()
    private val tasks = ArrayList<Pair<Long, () -> Unit>>()
    private var stopped = false

    init {
        startThread(name) {
            while (true) {
                val task = monitor.locked {
                    while (!stopped) {
                        val now = nanoTime()
                        val next = tasks.minByOrNull { it.first }
                        if (next != null && next.first <= now) {
                            tasks.remove(next)
                            return@locked next.second
                        }
                        val waitMs = if (next == null) 1000 else ((next.first - now) / 1_000_000).coerceIn(1, 1000)
                        monitor.await(waitMs)
                    }
                    null
                } ?: break
                runCatching(task)
            }
        }
    }

    /** Throws once stopped, as an executor does. */
    fun execute(task: () -> Unit) = schedule(0, task)

    fun schedule(delayMs: Long, task: () -> Unit) = monitor.locked {
        check(!stopped) { "Stopped" }
        tasks += (nanoTime() + delayMs * 1_000_000) to task
        monitor.signalAll()
    }

    /** Drops what's waiting; the task running now finishes. */
    fun shutdownNow() = monitor.locked {
        stopped = true
        tasks.clear()
        monitor.signalAll()
    }
}

/** A one-time gate: [await] until [countDown]. */
class Latch {
    private val monitor = Monitor()
    private var open = false

    fun countDown() = monitor.locked {
        open = true
        monitor.signalAll()
    }

    /** True if opened within [ms]. */
    fun await(ms: Long): Boolean = monitor.locked {
        val until = nanoTime() + ms * 1_000_000
        while (!open) {
            val left = (until - nanoTime()) / 1_000_000
            if (left <= 0) return@locked false
            monitor.await(left)
        }
        true
    }
}

/** A queue a thread can wait on. */
class BlockingQueue<T : Any> {
    private val monitor = Monitor()
    private val items = ArrayDeque<T>()

    fun add(item: T) = monitor.locked {
        items.addLast(item)
        monitor.signalAll()
    }

    /** The next item, waiting up to [ms] for one; null if none came. */
    fun poll(ms: Long): T? = monitor.locked {
        val until = nanoTime() + ms * 1_000_000
        while (items.isEmpty()) {
            val left = (until - nanoTime()) / 1_000_000
            if (left <= 0) return@locked null
            monitor.await(left)
        }
        items.removeFirst()
    }

    fun clear() = monitor.locked { items.clear() }

    val size: Int get() = monitor.locked { items.size }
}

/** A boolean several threads flip. */
class Flag(initial: Boolean = false) {
    private val monitor = Monitor()
    private var value = initial

    fun get(): Boolean = monitor.locked { value }

    fun set(v: Boolean) = monitor.locked { value = v }

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = monitor.locked {
        if (value != expect) return@locked false
        value = update
        true
    }
}

/** A number several threads count up. */
class Counter {
    private val monitor = Monitor()
    private var value = 0

    fun incrementAndGet(): Int = monitor.locked { ++value }

    fun get(): Int = monitor.locked { value }
}

/** Waits for [count] things to finish ([done] each). */
class Countdown(private var count: Int) {
    private val monitor = Monitor()

    fun done() = monitor.locked {
        count--
        monitor.signalAll()
    }

    /** True if all finished within [ms]. */
    fun await(ms: Long): Boolean = monitor.locked {
        val until = nanoTime() + ms * 1_000_000
        while (count > 0) {
            val left = (until - nanoTime()) / 1_000_000
            if (left <= 0) return@locked false
            monitor.await(left)
        }
        true
    }
}

/** A map several threads use. */
class SyncMap<K, V> {
    private val monitor = Monitor()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = monitor.locked { map[key] }

    operator fun set(key: K, value: V) = monitor.locked { map[key] = value }

    fun remove(key: K): V? = monitor.locked { map.remove(key) }

    fun clear() = monitor.locked { map.clear() }
}

/** A set several threads use. */
class SyncSet<T> {
    private val monitor = Monitor()
    private val set = HashSet<T>()

    fun add(item: T): Boolean = monitor.locked { set.add(item) }

    fun remove(item: T): Boolean = monitor.locked { set.remove(item) }
}

/** `synchronized` for code shared with iPhone: on a [Monitor]. */
inline fun <T> synchronized(lock: Monitor, block: () -> T): T = lock.locked(block)
