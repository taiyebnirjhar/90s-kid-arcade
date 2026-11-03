package com.kofarcade

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Match videos: kept in the gallery, and handed to the apps people share in. */
object Videos {
    /** An app to share in, if the phone has it. */
    data class Target(val label: String, val pkg: String?)

    private val APPS = listOf(
        Target("WhatsApp", "com.whatsapp"),
        Target("Messenger", "com.facebook.orca"),
        Target("Facebook", "com.facebook.katana"),
    )

    /** The share buttons for this phone: the apps it has, then More (Android's own list). */
    fun targets(context: Context): List<Target> =
        APPS.filter { t -> runCatching { context.packageManager.getPackageInfo(t.pkg!!, 0) }.isSuccess } + Target("More…", null)

    /**
     * Into the gallery (Movies/90s Kid Arcade) and [file] deleted; its address
     * to share. Before Android 10, the app's own Movies folder (no permission
     * needed), shared through the app's file provider.
     */
    fun keep(context: Context, file: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/90s Kid Arcade")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("The gallery said no")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            file.delete()
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "").apply { mkdirs() }
        val kept = File(dir, name)
        file.copyTo(kept, overwrite = true)
        file.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", kept)
    }

    /** That app's share screen with the video in it ([Target.pkg] null: Android's list of every app). */
    fun share(context: Context, uri: Uri, target: Target) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Played on 90s Kid Arcade: https://arcade.taiyebnirjhor.com")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = if (target.pkg != null) send.setPackage(target.pkg) else Intent.createChooser(send, "Share the video")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
