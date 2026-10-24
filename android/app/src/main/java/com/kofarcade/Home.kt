package com.kofarcade

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kofarcade.core.Art
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.Layout
import com.kofarcade.core.artUrl

/** What the home screen shows: which games are on the phone, and the banner's game. */
data class HomeUi(
    val hasBios: Boolean,
    /** Game id -> the version on the phone (null: not added). */
    val sets: Map<String, String?>,
    val hero: Game,
    val heroLabel: String,
    val heroAction: String,
)

/**
 * Home: the logo and what you can do (multiplayer, library, BIOS), a banner for
 * the game you played last, then a shelf of title-screen cards per series.
 */
@Composable
fun HomeScreen(
    ui: HomeUi,
    onTwoPlayer: () -> Unit,
    onLibrary: () -> Unit,
    onBios: () -> Unit,
    onHero: () -> Unit,
    onGame: (Game) -> Unit,
    onLongGame: (Game) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Palette.bg).verticalScroll(rememberScrollState()).systemBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
            Text("90s KID ", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.text)
            Text("ARCADE", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.gold)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            HeaderChip("Multiplayer", gold = true, onClick = onTwoPlayer)
            HeaderChip("Library", onClick = onLibrary)
            HeaderChip(if (ui.hasBios) "BIOS ready" else "Add BIOS", dot = if (ui.hasBios) Palette.green else Palette.red, warn = !ui.hasBios, onClick = onBios)
        }
        Hero(ui.hero, ui.sets[ui.hero.id], ui.heroLabel, ui.heroAction, onHero)
        for ((series, games) in GAMES.groupBy { it.series }) {
            SectionLabel(series, Modifier.padding(start = 20.dp, top = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(games, key = { it.id }) { game ->
                    GameCard(game, ui.sets[game.id], onClick = { onGame(game) }, onLongClick = { onLongGame(game) })
                }
            }
        }
        Text(
            "ROMs are copied into this app's private storage. Long-press a game to remove it. " +
                "Game pictures: libretro thumbnails.",
            color = Palette.muted.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        )
    }
}

@Composable
private fun HeaderChip(text: String, gold: Boolean = false, dot: Color? = null, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(if (gold) Palette.gold else if (warn) Palette.red.copy(alpha = 0.15f) else Palette.panel)
            .border(1.dp, if (gold) Palette.gold else if (warn) Palette.red.copy(alpha = 0.5f) else Palette.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            color = if (gold) OnGold else Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Hero(game: Game, set: String?, label: String, action: String, onClick: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.panel)
            .clickable(onClick = onClick),
    ) {
        // Gameplay, blurred and darkened behind the banner.
        ArtImage(game, set, Art.SNAP, Modifier.matchParentSize().blur(14.dp), contentScale = ContentScale.Crop)
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Palette.bg.copy(alpha = 0.92f),
                    0.65f to Palette.bg.copy(alpha = 0.55f),
                    1f to Palette.bg.copy(alpha = 0.25f),
                ),
            ),
        )
        val wide = maxWidth > 520.dp
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.weight(1f)) {
                Kicker(label)
                Spacer(Modifier.height(4.dp))
                Text(
                    game.title,
                    color = Palette.text,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(game.subtitle ?: game.series, color = Palette.muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                GoldButton(action, onClick, play = action == "Play")
            }
            if (wide) {
                Spacer(Modifier.width(16.dp))
                ArtImage(
                    game,
                    set,
                    Art.TITLE,
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(game: Game, set: String?, onClick: () -> Unit, onLongClick: () -> Unit, width: Dp = 210.dp) {
    val ready = set != null
    Box(
        Modifier
            .width(width)
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel)
            .border(1.dp, if (ready) Color.White.copy(alpha = 0.14f) else Palette.line, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        ArtImage(game, set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, dim = !ready)
        // Bottom shade so the title reads on any picture.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        // Title and play button share a row, so the button is centred on the title.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    game.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                game.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp) }
            }
            if (ready) {
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape).background(Palette.gold)) {
                    PlayIcon(Modifier.size(12.dp))
                }
            }
        }
        if (!ready) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { Tag("Add ROM", Color.White) }
        }
    }
}

/**
 * A game picture from libretro's thumbnail collection, scaled without
 * smoothing so the pixel art stays crisp. Until it loads (or offline) a
 * tinted tile with the game's name shows instead.
 */
@Composable
fun ArtImage(game: Game, set: String?, kind: Art, modifier: Modifier, contentScale: ContentScale, dim: Boolean = false) {
    val tint = when (game.layout) {
        Layout.FIGHTER -> Palette.red
        Layout.SHOOTER -> Palette.green
        Layout.BRAWLER -> Palette.blue
    }
    Box(modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.45f), Palette.panel))), contentAlignment = Alignment.Center) {
        Text(
            game.title,
            color = Color.White.copy(alpha = 0.35f),
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        val url = artUrl(game, kind, set)
        // Screenshot tests have no network; the tile above stands in.
        if (url != null && !LocalInspectionMode.current) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = contentScale,
                filterQuality = FilterQuality.None,
                colorFilter = if (dim) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.15f) }) else null,
                alpha = if (dim) 0.55f else 1f,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
