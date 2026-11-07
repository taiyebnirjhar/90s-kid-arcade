package com.kofarcade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameForSet
import com.kofarcade.core.setName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The app's one download queue ([DownloadQueue]), for the whole app: the
 * Library page shows it and adds to it, and [DownloadService] keeps it going
 * with the app in the background or closed.
 */
object LibraryDownloads {
    @Volatile private var queue: DownloadQueue? = null

    fun get(context: Context): DownloadQueue =
        queue ?: synchronized(this) { queue ?: create(context.applicationContext).also { queue = it } }

    private fun create(app: Context): DownloadQueue {
        val prefs = Prefs(app)
        val roms = RomStore(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val q = DownloadQueue(
            scope,
            fetch = { entry, to, cancelled, progress ->
                // A PSP game from the owner's Google Drive folder, or an arcade one from the library.
                val drive = DriveFolder.parseEntry(entry.name)
                if (drive != null) {
                    DriveFolder.download(drive.first, to, cancelled, progress)
                } else {
                    Library(prefs.libraryUrl, prefs.libraryKey.orEmpty()).download(entry.name, to, entry.size, cancelled, progress)
                }
            },
            scratch = { roms.partFile("library") },
            install = { entry, file ->
                DriveFolder.parseEntry(entry.name)?.let { (_, name) -> return@DownloadQueue installPsp(app, name, file) }
                val check = roms.add(file)
                val set = when (check) {
                    RomCheck.Bios -> "neogeo"
                    is RomCheck.Game -> check.set.name
                    else -> null
                }
                prefs.setLibrarySet(entry.name, set)
                check.message() to (set != null)
            },
        )
        // While anything downloads, the service holds the app up (and shows it in a notification).
        scope.launch(Dispatchers.Main) { q.active.collect { if (it) DownloadService.start(app) } }
        return q
    }

    /** A PSP game from Drive into the PSP games (a .7z or .zip unpacked on the way). */
    private fun installPsp(app: Context, name: String, file: File): Pair<String, Boolean> = runCatching {
        val game = PspGames.import(
            name, File(app.filesDir, "psp/games"),
            open = { file.inputStream() },
            channel = { java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ) },
        )
        "Added ${PspGames.title(game)}" to true
    }.getOrElse { e -> (e.message ?: "Couldn't add $name") to false }

    /** A library file's name as people know it: the game's title, else the file. */
    fun title(name: String): String {
        DriveFolder.parseEntry(name)?.let { (_, file) -> return file.substringBeforeLast('.').replace('_', ' ').removeSuffix(".zip") }
        val set = setName(name).replace(Regex(""" \(\d+\)$"""), "")
        return if (set.startsWith("neogeo")) "Neo Geo BIOS" else gameForSet(set)?.fullTitle ?: name.removeSuffix(".zip")
    }
}

/**
 * Keeps the library's downloads going with the app in the background or
 * closed (a foreground service: Android lets it run, with its notification):
 * the game downloading now, how far, how many are left, and Cancel. Holds
 * the CPU and Wi-Fi awake meanwhile, so a screen turned off doesn't stall
 * them; stops by itself once the queue's empty.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var landed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        val first = notification("Getting your games…", null, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING, first)
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "90s-kid-arcade:downloads").apply { acquire(MAX_HOLD_MS) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "90s-kid-arcade:downloads").apply { runCatching { acquire() } }
        val q = LibraryDownloads.get(this)
        val startedAt = q.installed.value
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(q.state, q.active) { s, a -> s to a }.collect { (s, active) ->
                landed = q.installed.value - startedAt
                if (!active) {
                    finish(s)
                    return@collect
                }
                val now = q.current
                val got = now?.let { s[it.name]?.done } ?: 0L
                val pct = if (now != null && now.size > 0) (got * 100 / now.size).toInt() else 0
                manager.notify(ONGOING, notification(now?.let { LibraryDownloads.title(it.name) } ?: "Getting your games…", now, pct, q.left))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) LibraryDownloads.get(this).cancelAll()
        // Not brought back by Android after a kill: the queue's gone with the process.
        return START_NOT_STICKY
    }

    // The queue's empty: what came, then the service goes.
    private fun finish(state: Map<String, Download>) {
        val failed = state.values.count { it.retry }
        val manager = getSystemService(NotificationManager::class.java)
        if (landed > 0 || failed > 0) {
            val text = buildString {
                if (landed > 0) append(if (landed == 1) "1 game ready to play" else "$landed games ready to play")
                if (failed > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(if (failed == 1) "1 didn't come: try it again in My Library" else "$failed didn't come: try them again in My Library")
                }
            }
            manager.notify(
                DONE,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(if (failed > 0 && landed == 0) "Downloads stopped" else "Downloads done")
                    .setContentText(text)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { if (wifi?.isHeld == true) wifi?.release() }
        super.onDestroy()
    }

    private fun notification(title: String, entry: Library.Entry?, pct: Int, left: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                when {
                    entry == null -> "Starting…"
                    left > 1 -> "$pct% · ${left - 1} more after this"
                    else -> "$pct%"
                },
            )
            .setProgress(100, pct, entry == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL = "downloads"
        private const val ONGOING = 41
        private const val DONE = 42
        private const val ACTION_CANCEL = "com.kofarcade.CANCEL_DOWNLOADS"
        // A wake lock never outlives a stuck download for long.
        private const val MAX_HOLD_MS = 3 * 60 * 60 * 1000L

        fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Games downloading from your library"
                    },
                )
            }
        }

        /** Starts it (from the app on screen; Android doesn't allow it from the background). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { android.util.Log.w("Downloads", "Couldn't start the download service", it) }
        }
    }
}
