package com.kofarcade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the update stands, for the home screen's chip, card and sheet. */
sealed interface UpdateState {
    /** Nothing newer (or not checked yet). */
    data object None : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val fraction: Float) : UpdateState
    /** Downloaded whole: Android's installer is next. */
    data class Ready(val update: AppUpdate, val file: File) : UpdateState
    /** Android needs the player to allow this app to install updates (once). */
    data class NeedsPermission(val update: AppUpdate, val file: File) : UpdateState
    data class Failed(val update: AppUpdate, val why: String) : UpdateState
}

/**
 * Updates inside the app: the website says what its newest version is
 * (version.json, written when it's published); a newer one shows on the home
 * screen, downloads with its progress, and opens Android's installer. The
 * APK's signed with the same key every build, so it installs over this one,
 * keeping every game and setting.
 */
object AppUpdates {
    private const val VERSION_URL = "https://arcade.taiyebnirjhor.com/version.json"
    // Asked every time the app comes back (no more than once a minute) and
    // every 2 minutes while it's open: the owner wanted the Update button
    // without closing and opening the app. version.json is a few hundred
    // bytes on the website (GitHub Pages), so this costs nothing.
    private const val CHECK_EVERY_MS = 60 * 1000L

    /** While the app is open: asked again this often. */
    const val CHECK_OPEN_MS = 2 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var checkedAt = 0L
    @Volatile private var busy = false

    /** Asks the website (off the main thread) unless it was asked a moment ago or [force]. */
    fun check(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < CHECK_EVERY_MS) return
        if (_state.value is UpdateState.Downloading) return
        checkedAt = now
        Thread({
            val found = runCatching { AppUpdate.parse(get(VERSION_URL)) }.getOrNull()
            if (found != null && found.newerThan(BuildConfig.VERSION_CODE)) {
                // Already downloaded on an earlier go: straight to installing.
                val file = apkFile(context, found)
                val keep = _state.value
                if (keep is UpdateState.Ready || keep is UpdateState.NeedsPermission || keep is UpdateState.Downloading) return@Thread
                _state.value = if (file.isFile && file.length() > 0) UpdateState.Ready(found, file) else UpdateState.Available(found)
            } else if (found != null) {
                _state.value = UpdateState.None
                cleanUp(context, keep = null)
            }
        }, "update-check").apply { isDaemon = true }.start()
    }

    /**
     * Downloads the update (its progress in [state]) in [UpdateService], so
     * it goes on with the app in the background or the screen off; then the
     * installer opens (or, from the background, a notification offers it).
     */
    fun download(context: Context) {
        val update = when (val s = _state.value) {
            is UpdateState.Available -> s.update
            is UpdateState.Failed -> s.update
            is UpdateState.Ready -> return install(context)
            is UpdateState.NeedsPermission -> return install(context)
            else -> return
        }
        if (busy) return
        _state.value = UpdateState.Downloading(update, 0f)
        UpdateService.start(context.applicationContext)
    }

    /** The update being downloaded, or null. */
    val downloading: AppUpdate? get() = (_state.value as? UpdateState.Downloading)?.update

    /**
     * The download itself, on the caller's thread ([UpdateService]'s): a
     * dropped connection is tried again a few times, carrying on from where
     * it stopped (a Range request), never from the start. True: it's in.
     */
    fun runDownload(app: Context): Boolean {
        val update = downloading ?: return false
        if (busy) return false
        busy = true
        val file = apkFile(app, update)
        val part = File(file.parentFile, file.name + ".part")
        try {
            cleanUp(app, keep = update)
            var tries = 0
            while (true) {
                try {
                    fetch(update.apk, part) { got, size ->
                        if (size > 0) _state.value = UpdateState.Downloading(update, got.toFloat() / size)
                    }
                    break
                } catch (e: IOException) {
                    if (++tries >= RETRIES) throw e
                    Thread.sleep(2_000L shl (tries - 1))
                }
            }
            if (!part.renameTo(file)) throw IOException("Couldn't keep the download")
            _state.value = UpdateState.Ready(update, file)
            return true
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(update, "The download stopped (${e.message ?: "no connection"}). Try again.")
            return false
        } finally {
            busy = false
        }
    }

    /**
     * Opens Android's installer for the downloaded update. The first time,
     * Android wants the player to allow this app to install apps: their
     * settings open on that switch, and coming back, it's tapped again.
     */
    fun install(context: Context) {
        val (update, file) = when (val s = _state.value) {
            is UpdateState.Ready -> s.update to s.file
            is UpdateState.NeedsPermission -> s.update to s.file
            else -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update, file)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        _state.value = UpdateState.Ready(update, file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { _state.value = UpdateState.Failed(update, "Android's installer didn't open. Get it from the website instead.") }
    }

    /** Back from settings (the install switch): if it's on now, the installer opens. */
    fun resumed(context: Context) {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())) {
            install(context)
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, update: AppUpdate) = File(dir(context), "90s-Kid-Arcade-${update.code}.apk")

    // Old downloads go (only the one being got stays).
    private fun cleanUp(context: Context, keep: AppUpdate?) {
        dir(context).listFiles()?.forEach { f -> if (keep == null || !f.name.startsWith("90s-Kid-Arcade-${keep.code}.")) f.delete() }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        try {
            if (conn.responseCode != 200) throw IOException("The site answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // The APK, whole: from where [to] got to before (a Range request; the
    // whole file again if the site won't), its length checked against what
    // the site said it is.
    private fun fetch(url: String, to: File, progress: (Long, Long) -> Unit) {
        val have = if (to.isFile) to.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            // What was kept doesn't fit what the site has now: from the start next try.
            if (code == 416) {
                to.delete()
                throw IOException("starting over")
            }
            val resumed = code == 206 && have > 0
            if (code != 200 && !resumed) throw IOException("the site answered $code")
            var got = if (resumed) have else 0L
            val size = if (conn.contentLengthLong > 0) got + conn.contentLengthLong else -1L
            var shown = -1L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(to, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val pct = if (size > 0) got * 100 / size else got shr 20
                        if (pct != shown) {
                            shown = pct
                            progress(got, size)
                        }
                    }
                }
            }
            if (size > 0 && got != size) throw IOException("it stopped at ${got * 100 / size}%")
        } finally {
            conn.disconnect()
        }
    }

    // A dropped download is tried this many times in all (2, 4, 8, 16 s apart).
    private const val RETRIES = 5
}

/**
 * The update, as a card (from data, so it's previewed): the new version and
 * what's new, then one button for the step it's at: Update now, the download's
 * progress, Allow installing (once), Install, or Try again; Later closes it.
 */
@androidx.compose.runtime.Composable
fun UpdateCard(state: UpdateState, current: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val update = when (state) {
        is UpdateState.Available -> state.update
        is UpdateState.Downloading -> state.update
        is UpdateState.Ready -> state.update
        is UpdateState.NeedsPermission -> state.update
        is UpdateState.Failed -> state.update
        UpdateState.None -> return
    }
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = Palette.panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.gold.copy(alpha = 0.6f)),
        modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Kicker("Update ready")
            Text("90s Kid Arcade ${update.name}", color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            Text("You have $current. Your games and settings stay.", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (update.notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("What's new", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                for (n in update.notes.take(4)) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        CheckIcon(Modifier.padding(top = 3.dp).size(12.dp), color = Palette.gold)
                        Spacer(Modifier.width(8.dp))
                        Text(n, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when (state) {
                is UpdateState.Downloading -> {
                    Text("Downloading ${(state.fraction * 100).toInt()}%", color = Palette.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(state.fraction, thickness = 6.dp)
                    Spacer(Modifier.height(6.dp))
                    Text("Keep playing: it installs when it's done.", color = Palette.muted, fontSize = 12.sp)
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "One time only: Android asks you to allow 90s Kid Arcade to install updates. Turn on \"Allow from this source\", then come back.",
                        color = Palette.text, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    GoldButton("Allow and install", onUpdate, Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> {
                    Text(state.why, color = Palette.red, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    GoldButton("Try again", onUpdate, Modifier.fillMaxWidth())
                }
                is UpdateState.Ready -> GoldButton("Install", onUpdate, Modifier.fillMaxWidth(), play = true)
                else -> GoldButton("Update now", onUpdate, Modifier.fillMaxWidth(), play = true)
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(if (state is UpdateState.Downloading) "Hide" else "Later", onLater, Modifier.fillMaxWidth())
        }
    }
}

/** [UpdateCard] in a dialog over the page. */
@androidx.compose.runtime.Composable
fun UpdateDialog(state: UpdateState, current: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onLater, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        TextScaleCap { UpdateCard(state, current, onUpdate, onLater) }
    }
}
