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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Downloads the app's update ([AppUpdates.runDownload]) as a foreground
 * service, as [DownloadService] does the games: Android lets it go on with
 * the app in the background or the screen off, its progress in a
 * notification. Done: the installer opens if the app's on screen, else a
 * notification offers it. Every download in the app works this way (see
 * docs/AGENTS.md, "Downloads").
 */
class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        val first = progress(AppUpdates.downloading?.name, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING, first)
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "90s-kid-arcade:update").apply { acquire(MAX_HOLD_MS) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "90s-kid-arcade:update").apply { runCatching { acquire() } }
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            AppUpdates.state.collect { s ->
                if (s is UpdateState.Downloading) manager.notify(ONGOING, progress(s.update.name, (s.fraction * 100).toInt()))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Thread({
                val ok = AppUpdates.runDownload(applicationContext)
                Handler(Looper.getMainLooper()).post { done(ok) }
            }, "update-download").start()
        }
        return START_NOT_STICKY
    }

    private fun done(ok: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        // The app on screen (the installer can open over it); in the background, Android wouldn't let it.
        val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        val onScreen = me.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        if (ok && onScreen) {
            AppUpdates.install(this)
        } else {
            val s = AppUpdates.state.value
            manager.notify(
                DONE,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setContentTitle(if (ok) "Update ready to install" else "The update didn't download")
                    .setContentText(if (ok) "Tap to install 90s Kid Arcade ${(s as? UpdateState.Ready)?.update?.name.orEmpty()}" else "Open the app and tap Try again")
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

    private fun progress(name: String?, pct: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading the update${name?.let { " $it" }.orEmpty()}")
            .setContentText("$pct%")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()

    // Opening the app shows the update card: Install (or Try again).
    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL = "update"
        private const val ONGOING = 51
        private const val DONE = 52
        private const val MAX_HOLD_MS = 60 * 60 * 1000L

        fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "A new version of 90s Kid Arcade downloading"
                    },
                )
            }
        }

        /** Starts it (from the app on screen: the Update button). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java)) }
                .onFailure {
                    // Couldn't start (rare): the download runs here instead, as it did before.
                    Thread({ AppUpdates.runDownload(context) }, "update-download").start()
                }
        }
    }
}
