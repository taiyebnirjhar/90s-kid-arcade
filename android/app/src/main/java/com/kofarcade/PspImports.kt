package com.kofarcade

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * Adding a PSP game from the phone's files (a .7z of a GB or two takes a
 * minute or more to unpack), for the whole app: it goes on after the PSP
 * page is left or the app goes to the background, held up by
 * [PspImportService] and its notification. The page shows [state].
 */
object PspImports {
    /** The game being added and how far ([bytes] written), or how the last one went ([result]). */
    data class Import(val name: String, val bytes: Long = 0, val result: String? = null, val ok: Boolean = false) {
        val running: Boolean get() = result == null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Import?>(null)
    val state: StateFlow<Import?> = _state

    /** Adds the game at [uri] ([name] its file name); false if one's being added already. */
    fun start(context: Context, uri: Uri, name: String): Boolean {
        if (_state.value?.running == true) return false
        val app = context.applicationContext
        _state.value = Import(name)
        PspImportService.start(app)
        scope.launch {
            val result = runCatching {
                app.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    PspGames.import(
                        name, File(app.filesDir, "psp/games"),
                        open = { app.contentResolver.openInputStream(uri)!! },
                        channel = { FileInputStream(pfd.fileDescriptor).channel },
                        progress = { b -> _state.value = Import(name, b) },
                    )
                }
            }
            _state.value = result.fold(
                { Import(name, _state.value?.bytes ?: 0, "Added ${PspGames.title(it)}", ok = true) },
                { Import(name, _state.value?.bytes ?: 0, it.message ?: "Couldn't add $name") },
            )
        }
        return true
    }

    /** The page has shown how it went. */
    fun clear() {
        if (_state.value?.running == false) _state.value = null
    }
}

/**
 * Keeps [PspImports] going with the app in the background (a foreground
 * service with a notification: the game and the MB so far), the CPU held
 * awake meanwhile; says how it went and stops once it's done.
 */
class PspImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadService.channel(this)
        val first = notification("Adding a PSP game…", "Starting…", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING, first)
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "90s-kid-arcade:psp-import").apply { acquire(MAX_HOLD_MS) }
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            var lastMb = -1L
            PspImports.state.collect { s ->
                when {
                    s == null -> done(null)
                    !s.running -> done(s)
                    s.bytes / 50_000_000 != lastMb -> {
                        // Every 50 MB: often enough to see it move, not a notification a second.
                        lastMb = s.bytes / 50_000_000
                        manager.notify(ONGOING, notification("Adding ${s.name}", "${s.bytes / 1_000_000} MB unpacked", ongoing = true))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun done(s: PspImports.Import?) {
        if (s != null) {
            getSystemService(NotificationManager::class.java).notify(
                DONE,
                notification(if (s.ok) "PSP game ready to play" else "PSP game not added", s.result.orEmpty(), ongoing = false),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }

    private fun notification(title: String, text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, DownloadService.CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 1, Intent(this, PspActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()

    companion object {
        private const val ONGOING = 43
        private const val DONE = 44
        private const val MAX_HOLD_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PspImportService::class.java)) }
                .onFailure { android.util.Log.w("PspImport", "Couldn't start the service", it) }
        }
    }
}
