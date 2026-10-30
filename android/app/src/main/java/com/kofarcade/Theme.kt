package com.kofarcade

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/**
 * Android's text size, up to [MAX_TEXT_SCALE]: larger settings (up to 2x on
 * Android 14) make text bigger only that far, so arcade labels, codes and
 * buttons keep their shape on every phone. Each dialog's window gets it again
 * (a dialog starts from the window's own density).
 */
@Composable
fun TextScaleCap(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= MAX_TEXT_SCALE) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, MAX_TEXT_SCALE), content = content)
    }
}

/** The largest text size the app follows: Android's "Large" setting, about. */
const val MAX_TEXT_SCALE = 1.3f

@Composable
fun KofTheme(content: @Composable () -> Unit) = TextScaleCap {
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
