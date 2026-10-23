package com.kofarcade

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val bg = Color(0xFF0B0B12)
    val panel = Color(0xFF171724)
    val line = Color(0xFF2C2C40)
    val text = Color(0xFFF1F0F7)
    val muted = Color(0xFF9A98B0)
    val gold = Color(0xFFE8B21A)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF30A46C)
    val blue = Color(0xFF3E8ED0)
}

@Composable
fun KofTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.gold,
            onPrimary = Color(0xFF1D1400),
            background = Palette.bg,
            surface = Palette.panel,
            onSurface = Palette.text,
            onBackground = Palette.text,
            surfaceVariant = Palette.panel,
            outline = Palette.line,
            error = Palette.red,
        ),
        content = content,
    )
}

/** "Kyo Kusanagi" -> "KK", for the portrait tiles (no character art is bundled). */
fun initials(name: String): String =
    name.replace(Regex("[^A-Za-z' ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.first() }
        .joinToString("")
        .take(2)
        .uppercase()

/** A stable hue per character name, so portraits are easy to tell apart. */
fun nameHue(name: String): Float {
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) % 360
    return h.toFloat()
}
