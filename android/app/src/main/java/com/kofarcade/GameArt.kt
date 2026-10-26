package com.kofarcade

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The games' pictures, packed inside the app (assets/art, fetched when it's
 * built: see app/art.txt), decoded once and kept while there's room. No
 * network: they're there offline, on the first launch, always.
 */
object GameArt {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Already decoded: shows at once, without a frame of the placeholder. */
    fun cached(file: String): ImageBitmap? = cache.get(file)

    /** [file] (core's artFile) decoded, or null if this build hasn't got it. Off the main thread. */
    fun load(context: Context, file: String): ImageBitmap? {
        cache.get(file)?.let { return it }
        val bitmap = runCatching {
            context.assets.open("art/$file").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap() ?: return null
        cache.put(file, bitmap)
        return bitmap
    }
}
