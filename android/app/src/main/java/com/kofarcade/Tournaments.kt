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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawWithContent
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

/** The bar over the game: "Quarter-final 2 of 4 · Rafi vs Tanu". At most half the screen wide, so it never covers the controls. */
@Composable
fun TournamentBar(t: Tournament, modifier: Modifier = Modifier) {
    val text = t.bar() ?: if (t.over) "Champion: ${t.name(t.champion)}" else "Tournament: the next match is coming up"
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = (maxWidth * 0.5f).coerceAtLeast(220.dp)).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.62f)).border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Canvas(Modifier.size(12.dp)) { drawTrophy(size.width / 2, size.height, size.height) }
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The tournament part of the room sheet: the host starts one (best of 1 or
 * 3), everyone sees the bracket; the two playing say who won (T3), the host
 * decides a dispute or any game; the host can call it off. Buttons wrap to
 * a new line on a narrow screen or with big text.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val rowGap = Arrangement.spacedBy(8.dp)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) { drawTrophy(size.width / 2, size.height, size.height) }
            Spacer(Modifier.width(8.dp))
            Text("TOURNAMENT", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            if (t != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${t.bracket.players.size} players · ${if (t.bestOf == 3) "best of 3" else "one match each"}",
                    color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (t == null) {
            if (!isHost) {
                Text("The host can start a tournament: everyone in the room, one match at a time, until there's a champion.", color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp)
                return
            }
            var bestOf by remember { mutableStateOf(1) }
            Text(
                if (inRoom >= Tournament.MIN_PLAYERS) "$inRoom in the room: a bracket drawn at random, byes for the top seeds."
                else "A tournament needs 3 to 8 in the room ($inRoom now).",
                color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            FlowRow(horizontalArrangement = rowGap, verticalArrangement = rowGap) {
                Chip("One match", bestOf == 1) { bestOf = 1 }
                Chip("Best of 3", bestOf == 3) { bestOf = 3 }
                GoldButton("Start tournament", { onStart(bestOf) }, compact = true, enabled = inRoom in Tournament.MIN_PLAYERS..Bracket.MAX)
            }
            return
        }
        BracketView(t, Modifier.fillMaxWidth())
        val pair = t.pair
        if (pair != null) {
            val (a, b) = pair
            val me = localId.toString()
            if (me == a || me == b) {
                val other = if (me == a) b else a
                val said = t.reports[me]
                Text(
                    if (said != null) "You said ${if (said == me) "you" else t.name(said)} won. Waiting for ${t.name(other)}…"
                    else "When the game's done, if the app doesn't see who won:",
                    color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                FlowRow(horizontalArrangement = rowGap, verticalArrangement = rowGap) {
                    GhostButton("I won", { onReport(me.toInt()) }, compact = true)
                    GhostButton("${t.name(other)} won", { onReport(other.toInt()) }, compact = true)
                }
            }
            if (isHost) {
                val disputed = t.reports.size == 2 && t.reports.values.toSet().size == 2
                Text(
                    if (disputed) "They disagree: you decide who won this game." else "Host: settle this game",
                    color = if (disputed) Palette.red else Palette.muted, fontSize = 12.sp,
                )
                FlowRow(horizontalArrangement = rowGap, verticalArrangement = rowGap) {
                    GhostButton("${t.name(a)} won", { onDecide(a.toInt()) }, compact = true)
                    GhostButton("${t.name(b)} won", { onDecide(b.toInt()) }, compact = true)
                }
            }
        }
        if (isHost) GhostButton(if (t.over) "Close the tournament" else "Call it off", onEnd, compact = true, danger = !t.over)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(38.dp).clip(RoundedCornerShape(50)).background(if (on) Gold else Color.Transparent)
            .border(1.dp, if (on) Gold else Palette.line, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Text(text, color = if (on) Color.Black else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Where each match of a bracket sits: its column (0 = the left edge) and its middle, as a share of the height. */
private class BracketLayout(val bracket: Bracket) {
    val rounds = bracket.rounds
    val columns = rounds * 2 - 1
    val matches = bracket.matches
    private val byRound = matches.groupBy { it.round }
    val firstHalf = byRound.getValue(0).size / 2

    /** (column, middle 0..1) of [m]. */
    val spot: Map<Int, Pair<Int, Float>> = matches.associate { m ->
        val inRound = byRound.getValue(m.round)
        val i = inRound.indexOf(m)
        val per = inRound.size
        val final = m.round == rounds - 1
        val left = i < per / 2
        val col = if (final) rounds - 1 else if (left) m.round else columns - 1 - m.round
        val j = if (final) 0 else if (left) i else i - per / 2
        val count = if (final) 1 else per / 2
        m.number to (col to (j + 0.5f) / count)
    }

    /** The match each one's winner goes on to. */
    val next: Map<Int, Int> = matches.filter { it.round < rounds - 1 }.associate { m ->
        val i = byRound.getValue(m.round).indexOf(m)
        m.number to byRound.getValue(m.round + 1)[i / 2].number
    }
}

/**
 * The bracket, World Cup style: the first round's two halves on the left and
 * right, the rounds stepping in to the final in the middle with the trophy
 * under it. Each winner's line lights up gold, drawn in as it happens; the
 * match on now glows. The cards size to the space (scrolling sideways only
 * when even the smallest don't fit); the layout is worked out once per
 * bracket and the glow and lines animate in the drawing alone, so a frame of
 * animation never rebuilds the cards.
 */
@Composable
fun BracketView(t: Tournament, modifier: Modifier = Modifier) {
    val layout = remember(t.bracket) { BracketLayout(t.bracket) }
    val still = LocalStill.current
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(t.results.size) {
        if (still) return@LaunchedEffect
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val glow = if (still || t.playing == null) remember { mutableStateOf(1f) }
    else rememberInfiniteTransition(label = "glow").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "on now")
    val latest = t.results.lastOrNull()?.match
    BoxWithConstraints(modifier) {
        val gap = 12.dp
        val avail = if (maxWidth.value.isFinite()) maxWidth else 600.dp
        // As wide as fits, between a readable 76 dp and a roomy 130 dp a card.
        val colW = ((avail - gap * (layout.columns - 1)) / layout.columns).coerceIn(76.dp, 130.dp)
        val width = colW * layout.columns + gap * (layout.columns - 1)
        // Taller cards with bigger text (the phone's text size), up to 1.4x.
        val cardH = 42.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
        val slotH = cardH + 10.dp
        val trophyRoom = 64.dp
        val height = (slotH * layout.firstHalf).coerceAtLeast(cardH + trophyRoom + 20.dp) + 16.dp
        val full = maxOf(width, avail)
        val left = (full - width) / 2
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(full).height(height)) {
                fun x(m: Bracket.Match) = left + (colW + gap) * layout.spot.getValue(m.number).first
                fun y(m: Bracket.Match) = 8.dp + (height - 16.dp) * layout.spot.getValue(m.number).second
                Canvas(Modifier.fillMaxSize()) {
                    for (m in layout.matches) {
                        val to = layout.next[m.number]?.let { layout.matches[it] } ?: continue
                        val fromRight = x(m) > x(to)
                        val sx = (if (fromRight) x(m) else x(m) + colW).toPx()
                        val ex = (if (fromRight) x(to) + colW else x(to)).toPx()
                        val sy = y(m).toPx()
                        val ey = y(to).toPx()
                        val midX = (sx + ex) / 2
                        val thin = 1.5.dp.toPx()
                        drawLine(Line, Offset(sx, sy), Offset(midX, sy), thin)
                        drawLine(Line, Offset(midX, sy), Offset(midX, ey), thin)
                        drawLine(Line, Offset(midX, ey), Offset(ex, ey), thin)
                        if (m.winner == null || m.a == null || m.b == null) continue
                        // The winner's way on, drawn in (the newest one grows).
                        val p = if (m.number == latest) grow.value else 1f
                        val segs = listOf(Offset(sx, sy) to Offset(midX, sy), Offset(midX, sy) to Offset(midX, ey), Offset(midX, ey) to Offset(ex, ey))
                        segs.forEachIndexed { k, (a, b) ->
                            val f = (3f * p - k).coerceIn(0f, 1f)
                            if (f > 0f) drawLine(Gold, a, Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f), 2.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    val final = layout.matches.last()
                    val trophyH = minOf(trophyRoom.toPx() - 8.dp.toPx(), (height - y(final) - cardH / 2).toPx() - 6.dp.toPx())
                    if (trophyH > 16.dp.toPx()) drawTrophy((x(final) + colW / 2).toPx(), (y(final) + cardH / 2).toPx() + 6.dp.toPx() + trophyH, trophyH)
                }
                for (m in layout.matches) {
                    val bye = m.round == 0 && (m.a == null || m.b == null)
                    Box(Modifier.padding(start = x(m), top = y(m) - cardH / 2).width(colW).height(cardH).alpha(if (bye) 0.45f else 1f)) {
                        MatchCard(t, m, if (m.number == t.playing) glow else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCard(t: Tournament, m: Bracket.Match, glow: androidx.compose.runtime.State<Float>?) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(CardBg)
            .then(
                if (glow == null) Modifier.border(1.dp, Palette.line, shape)
                // The glow reads its value while drawing only: the card isn't rebuilt each frame.
                else Modifier.drawWithContent {
                    drawContent()
                    drawRoundRect(Gold.copy(alpha = glow.value), cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
                },
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
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
                fontSize = 11.sp, lineHeight = 13.sp, fontWeight = if (won) FontWeight.Black else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
        // Sized to the screen: a small phone gets smaller names, a tablet bigger.
        val nameSize = (w / 26f).coerceIn(20f, 40f).sp
        val vsSize = (w / 18f).coerceIn(30f, 58f).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(round.uppercase(), color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            if (you) Text("YOU'RE UP!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a, color = Color.White, fontSize = nameSize, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, textAlign = TextAlign.End,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width((w * 0.36f).dp).graphicsLayer { translationX = -slide.value * w * density },
                )
                Text(
                    "VS", color = Gold, fontSize = vsSize, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 18.dp).scale(vs.value).alpha((2f - vs.value).coerceIn(0f, 1f)),
                )
                Text(
                    b, color = Color.White, fontSize = nameSize, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
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
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xF0302008), Color(0xF2000000)))).clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        // A short landscape screen keeps the trophy smaller, so the name below never meets it.
        val short = maxHeight < 480.dp
        val textRoom = if (short) 104.dp else 150.dp
        Canvas(Modifier.fillMaxSize()) {
            val room = size.height - textRoom.toPx()
            val c = Offset(size.width / 2, room * 0.46f)
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
            val h = minOf(room * 0.78f, size.width * 0.5f)
            val y = c.y + h * 0.5f + rise.value * size.height * 0.6f - lift.value * h * 0.06f
            drawTrophy(c.x, y, h)
            // Confetti.
            for (p in pieces) p.draw(this, time)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (short) 14.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("CHAMPION", color = Gold, fontSize = if (short) 15.sp else 18.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text(name, color = Color.White, fontSize = if (short) 28.sp else 36.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
