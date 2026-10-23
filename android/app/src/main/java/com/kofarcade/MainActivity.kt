package com.kofarcade

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.matchParentSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.Layout
import com.kofarcade.core.artUrl
import com.kofarcade.core.gameById

/**
 * Home: a "Continue" banner for the game you played last, then a shelf of
 * title-screen cards per series. Tap a card to play, or to add its ROM.
 */
class MainActivity : ComponentActivity() {
    private lateinit var roms: RomStore
    private lateinit var prefs: Prefs
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        roms = RomStore(this)
        prefs = Prefs(this)
        setContent { KofTheme { Home() } }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    private fun play(game: Game) {
        val set = roms.setFor(game) ?: return
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_GAME, game.id)
                .putExtra(GameActivity.EXTRA_SET, set),
        )
    }

    /** What the file picker is for: the BIOS, or a game's ROM. */
    private sealed interface Pick {
        data object Bios : Pick
        data class Rom(val game: Game) : Pick
    }

    @Composable
    private fun Home() {
        var pending by remember { mutableStateOf<Pick?>(null) }
        var ask by remember { mutableStateOf<Pick?>(null) }
        var remove by remember { mutableStateOf<Game?>(null) }
        // Re-read what's on disk whenever `refresh` changes (after a pick or a return from a game).
        val hasBios = remember(refresh) { roms.has(BIOS) }
        val sets = remember(refresh) { GAMES.associate { it.id to roms.setFor(it) } }
        val last = remember(refresh) { prefs.lastGame?.let(::gameById) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pending
            pending = null
            if (uri == null || target == null) return@rememberLauncherForActivityResult
            try {
                when (target) {
                    Pick.Bios -> roms.import(uri, BIOS)
                    is Pick.Rom -> {
                        roms.import(uri, target.game.sets.first() + ".zip", target.game.sets)
                        if (roms.has(BIOS)) play(target.game)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't add the file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refresh++
        }
        fun launch(p: Pick) {
            pending = p
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }

        fun open(game: Game) = when {
            !hasBios -> ask = Pick.Bios
            sets[game.id] == null -> ask = Pick.Rom(game)
            else -> play(game)
        }

        // The banner shows the last game played, else the first one ready, else KOF '97.
        val hero = last?.takeIf { sets[it.id] != null }
            ?: GAMES.firstOrNull { sets[it.id] != null }
            ?: GAMES.first()

        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding()) {
                Header(hasBios) { if (hasBios) ask = Pick.Bios else launch(Pick.Bios) }
                Hero(
                    game = hero,
                    label = when {
                        !hasBios -> "GET STARTED"
                        hero == last && sets[hero.id] != null -> "CONTINUE"
                        sets[hero.id] != null -> "READY TO PLAY"
                        else -> "GET STARTED"
                    },
                    action = when {
                        !hasBios -> "Add Neo Geo BIOS"
                        sets[hero.id] == null -> "Add ROM"
                        else -> "Play"
                    },
                    onClick = { if (!hasBios) launch(Pick.Bios) else open(hero) },
                )
                for ((series, games) in GAMES.groupBy { it.series }) {
                    Text(
                        series.uppercase(),
                        color = Palette.muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(games, key = { it.id }) { game ->
                            val set = sets[game.id]
                            GameCard(
                                game,
                                ready = set != null,
                                onClick = { open(game) },
                                onLongClick = { if (set != null) remove = game },
                            )
                        }
                    }
                }
                Text(
                    "ROMs are copied into this app's private storage and never leave your phone. " +
                        "Long-press a game to remove its ROM. Game pictures: libretro thumbnails.",
                    color = Palette.muted.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }

        ask?.let { p ->
            val (title, body) = when (p) {
                Pick.Bios -> if (hasBios) {
                    "Replace the Neo Geo BIOS?" to "Pick another neogeo.zip to use instead of the current one."
                } else {
                    "Add the Neo Geo BIOS" to "Every game needs neogeo.zip. Pick it from your phone."
                }
                is Pick.Rom -> "Add ${p.game.title}" to
                    "Pick your ${p.game.sets.joinToString(" or ") { "$it.zip" }}. Any file name works."
            }
            AlertDialog(
                onDismissRequest = { ask = null },
                title = { Text(title) },
                text = { Text(body) },
                confirmButton = {
                    TextButton(onClick = {
                        ask = null
                        launch(p)
                    }) { Text("Choose file") }
                },
                dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
            )
        }

        remove?.let { game ->
            AlertDialog(
                onDismissRequest = { remove = null },
                title = { Text("Remove ${game.title}?") },
                text = { Text("Deletes the ROM from this app. You can add it again any time.") },
                confirmButton = {
                    TextButton(onClick = {
                        sets[game.id]?.let { roms.remove("$it.zip") }
                        remove = null
                        refresh++
                    }) { Text("Remove", color = Palette.red) }
                },
                dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun Header(hasBios: Boolean, onBios: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Text("KOF ", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.text)
            Text("ARCADE", fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.gold)
            Spacer(Modifier.weight(1f))
            // BIOS status: green when ready, a red call to action when missing.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (hasBios) Palette.panel else Palette.red.copy(alpha = 0.15f))
                    .border(1.dp, if (hasBios) Palette.line else Palette.red.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(onClick = onBios)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (hasBios) Palette.green else Palette.red))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasBios) "BIOS ready" else "Add Neo Geo BIOS",
                    color = Palette.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    @Composable
    private fun Hero(game: Game, label: String, action: String, onClick: () -> Unit) {
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
            ArtImage(
                game,
                Art.SNAP,
                Modifier.matchParentSize().blur(14.dp),
                contentScale = ContentScale.Crop,
            )
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(18.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Palette.gold, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Palette.gold)
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text(
                            if (action == "Play") "▶  PLAY" else action.uppercase(),
                            color = Color(0xFF1D1400),
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
                if (wide) {
                    Spacer(Modifier.width(16.dp))
                    ArtImage(
                        game,
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
    private fun GameCard(game: Game, ready: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, width: Dp = 210.dp) {
        Box(
            Modifier
                .width(width)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.panel)
                .border(1.dp, if (ready) Color.White.copy(alpha = 0.14f) else Palette.line, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            ArtImage(game, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, dim = !ready)
            // Bottom shade so the title reads on any picture.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Palette.gold),
                ) { Text("▶", color = Color(0xFF1D1400), fontSize = 13.sp) }
            } else {
                Text(
                    "ADD ROM",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }

    /**
     * A game picture from libretro's thumbnail collection, scaled without
     * smoothing so the pixel art stays crisp. Until it loads (or offline) a
     * tinted tile with the game's name shows instead.
     */
    @Composable
    private fun ArtImage(game: Game, kind: Art, modifier: Modifier, contentScale: ContentScale, dim: Boolean = false) {
        val tint = when (game.layout) {
            Layout.FIGHTER -> Palette.red
            Layout.SHOOTER -> Palette.green
            Layout.BRAWLER -> Palette.blue
        }
        Box(
            modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.45f), Palette.panel))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                game.title,
                color = Color.White.copy(alpha = 0.35f),
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            val url = artUrl(game, kind)
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = contentScale,
                    filterQuality = FilterQuality.None,
                    colorFilter = if (dim) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.15f) })
                    } else {
                        null
                    },
                    alpha = if (dim) 0.55f else 1f,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}
