package com.kofarcade

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The website's heading font (Barlow Condensed Black Italic, SIL Open Font License). */
val ArcadeFont = FontFamily(Font(R.font.barlow_condensed_black_italic, FontWeight.Black, FontStyle.Italic))

/**
 * While a room is being made or joined: the app's own "GAME ON" screen,
 * like the website's. The big letters fill with gold, again and again, and
 * the bar below runs, so it never looks stuck; [step] says what's happening
 * now in plain words ("Finding the room…", "Connecting to Rafi…").
 * [overGame]: drawn over the game (a darker veil, the game showing through).
 */
@Composable
fun GameOnLoading(step: String, onCancel: (() -> Unit)? = null, overGame: Boolean = false) = TextScaleCap {
    val still = LocalInspectionMode.current || LocalStill.current
    val anim = rememberInfiniteTransition(label = "game-on")
    val fillAnim by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "fill")
    val runAnim by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "run")
    val fill = if (still) 0.62f else fillAnim
    val run = if (still) 0.35f else runAnim
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(if (overGame) Color.Black.copy(alpha = 0.82f) else Palette.bg)
            .background(Brush.radialGradient(listOf(Palette.gold.copy(alpha = 0.16f), Color.Transparent)))
            // Nothing behind is pressed while it's up.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Fits a landscape phone's height as well as a portrait one's width, never cut or scrolling.
        val big = minOf(maxWidth.value / 4.2f, maxHeight.value / 3.6f).coerceIn(44f, 150f).sp
        val short = maxHeight < 420.dp
        // Behind it all, as on the website: arcade calls scrolling by, row against row.
        val rowSize = minOf(maxWidth.value / 6f, maxHeight.value / 5f).coerceIn(36f, 110f).sp
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MarqueeRow("PLAY MORE · INSERT COIN · ", rowSize, still, 14_000, Color.White.copy(alpha = 0.06f))
            MarqueeRow("GAME ON · PRESS START · ", rowSize, still, 17_000, Palette.gold.copy(alpha = 0.12f), backwards = true)
            MarqueeRow("HERE COMES A NEW CHALLENGER · ", rowSize, still, 20_000, Color.White.copy(alpha = 0.06f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp, vertical = if (short) 8.dp else 24.dp).widthIn(max = 560.dp)) {
            Row {
                Text("90S KID ", color = Palette.text, fontFamily = ArcadeFont, fontSize = 22.sp)
                Text("ARCADE", color = Palette.gold, fontFamily = ArcadeFont, fontSize = 22.sp)
            }
            Spacer(Modifier.height(if (short) 2.dp else 10.dp))
            // GAME ON: outlined letters, gold filling them from the left.
            Box(contentAlignment = Alignment.Center) {
                val style = TextStyle(fontFamily = ArcadeFont, fontSize = big, textAlign = TextAlign.Center, letterSpacing = 1.sp)
                Text("GAME ON", style = style.copy(color = Palette.gold.copy(alpha = 0.55f), drawStyle = Stroke(width = 3f)))
                Text(
                    "GAME ON",
                    style = style.copy(brush = Brush.verticalGradient(listOf(Color(0xFFFFD45A), Palette.gold, Color(0xFFB8860B)))),
                    modifier = Modifier.drawWithContent {
                        clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                    },
                )
            }
            Spacer(Modifier.height(if (short) 8.dp else 18.dp))
            Text(step, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            // A bar that keeps running: it's working.
            Canvas(Modifier.fillMaxWidth(0.7f).height(4.dp)) {
                drawLine(Palette.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                val w = size.width * 0.3f
                val x = -w + (size.width + w) * run
                drawLine(
                    Palette.gold,
                    Offset(x.coerceAtLeast(0f), size.height / 2),
                    Offset((x + w).coerceAtMost(size.width), size.height / 2),
                    size.height,
                    StrokeCap.Round,
                )
            }
            if (onCancel != null) {
                Spacer(Modifier.height(if (short) 12.dp else 26.dp))
                GhostButton("Cancel", onCancel, compact = true)
            }
        }
    }
}

// One row of outlined words, scrolling for ever (the text twice, so there's no seam).
@Composable
private fun MarqueeRow(text: String, size: androidx.compose.ui.unit.TextUnit, still: Boolean, ms: Int, color: Color, backwards: Boolean = false) {
    var oneWidth by remember { mutableFloatStateOf(0f) }
    val anim = rememberInfiniteTransition(label = "marquee")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "row")
    val p = if (still) 0.3f else t
    Text(
        text.repeat(4),
        style = TextStyle(fontFamily = ArcadeFont, fontSize = size, color = color, drawStyle = Stroke(width = 2f)),
        maxLines = 1,
        softWrap = false,
        onTextLayout = { oneWidth = it.size.width / 2f },
        modifier = Modifier
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .graphicsLayer { translationX = -oneWidth * (if (backwards) 1f - p else p) },
    )
}
