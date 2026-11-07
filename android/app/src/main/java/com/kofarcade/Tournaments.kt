package com.kofarcade

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.tournament.Bracket
import com.kofarcade.core.tournament.Tournament
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Tournaments on screen (docs/tournament-plan.md, T5): the bracket in the
// room sheet, World Cup style (two halves meeting at the final in the middle,
// the trophy under it), the bar over the game, the VS intro before each
// match, and the champion lifting our own trophy (drawn here: not FIFA's).

private val Gold = Color(0xFFF2C14E)
private val GoldDark = Color(0xFFB8860B)
private val GoldLight = Color(0xFFFFE9A8)
private val CardBg = Color(0xFF1C1C24)
private val Line = Color(0xFF5A5A66)

/** The bar over the game: "Quarter-final 2 of 4 · Rafi vs Tanu". */
@Composable
fun TournamentBar(t: Tournament, modifier: Modifier = Modifier) {
    val text = t.bar() ?: if (t.over) "Champion: ${t.name(t.champion)}" else "Tournament: the next match is coming up"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Canvas(Modifier.size(12.dp)) { drawTrophy(size.width / 2, size.height, size.height) }
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The tournament part of the room sheet: the host starts one (best of 1 or
 * 3), everyone sees the bracket; the two playing say who won (T3), the host
 * decides a dispute or any game; the host can call it off.
 */
@Composable
fun TournamentSection(
    t: Tournament?,
    isHost: Boolean,
    inRoom: Int,
    localId: Int,
    onStart: (bestOf: Int) -> Unit,
    onReport: (winner: Int) -> Unit,
    onDecide: (winner: Int) -> Unit,
    onEnd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) { drawTrophy(size.width / 2, size.height, size.height) }
            Spacer(Modifier.width(8.dp))
            Text("TOURNAMENT", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (t == null) {
            if (!isHost) {
                Text("The host can start a tournament: everyone in the room, one match at a time, until there's a champion.", color = Palette.muted, fontSize = 12.sp)
                return
            }
            var bestOf by remember { mutableStateOf(1) }
            Text(
                if (inRoom >= Tournament.MIN_PLAYERS) "$inRoom in the room: a bracket drawn at random, byes for the top seeds."
                else "A tournament needs 3 to 8 in the room ($inRoom now).",
                color = Palette.muted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("One match", bestOf == 1) { bestOf = 1 }
                Chip("Best of 3", bestOf == 3) { bestOf = 3 }
                Spacer(Modifier.weight(1f))
                GoldButton("Start tournament", { onStart(bestOf) }, compact = true, enabled = inRoom in Tournament.MIN_PLAYERS..Bracket.MAX)
            }
            return
        }
        BracketView(t, Modifier.fillMaxWidth())
        val pair = t.pair
        if (pair != null) {
            val (a, b) = pair
            val me = localId.toString()
            Spacer(Modifier.height(8.dp))
            if (me == a || me == b) {
                val other = if (me == a) b else a
                val said = t.reports[me]
                Text(
                    if (said != null) "You said ${if (said == me) "you" else t.name(said)} won. Waiting for ${t.name(other)}…"
                    else "When the game's done, if the app doesn't see who won:",
                    color = Palette.muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("I won", { onReport(me.toInt()) }, compact = true)
                    GhostButton("${t.name(other)} won", { onReport(other.toInt()) }, compact = true)
                }
            }
            if (isHost) {
                val disputed = t.reports.size == 2 && t.reports.values.toSet().size == 2
                Spacer(Modifier.height(6.dp))
                Text(if (disputed) "They disagree: you decide who won this game." else "Host: settle this game", color = if (disputed) Palette.red else Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("${t.name(a)} won", { onDecide(a.toInt()) }, compact = true)
                    GhostButton("${t.name(b)} won", { onDecide(b.toInt()) }, compact = true)
                }
            }
        }
        if (isHost) {
            Spacer(Modifier.height(8.dp))
            GhostButton(if (t.over) "Close the tournament" else "Call it off", onEnd, compact = true, danger = !t.over)
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) Color.Black else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Gold else Color.Transparent)
            .border(1.dp, if (on) Gold else Palette.line, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The bracket, World Cup style: the first round's two halves on the left and
 * right, the rounds stepping in to the final in the middle with the trophy
 * under it. Each winner's line lights up gold, drawn in as it happens; the
 * match on now glows.
 */
@Composable
fun BracketView(t: Tournament, modifier: Modifier = Modifier) {
    val matches = t.bracket.matches
    val rounds = t.bracket.rounds
    // Lines drawn in when results come.
    val still = LocalStill.current
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(t.results.size) {
        if (still) return@LaunchedEffect
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "on now")
    val latest = t.results.lastOrNull()?.match
    BoxWithConstraints(modifier) {
        // Columns: round 0 left, …, final, …, round 0 right.
        val cols = rounds * 2 - 1
        val colW = 96.dp
        val gap = 14.dp
        val width = colW * cols + gap * (cols - 1)
        val firstRound = matches.count { it.round == 0 }
        val half = firstRound / 2
        val slotH = 46.dp
        val height = slotH * half * 2 + 24.dp
        val full = maxOf(width, if (this.maxWidth.value.isFinite()) this.maxWidth else width)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { Box(Modifier.width(full).height(height)) {
            val left = (full - width) / 2
            // Where each match sits: its column and its middle (in dp).
            fun place(m: Bracket.Match): Pair<Float, Float> {
                val inRound = matches.filter { it.round == m.round }
                val i = inRound.indexOf(m)
                val per = inRound.size
                val final = m.round == rounds - 1
                val side = if (final) 0 else if (i < per / 2) -1 else 1
                val col = if (final) rounds - 1 else if (side < 0) m.round else cols - 1 - m.round
                val j = if (final) 0 else if (side < 0) i else i - per / 2
                val count = if (final) 1 else per / 2
                val span = height.value - 24f
                val mid = 12f + span * (j + 0.5f) / count
                return (left.value + col * (colW.value + gap.value)) to mid
            }
            // The lines first, under the cards.
            Canvas(Modifier.fillMaxSize()) {
                val d = density
                for (m in matches) {
                    if (m.round == rounds - 1) continue
                    val next = matches.first { it.round == m.round + 1 && matches.filter { x -> x.round == m.round }.indexOf(m) / 2 == matches.filter { x -> x.round == m.round + 1 }.indexOf(it) }
                    val (x, y) = place(m)
                    val (nx, ny) = place(next)
                    val fromRight = x > nx
                    val sx = (if (fromRight) x else x + colW.value) * d
                    val ex = (if (fromRight) nx + colW.value else nx) * d
                    val midX = (sx + ex) / 2
                    val won = m.winner != null && m.a != null && m.b != null
                    val p = if (m.number == latest) grow.value else 1f
                    val c = if (won) Gold else Line
                    val w = if (won) 2.5f * d else 1.5f * d
                    drawLine(Line, Offset(sx, y * d), Offset(midX, y * d), 1.5f * d)
                    drawLine(Line, Offset(midX, y * d), Offset(midX, ny * d), 1.5f * d)
                    drawLine(Line, Offset(midX, ny * d), Offset(ex, ny * d), 1.5f * d)
                    if (won) {
                        // The winner's way on, drawn in.
                        val segs = listOf(Offset(sx, y * d) to Offset(midX, y * d), Offset(midX, y * d) to Offset(midX, ny * d), Offset(midX, ny * d) to Offset(ex, ny * d))
                        val total = 3f * p
                        segs.forEachIndexed { k, (a, b) ->
                            val f = (total - k).coerceIn(0f, 1f)
                            if (f > 0f) drawLine(c, a, Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f), w, StrokeCap.Round)
                        }
                    }
                }
                // The trophy under the final.
                val fm = matches.last()
                val (fx, fy) = place(fm)
                drawTrophy((fx + colW.value / 2) * d, (fy + slotH.value * 1.45f) * d, 30f * d)
            }
            for (m in matches) {
                val (x, y) = place(m)
                val bye = m.round == 0 && (m.a == null || m.b == null)
                val on = m.number == t.playing
                Box(
                    Modifier.padding(start = x.dp, top = (y - slotH.value / 2 + 2).dp).width(colW).height(slotH - 4.dp)
                        .alpha(if (bye) 0.45f else 1f),
                ) {
                    MatchCard(t, m, on, if (on) glow else 0f)
                }
            }
        } }
    }
}

@Composable
private fun MatchCard(t: Tournament, m: Bracket.Match, on: Boolean, glow: Float) {
    val border = if (on) Gold.copy(alpha = glow) else Palette.line
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(CardBg).border(if (on) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (id in listOf(m.a, m.b)) {
            val won = id != null && m.winner == id
            val lost = id != null && m.winner != null && m.winner != id
            Text(
                when {
                    id != null -> t.name(id)
                    m.round == 0 -> "bye"
                    else -> "—"
                },
                color = when {
                    won -> Gold
                    lost -> Palette.muted.copy(alpha = 0.6f)
                    else -> Palette.text
                },
                fontSize = 11.sp, fontWeight = if (won) FontWeight.Black else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Before each match: the two names rush in from the sides, VS slams down,
 * the round's name over it; a few seconds, then the fight (tap to skip).
 */
@Composable
fun VsIntro(round: String, a: String, b: String, you: Boolean, onDone: () -> Unit) {
    // A picture (tests): the intro as it stands once it's in.
    val still = LocalStill.current
    val slide = remember { Animatable(if (still) 0f else 1f) }
    val vs = remember { Animatable(if (still) 1f else 3f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        slide.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
        vs.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        kotlinx.coroutines.delay(1_800)
        fade.animateTo(0f, tween(350))
        onDone()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().alpha(fade.value).background(Brush.horizontalGradient(listOf(Color(0xEE3A0A0A), Color(0xEE000000), Color(0xEE0A1A3A))))
            .clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth.value
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(round.uppercase(), color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            if (you) Text("YOU'RE UP!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, textAlign = TextAlign.End,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width((w * 0.36f).dp).graphicsLayer { translationX = -slide.value * w * density },
                )
                Text(
                    "VS", color = Gold, fontSize = 44.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 18.dp).scale(vs.value).alpha((2f - vs.value).coerceIn(0f, 1f)),
                )
                Text(
                    b, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width((w * 0.36f).dp).graphicsLayer { translationX = slide.value * w * density },
                )
            }
        }
    }
}

/** A match decided: the winner's name slides up, gold, for a moment. */
@Composable
fun ResultFlash(text: String, onDone: () -> Unit) {
    val still = LocalStill.current
    val up = remember { Animatable(if (still) 0f else 40f) }
    val fade = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(text) {
        if (still) return@LaunchedEffect
        fade.animateTo(1f, tween(250))
        up.animateTo(0f, spring(dampingRatio = 0.6f))
        kotlinx.coroutines.delay(2_400)
        fade.animateTo(0f, tween(400))
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text, color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
            modifier = Modifier.alpha(fade.value).graphicsLayer { translationY = up.value * density }
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/**
 * The champion: our trophy rises and is lifted, light rays turn behind it,
 * confetti falls, the name in gold. Tap to close.
 */
@Composable
fun ChampionScreen(name: String, you: Boolean, game: String, onDone: () -> Unit) {
    val still = LocalStill.current
    val rise = remember { Animatable(if (still) 0f else 1f) }
    val lift = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        rise.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        lift.animateTo(1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    }
    val turn by rememberInfiniteTransition(label = "rays").animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "turn")
    // Confetti: pieces with their own fall, drift and spin, moved every frame.
    val pieces = remember { List(90) { Confetti.random() } }
    var time by remember { mutableStateOf(if (still) 2.5f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xF0302008), Color(0xF2000000)))).clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            // Rays.
            rotate(turn, c) {
                for (k in 0 until 16) {
                    val a = (k * 2 * PI / 16).toFloat()
                    val p = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + cos(a - 0.09f) * size.maxDimension, c.y + sin(a - 0.09f) * size.maxDimension)
                        lineTo(c.x + cos(a + 0.09f) * size.maxDimension, c.y + sin(a + 0.09f) * size.maxDimension)
                        close()
                    }
                    drawPath(p, Gold.copy(alpha = 0.07f))
                }
            }
            // The trophy, rising then lifted.
            val h = size.minDimension * 0.42f
            val y = c.y + h * 0.5f + rise.value * size.height * 0.6f - lift.value * h * 0.06f
            drawTrophy(c.x, y, h)
            // Confetti.
            for (p in pieces) p.draw(this, time)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CHAMPION", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text(name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (you) "You won the $game tournament!" else "$game tournament", color = GoldLight, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Tap to close", color = Palette.muted, fontSize = 11.sp)
        }
    }
}

private class Confetti(val x: Float, val delay: Float, val speed: Float, val drift: Float, val spin: Float, val color: Color, val w: Float) {
    fun draw(s: DrawScope, t: Float) {
        val life = t - delay
        if (life < 0) return
        val fall = (life * speed) % 1.15f
        val px = s.size.width * x + sin(life * 2.3f + drift * 6f) * s.size.width * 0.04f * drift
        val py = s.size.height * (fall - 0.1f)
        s.rotate(life * spin, Offset(px, py)) {
            s.drawRect(color, Offset(px - w / 2, py - w), Size(w, w * 2.2f))
        }
    }

    companion object {
        private val colors = listOf(Gold, Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color.White, Color(0xFFFF7043))
        fun random() = Random.Default.let { r ->
            Confetti(r.nextFloat(), r.nextFloat() * 1.5f, 0.18f + r.nextFloat() * 0.22f, r.nextFloat(), 90f + r.nextFloat() * 360f, colors[r.nextInt(colors.size)], 6f + r.nextFloat() * 8f)
        }
    }
}

/**
 * Our trophy (not anyone's real one): a gold cup with two handles on a
 * stem, a star on its face, on a dark plinth. [cx] is its middle, [bottom]
 * where it stands, [h] its height.
 */
fun DrawScope.drawTrophy(cx: Float, bottom: Float, h: Float) {
    val w = h * 0.72f
    val top = bottom - h
    val gold = Brush.linearGradient(listOf(GoldLight, Gold, GoldDark, Gold), Offset(cx - w / 2, top), Offset(cx + w / 2, bottom))
    // Plinth.
    drawRoundRect(Color(0xFF2B2B2B), Offset(cx - w * 0.32f, bottom - h * 0.14f), Size(w * 0.64f, h * 0.14f), CornerRadius(h * 0.02f))
    drawRect(gold, Offset(cx - w * 0.26f, bottom - h * 0.11f), Size(w * 0.52f, h * 0.025f))
    // Stem and foot.
    drawRect(gold, Offset(cx - w * 0.2f, bottom - h * 0.21f), Size(w * 0.4f, h * 0.07f))
    drawRect(gold, Offset(cx - w * 0.06f, bottom - h * 0.4f), Size(w * 0.12f, h * 0.2f))
    drawOval(gold, Offset(cx - w * 0.14f, bottom - h * 0.44f), Size(w * 0.28f, h * 0.08f))
    // Cup.
    val cupTop = top + h * 0.04f
    val cup = Path().apply {
        moveTo(cx - w * 0.38f, cupTop)
        lineTo(cx + w * 0.38f, cupTop)
        cubicTo(cx + w * 0.38f, cupTop + h * 0.3f, cx + w * 0.2f, cupTop + h * 0.5f, cx, cupTop + h * 0.52f)
        cubicTo(cx - w * 0.2f, cupTop + h * 0.5f, cx - w * 0.38f, cupTop + h * 0.3f, cx - w * 0.38f, cupTop)
        close()
    }
    drawPath(cup, gold)
    // Handles.
    val hw = h * 0.035f
    drawArc(gold, 90f, 180f, false, Offset(cx - w * 0.56f, cupTop + h * 0.06f), Size(w * 0.36f, h * 0.24f), style = Stroke(hw))
    drawArc(gold, -90f, 180f, false, Offset(cx + w * 0.2f, cupTop + h * 0.06f), Size(w * 0.36f, h * 0.24f), style = Stroke(hw))
    // Rim and shine.
    drawOval(Brush.linearGradient(listOf(GoldDark, GoldLight)), Offset(cx - w * 0.38f, cupTop - h * 0.025f), Size(w * 0.76f, h * 0.05f))
    drawLine(Color.White.copy(alpha = 0.45f), Offset(cx - w * 0.24f, cupTop + h * 0.07f), Offset(cx - w * 0.16f, cupTop + h * 0.32f), h * 0.02f, StrokeCap.Round)
    // A star on its face.
    val star = Path()
    val r1 = h * 0.08f
    val r2 = r1 * 0.45f
    val sc = Offset(cx, cupTop + h * 0.2f)
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) r1 else r2
        val a = (-PI / 2 + k * PI / 5).toFloat()
        val p = Offset(sc.x + cos(a) * r, sc.y + sin(a) * r)
        if (k == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
    }
    star.close()
    drawPath(star, Color(0xFF7A5200).copy(alpha = 0.55f))
}
