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
        val big = (maxWidth.value / 4.2f).coerceIn(64f, 150f).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp).widthIn(max = 560.dp)) {
            Row {
                Text("90S KID ", color = Palette.text, fontFamily = ArcadeFont, fontSize = 22.sp)
                Text("ARCADE", color = Palette.gold, fontFamily = ArcadeFont, fontSize = 22.sp)
            }
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(18.dp))
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
                Spacer(Modifier.height(26.dp))
                GhostButton("Cancel", onCancel, compact = true)
            }
        }
    }
}
