package com.kofarcade.core.net

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.random.Random

/**
 * For tests: how one phone's network misbehaves, as Wi-Fi does. Every room
 * a phone opens or joins shares its [Faults] (a phone that rejoins is still
 * on the same bad Wi-Fi). The app never sets any: all off, nothing changes.
 */
class Faults {
    /** Cut off: nothing gets in or out, as when the Wi-Fi drops. */
    @Volatile var cut = false
        internal set

    /** What a cut does to the phone's TCP connections. */
    @Volatile var tcp = TcpCut.DROP

    /** Each UDP packet, in or out, is lost with this chance. */
    @Volatile var udpLoss = 0.0

    /** Each UDP packet out waits this long, plus up to [udpJitterMs] more (so some overtake others). */
    @Volatile var udpDelayMs = 0
    @Volatile var udpJitterMs = 0

    private val random = Random(System.nanoTime())

    internal fun lose(): Boolean {
        val p = udpLoss
        return p > 0 && synchronized(random) { random.nextDouble() } < p
    }

    internal fun delayMs(): Long {
        val d = udpDelayMs
        val j = udpJitterMs
        if (d <= 0 && j <= 0) return 0
        return d + if (j > 0) synchronized(random) { random.nextInt(j + 1) }.toLong() else 0
    }

    /** TCP while [cut]: writes and reads stall (the connection lives on) unless [TcpCut.DROP]. */
    internal val stalls: Boolean get() = cut && tcp != TcpCut.DROP

    /** While [cut], what's written is lost and what arrives is ignored (the old, simple model). */
    internal val drops: Boolean get() = cut && tcp == TcpCut.DROP

    /** Waits while the TCP connections stall, until [closed]. */
    internal fun waitWhileStalled(closed: () -> Boolean) {
        while (stalls && !closed()) Thread.sleep(5)
    }

    internal companion object {
        /** Late UDP packets go out from here. */
        val late: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "net-faults").apply { isDaemon = true } }
        }
    }
}

/** What a phone's TCP connections do while its network is cut. */
enum class TcpCut {
    /** What's written is lost, what arrives is ignored. */
    DROP,

    /** Nothing moves, then everything does: a Wi-Fi hiccup the connections live through. */
    STALL,

    /** Android destroys the phone's connections the moment its Wi-Fi goes. */
    RESET,
}
