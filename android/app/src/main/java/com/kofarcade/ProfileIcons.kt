package com.kofarcade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kofarcade.core.online.PROFILE_ICONS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The profile icons: the app's own set, drawn (no game or character art).
 * Eight shapes, each in two styles: a coloured shape on the dark panel, or
 * a dark shape on a coloured disc. [PROFILE_ICONS] of them.
 */
@Composable
fun ProfileIcon(icon: Int, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val i = icon.mod(PROFILE_ICONS)
    val color = ICON_COLORS[i % ICON_COLORS.size]
    val filled = i >= PROFILE_ICONS / 2
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) color else Palette.bg)
            .border(if (ring != null) 2.dp else 1.dp, ring ?: if (filled) color else color.copy(alpha = 0.5f), CircleShape)
            .padding(size * 0.22f),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawShape(i % SHAPES, if (filled) OnGold else color) }
    }
}

private val ICON_COLORS = listOf(Palette.gold, Palette.red, Palette.green, Palette.blue)
private const val SHAPES = 8

private fun DrawScope.drawShape(shape: Int, color: Color) {
    val w = size.width
    val h = size.height
    fun at(x: Float, y: Float) = Offset(x * w, y * h)
    fun poly(vararg pts: Pair<Float, Float>) = Path().apply {
        moveTo(pts[0].first * w, pts[0].second * h)
        pts.drop(1).forEach { lineTo(it.first * w, it.second * h) }
        close()
    }
    when (shape) {
        // Star
        0 -> drawPath(Path().apply {
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) 0.5f else 0.21f
                val a = -PI / 2 + k * PI / 5
                val p = at(0.5f + r * cos(a).toFloat(), 0.53f + r * sin(a).toFloat())
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }, color)
        // Lightning bolt
        1 -> drawPath(poly(0.58f to 0f, 0.15f to 0.58f, 0.46f to 0.58f, 0.38f to 1f, 0.85f to 0.38f, 0.53f to 0.38f), color)
        // Crown
        2 -> drawPath(poly(0.05f to 0.25f, 0.3f to 0.55f, 0.5f to 0.15f, 0.7f to 0.55f, 0.95f to 0.25f, 0.85f to 0.85f, 0.15f to 0.85f), color)
        // Heart
        3 -> drawPath(Path().apply {
            moveTo(0.5f * w, 0.92f * h)
            cubicTo(0.05f * w, 0.6f * h, -0.02f * w, 0.25f * h, 0.27f * w, 0.12f * h)
            cubicTo(0.4f * w, 0.07f * h, 0.48f * w, 0.17f * h, 0.5f * w, 0.27f * h)
            cubicTo(0.52f * w, 0.17f * h, 0.6f * w, 0.07f * h, 0.73f * w, 0.12f * h)
            cubicTo(1.02f * w, 0.25f * h, 0.95f * w, 0.6f * h, 0.5f * w, 0.92f * h)
            close()
        }, color)
        // Diamond
        4 -> drawPath(poly(0.5f to 0f, 0.92f to 0.5f, 0.5f to 1f, 0.08f to 0.5f), color)
        // Flame
        5 -> drawPath(Path().apply {
            moveTo(0.5f * w, 0f)
            cubicTo(0.75f * w, 0.3f * h, 0.95f * w, 0.5f * h, 0.85f * w, 0.75f * h)
            cubicTo(0.78f * w, 0.95f * h, 0.6f * w, 1f * h, 0.5f * w, 1f * h)
            cubicTo(0.4f * w, 1f * h, 0.22f * w, 0.95f * h, 0.15f * w, 0.75f * h)
            cubicTo(0.08f * w, 0.55f * h, 0.25f * w, 0.4f * h, 0.33f * w, 0.3f * h)
            cubicTo(0.35f * w, 0.45f * h, 0.4f * w, 0.52f * h, 0.45f * w, 0.55f * h)
            cubicTo(0.42f * w, 0.35f * h, 0.45f * w, 0.15f * h, 0.5f * w, 0f)
            close()
        }, color)
        // Joystick: a ball on a stick, on a base
        6 -> {
            drawCircle(color, 0.2f * w, at(0.5f, 0.22f))
            drawRect(color, at(0.44f, 0.3f), Size(0.12f * w, 0.45f * h))
            drawRoundRect(color, at(0.1f, 0.72f), Size(0.8f * w, 0.24f * h), androidx.compose.ui.geometry.CornerRadius(0.08f * w))
        }
        // Coin: a ring with a bar
        else -> {
            drawCircle(color, 0.48f * w, at(0.5f, 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.12f * w))
            drawRect(color, at(0.44f, 0.24f), Size(0.12f * w, 0.52f * h))
        }
    }
}
