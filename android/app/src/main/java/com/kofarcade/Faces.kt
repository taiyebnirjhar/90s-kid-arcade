package com.kofarcade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kofarcade.core.Roster
import java.io.File

/**
 * The fighters' faces taken from one game's own screen (see Portraits.kt in
 * core), kept as small PNGs under files/faces/<game>/<fighter id>.png, so
 * each is taken once and shows from then on: in the pause menu, the team
 * buttons and the select screen. Fighters not seen yet keep their initials.
 */
class Faces(context: Context, gameId: String, private val roster: Roster?) {
    private val dir = File(context.filesDir, "faces/$gameId")

    /** By fighter name (what the screens show), for Compose. */
    val byName = mutableStateMapOf<String, ImageBitmap>()

    /** By fighter name, for the HUD's canvas. */
    private val bitmaps = HashMap<String, Bitmap>()

    init {
        dir.listFiles { f -> f.name.endsWith(".png") }?.forEach { f ->
            BitmapFactory.decodeFile(f.path)?.let { put(f.name.removeSuffix(".png"), it) }
        }
    }

    fun has(id: String): Boolean = nameOf(id)?.let { it in bitmaps } ?: true

    fun bitmap(name: String): Bitmap? = bitmaps[name]

    /** Keeps [id]'s face ([w] x [h] ARGB). Call on the main thread. */
    fun save(id: String, argb: IntArray, w: Int, h: Int) {
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        put(id, bitmap)
        runCatching {
            dir.mkdirs()
            File(dir, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun nameOf(id: String): String? = roster?.character(id)?.name

    private fun put(id: String, bitmap: Bitmap) {
        val name = nameOf(id) ?: return
        bitmaps[name] = bitmap
        byName[name] = bitmap.asImageBitmap()
    }
}

/** The game's faces for the Compose screens over it (empty outside a game). */
val LocalFaces = compositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }
