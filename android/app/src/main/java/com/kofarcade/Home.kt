package com.kofarcade

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Home, like a console's: the game you played last fills the top (its
 * gameplay picture, the title, a gold button), then what you can do
 * (multiplayer, library, BIOS), then a row of game cards per series. The
 * banner's game is outlined in its row, like a console's selected tile.
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val landscape = maxWidth > maxHeight
        // Most of the screen in landscape; a bit under half in portrait, so the first row peeks in.
        val heroHeight = if (landscape) maxHeight * 0.68f else (maxHeight * 0.46f).coerceIn(320.dp, 460.dp)
        val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        val chips: @Composable () -> Unit = {
            HeaderChip("Multiplayer", gold = true, onClick = onTwoPlayer)
            HeaderChip("Library", onClick = onLibrary)
            HeaderChip(if (ui.hasBios) "BIOS ready" else "Add BIOS", dot = if (ui.hasBios) Palette.green else Palette.red, warn = !ui.hasBios, onClick = onBios)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Landscape has room for the chips beside the logo; portrait puts them under the banner.
            Hero(ui.hero, ui.sets[ui.hero.id], ui.heroLabel, ui.heroAction, heroHeight, landscape, onHero, chips.takeIf { landscape })
            Column(Modifier.windowInsetsPadding(sides)) {
                if (!landscape) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
                    ) { chips() }
                }
                val cardWidth = if (landscape) 230.dp else 196.dp
                for ((series, games) in GAMES.groupBy { it.series }) {
                    Text(
                        series,
                        color = Palette.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(games, key = { it.id }) { game ->
                            GameCard(
                                game,
                                ui.sets[game.id],
                                onClick = { onGame(game) },
                                onLongClick = { onLongGame(game) },
                                width = cardWidth,
                                selected = game == ui.hero,
                            )
                        }
                    }
                }
                Text(
                    "ROMs are copied into this app's private storage. Long-press a game to remove it. " +
                        "Game pictures: libretro thumbnails.",
                    color = Palette.muted.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String, gold: Boolean = false, dot: Color? = null, warn: Boolean = false, onClick: () -> Unit) {
    // The hero's button is the gold one: Multiplayer is gold-edged beside it, not filled.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (gold) Palette.gold.copy(alpha = 0.1f) else if (warn) Palette.red.copy(alpha = 0.15f) else Palette.panel)
            .border(1.dp, if (gold) Palette.gold.copy(alpha = 0.8f) else if (warn) Palette.red.copy(alpha = 0.5f) else Palette.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text.uppercase(),
            color = if (gold) Palette.gold else Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
    }
}

// The banner: the game's gameplay picture across the whole width, fading into
// the page, with the logo on top and the title and button at the bottom.
@Composable
private fun Hero(
    game: Game,
    set: String?,
    label: String,
    action: String,
    height: Dp,
    landscape: Boolean,
    onClick: () -> Unit,
    chips: (@Composable () -> Unit)?,
) {
    Box(Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
        ArtImage(game, set, Art.SNAP, Modifier.matchParentSize(), contentScale = ContentScale.Crop, label = false)
        // Dark at the top for the logo, then clear, then into the page's background.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Palette.bg.copy(alpha = 0.8f),
                    0.22f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.82f to Palette.bg.copy(alpha = 0.85f),
                    1f to Palette.bg,
                ),
            ),
        )
        // And from the left, so the title reads on any picture.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Palette.bg.copy(alpha = 0.6f), 0.7f to Color.Transparent)))
        val safe = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.windowInsetsPadding(safe).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        ) {
            Text("90s KID ", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.text)
            Text("ARCADE", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.gold)
            if (chips != null) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .fillMaxWidth(if (landscape) 0.6f else 1f)
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        ) {
            Kicker(label)
            Spacer(Modifier.height(2.dp))
            Text(
                game.title,
                color = Palette.text,
                fontSize = if (landscape) 46.sp else 40.sp,
                lineHeight = if (landscape) 48.sp else 42.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(game.subtitle ?: game.series, color = Palette.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            GoldButton(action, onClick, play = action == "Play" || action == "Continue")
        }
    }
}

/**
 * A game in a row: its title-screen picture, in full colour, with its name
 * under it. Outlined in gold when [selected], pressed or focused (a TV
 * remote or gamepad moves the focus), like a console's tiles; a tag on the
 * picture says when its ROM still has to be added.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: Game,
    set: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp = 210.dp,
    selected: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = selected || pressed || focused
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) {
        // The outline sits a few dp off the picture, so it reads as a frame, not a border.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .border(2.5.dp, if (lit) Palette.gold else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(5.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Palette.panel),
        ) {
            ArtImage(game, set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            if (set == null) PictureTag("Add ROM", Palette.red, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
            Text(
                game.title,
                color = if (lit) Palette.gold else Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                game.subtitle ?: if (set != null) "Ready to play" else "Not on this phone",
                color = Palette.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A game picture from libretro's thumbnail collection, scaled without
 * smoothing so the pixel art stays crisp. Until it loads (or offline) a
 * tinted tile (with the game's name, unless [label] is off) shows instead.
 */
@Composable
fun ArtImage(game: Game, set: String?, kind: Art, modifier: Modifier, contentScale: ContentScale, label: Boolean = true) {
    val tint = when (game.layout) {
        Layout.FIGHTER -> Palette.red
        Layout.SHOOTER -> Palette.green
        Layout.BRAWLER -> Palette.blue
    }
    Box(modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.45f), Palette.panel))), contentAlignment = Alignment.Center) {
        // Left off where the card shows the title anyway.
        if (label) {
            Text(
                game.title,
                color = Color.White.copy(alpha = 0.35f),
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
        val url = artUrl(game, kind, set)
        // Screenshot tests have no network; the tile above stands in.
        if (url != null && !LocalInspectionMode.current) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = contentScale,
                filterQuality = FilterQuality.None,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
