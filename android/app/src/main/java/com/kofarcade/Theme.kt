package com.kofarcade

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * The app's screens are designed on one phone (450 design units across, a
 * 411 x 914 dp 20:9 phone, the kind most players have), and every other screen gets that same design, scaled
 * by its short side: text, buttons, gaps and pictures keep the same size
 * against the screen on a small phone, a big one and a tablet, held either
 * way. A tablet's extra length (it's squarer than a phone) is room the
 * [Breakpoints] use, never a reason to shrink everything.
 * Text follows Android's text size up to [MAX_TEXT_SCALE], so arcade
 * labels, codes and buttons keep their shape. Each dialog's window gets it
 * again (a dialog starts from the window's own density); it's worked out
 * from the screen's own density, so it never compounds.
 */
@Composable
fun TextScaleCap(content: @Composable () -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val base = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density
    val font = minOf(LocalDensity.current.fontScale, MAX_TEXT_SCALE) * TEXT_SIZE
    val scale = uiScale(config.screenWidthDp, config.screenHeightDp)
    CompositionLocalProvider(LocalDensity provides Density(base * scale, font), content = content)
}

/**
 * How much the design phone's screens are scaled for a [widthDp] x [heightDp]
 * screen (either way round): by the short side, as long as the long side
 * still has [MIN_LONG_DP] of the design's room (a squarer tablet keeps the
 * room a phone's sheets and menus need).
 */
fun uiScale(widthDp: Int, heightDp: Int): Float {
    if (widthDp <= 0 || heightDp <= 0) return 1f
    val short = minOf(widthDp, heightDp)
    val long = maxOf(widthDp, heightDp)
    return minOf(short / DESIGN_SHORT_DP, long / MIN_LONG_DP).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
}

/**
 * The design's short side, in its units: a 411 dp phone (the most common) shows
 * 450 of them, so everything's a little under Android's own sizes: compact,
 * like the arcade's own menus, with the same look on every screen.
 */
const val DESIGN_SHORT_DP = 450f

/** Text a touch smaller again than the rest (so the gaps around it read bigger). */
private const val TEXT_SIZE = 0.95f
private const val MIN_LONG_DP = 640f
// Never so small a button is hard to hit; a big tablet still looks like the phone, grown.
private const val MIN_UI_SCALE = 0.8f
private const val MAX_UI_SCALE = 2.2f

/**
 * Screen breakpoints, like Tailwind's (sm, md, lg, xl), in the design phone's
 * units: after [uiScale], each kind of screen lands in the same one on every
 * device, so a layout chosen for it looks the same everywhere.
 *
 * - below [sm]: the narrowest screens (a sheet or a panel, more than a phone)
 * - [sm] 360: a phone held upright (450 on every phone), a tablet upright (450-525)
 * - [md] 560: a tablet held sideways (640-720 wide)
 * - [lg] 760: a phone held sideways (about 1000 wide)
 * - [xl] 1100: wider than any phone (a big screen below the scale's floor)
 */
object Breakpoints {
    val sm = 360.dp
    val md = 560.dp
    val lg = 760.dp
    val xl = 1100.dp
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
