package com.kofarcade

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.Art
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.Layout
import com.kofarcade.core.artFile

/** What the home screen shows: which games are on the phone, and the banner's game. */
data class HomeUi(
    val hasBios: Boolean,
    /** Game id -> the version on the phone (null: not added). */
    val sets: Map<String, String?>,
    val hero: Game,
    val heroLabel: String,
    val heroAction: String,
    /** The banner's title and line under it, when it's about a step (adding the BIOS) rather than the game. */
    val heroTitle: String? = null,
    val heroText: String? = null,
    /** Your profile's name and icon, once made (else the chip just says Profile). */
    val profileName: String? = null,
    val profileIcon: Int? = null,
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
    onProfile: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val landscape = maxWidth > maxHeight
        val screenWidth = maxWidth
        // Most of the screen in landscape; a bit under half in portrait, so the first row peeks in.
        val heroHeight = if (landscape) maxHeight * 0.6f else (maxHeight * 0.46f).coerceIn(320.dp, 460.dp)
        val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        val chips: @Composable () -> Unit = {
            HeaderChip("Online", gold = true, onClick = onTwoPlayer)
            HeaderChip("Library", onClick = onLibrary)
            // Without the BIOS, the banner and the setup card already ask for it.
            if (ui.hasBios) HeaderChip("BIOS ready", dot = Palette.green, onClick = onBios)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Landscape has room for the chips beside the logo; portrait puts them under the banner.
            Hero(ui, heroHeight, landscape, onHero, chips.takeIf { landscape }, onProfile)
            Column(Modifier.windowInsetsPadding(sides)) {
                if (!landscape) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { chips() }
                }
                // First run: what's left to do before the first game, in order.
                val anyReady = ui.sets.values.any { it != null }
                if (!ui.hasBios || !anyReady) {
                    SetupCard(ui.hasBios, anyReady, onBios, onLibrary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp))
                }
                // Portrait: two cards and a peek of the third, so the row reads as scrollable.
                val cardWidth = if (landscape) 232.dp else ((screenWidth - 40.dp) / 2.25f).coerceIn(144.dp, 220.dp)
                for ((series, games) in GAMES.groupBy { it.series }) {
                    Text(
                        series,
                        color = Palette.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String, gold: Boolean = false, dot: Color? = null, warn: Boolean = false, onClick: () -> Unit) {
    // The hero's button is the gold one: Online is gold-edged beside it, not filled.
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .pressScale(source)
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (gold) Palette.gold.copy(alpha = 0.1f) else if (warn) Palette.red.copy(alpha = 0.15f) else Palette.panel)
            .border(1.dp, if (gold) Palette.gold.copy(alpha = 0.8f) else if (warn) Palette.red.copy(alpha = 0.5f) else Palette.line, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
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

/** Your profile, top right: its icon and name, or "Profile" before it's made. */
@Composable
private fun ProfileChip(name: String?, icon: Int?, onClick: () -> Unit, iconOnly: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    if (iconOnly) {
        ProfileIcon(
            icon ?: 0,
            44.dp,
            Modifier.pressScale(source).clip(CircleShape)
                .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
                .semantics { contentDescription = "Profile" + (name?.let { ": $it" } ?: ""); role = Role.Button },
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .pressScale(source)
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.bg.copy(alpha = 0.6f))
            .border(1.dp, Palette.line, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
    ) {
        ProfileIcon(icon ?: 0, 32.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            name ?: "PROFILE",
            color = Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (name == null) 1.sp else 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

// The banner: the game's gameplay picture across the whole width, fading into
// the page, with the logo on top and the title and button at the bottom.
@Composable
private fun Hero(
    ui: HomeUi,
    height: Dp,
    landscape: Boolean,
    onClick: () -> Unit,
    chips: (@Composable () -> Unit)?,
    onProfile: () -> Unit,
) {
    val game = ui.hero
    val action = ui.heroAction
    Box(Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
        ArtImage(game, ui.sets[game.id], Art.SNAP, Modifier.matchParentSize(), contentScale = ContentScale.Crop, label = false)
        // Dark at the top for the logo, then clear, then into the page's background.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Palette.bg.copy(alpha = 0.92f),
                    0.14f to Palette.bg.copy(alpha = 0.65f),
                    0.3f to Color.Transparent,
                    0.42f to Color.Transparent,
                    0.62f to Palette.bg.copy(alpha = 0.55f),
                    0.85f to Palette.bg.copy(alpha = 0.92f),
                    1f to Palette.bg,
                ),
            ),
        )
        // And from the left, so the title reads on any picture.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Palette.bg.copy(alpha = 0.7f), 0.75f to Color.Transparent)))
        val safe = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.windowInsetsPadding(safe).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            Text("90s KID ", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.text)
            Text("ARCADE", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.gold)
            Spacer(Modifier.weight(1f))
            if (chips != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
                Spacer(Modifier.width(8.dp))
            }
            // Your profile, top right, as on a console.
            // Portrait has room for the icon alone beside the logo.
            ProfileChip(ui.profileName.takeIf { landscape }, ui.profileIcon, onProfile, iconOnly = !landscape)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .fillMaxWidth(if (landscape) 0.6f else 1f)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Kicker(ui.heroLabel)
            Spacer(Modifier.height(2.dp))
            val title = ui.heroTitle ?: game.title
            // A step's title is a sentence: a size smaller than a game's name.
            val big = if (ui.heroTitle != null) 32 else if (landscape) 46 else 40
            Text(
                title,
                color = Palette.text,
                fontSize = big.sp,
                lineHeight = (big + 2).sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 16f)),
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.heroText ?: game.subtitle ?: game.series,
                color = Palette.text.copy(alpha = 0.8f),
                fontSize = 14.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 10f)),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            GoldButton(action, onClick, play = action == "Play" || action == "Continue")
        }
    }
}

/**
 * A game in a row: its title-screen picture (grey until its ROM is added), with its name
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
    val outlined = selected || lit(source)
    Column(
        Modifier
            .width(width)
            .pressScale(source)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) {
        // The outline sits a few dp off the picture, so it reads as a frame, not a border.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .border(2.5.dp, if (outlined) Palette.gold else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(5.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Palette.panel)
                // A faint rim, so a black title screen still has an edge on the black page.
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(15.dp)),
        ) {
            ArtImage(game, set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, grey = set == null)
            if (set == null) PictureTag("Add ROM", Palette.red, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
            Text(
                game.title,
                color = if (outlined) Palette.gold else Palette.text,
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
 * First run: the steps before the first game (the BIOS, a game), each ticked
 * off once done, the next one's number in gold.
 */
@Composable
private fun SetupCard(hasBios: Boolean, anyReady: Boolean, onBios: () -> Unit, onLibrary: () -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Kicker("Get set up")
        Spacer(Modifier.height(4.dp))
        Text("Two steps to your first game", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Spacer(Modifier.height(16.dp))
        SetupStep(1, "Add the Neo Geo BIOS", "neogeo.zip, from your library or this phone", hasBios, "Add", onBios, next = !hasBios)
        Spacer(Modifier.height(12.dp))
        SetupStep(2, "Add a game", "Tap one below, or get them from your library", anyReady, "Library", onLibrary, next = hasBios && !anyReady)
    }
}

@Composable
private fun SetupStep(number: Int, title: String, sub: String, done: Boolean, action: String, onAction: () -> Unit, next: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape)
                .background(if (done) Palette.green.copy(alpha = 0.16f) else if (next) Palette.gold else Palette.bg)
                .border(1.dp, if (done) Palette.green.copy(alpha = 0.6f) else if (next) Palette.gold else Palette.line, CircleShape),
        ) {
            if (done) {
                CheckIcon(Modifier.size(14.dp), Palette.green)
            } else {
                Text("$number", color = if (next) OnGold else Palette.muted, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (done) Palette.muted else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (done) "Done" else sub, color = if (done) Palette.green else Palette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            // Outlined: the banner's gold button is the screen's one main action.
            GhostButton(action, onAction, compact = true)
        }
    }
}

/**
 * Screenshot tests only: where [ArtImage] gets its pictures from (by
 * artFile) instead of the app's own, so the snapshots show the real ones.
 */
val LocalArtOverride = staticCompositionLocalOf<((String) -> ImageBitmap?)?> { null }

/**
 * A game picture (packed in the app: GameArt), scaled without smoothing so
 * the pixel art stays crisp. Until it's decoded, a tinted tile (with the
 * game's name, unless [label] is off) shows instead.
 */
@Composable
fun ArtImage(
    game: Game,
    set: String?,
    kind: Art,
    modifier: Modifier,
    contentScale: ContentScale,
    label: Boolean = true,
    /** Greyed out: the game isn't on the phone yet. */
    grey: Boolean = false,
) {
    val filter = if (grey) GREY else null
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
        // The version's own picture, else the game's.
        val files = listOfNotNull(artFile(game, kind, set), artFile(game, kind)).distinct()
        val override = LocalArtOverride.current
        val context = LocalContext.current
        val picture = when {
            files.isEmpty() -> null
            override != null -> files.firstNotNullOfOrNull(override)
            LocalInspectionMode.current -> null
            // Its own state for each game's picture: one kept across games
            // showed the last game's picture after another was played.
            else -> key(files) {
                produceState(files.firstNotNullOfOrNull(GameArt::cached)) {
                    if (value == null) value = withContext(Dispatchers.IO) { files.firstNotNullOfOrNull { GameArt.load(context, it) } }
                }.value
            }
        }
        if (picture != null) {
            Image(picture, null, Modifier.matchParentSize(), contentScale = contentScale, filterQuality = FilterQuality.None, colorFilter = filter)
        }
        if (grey) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}

// Greyscale, for the pictures of games not on the phone yet.
private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
