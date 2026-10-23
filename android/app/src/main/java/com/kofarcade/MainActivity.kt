package com.kofarcade

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.Layout

/** Home: the fixed list of supported games. Tap one to play, or to add its ROM. */
class MainActivity : ComponentActivity() {
    private lateinit var roms: RomStore
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roms = RomStore(this)
        setContent { KofTheme { Catalog() } }
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Catalog() {
        var pending by remember { mutableStateOf<Pick?>(null) }
        var ask by remember { mutableStateOf<Pick?>(null) }
        var remove by remember { mutableStateOf<Game?>(null) }
        // Re-read what's on disk whenever `refresh` changes (after a pick or a return from a game).
        val hasBios = remember(refresh) { roms.has(BIOS) }
        val sets = remember(refresh) { GAMES.associate { it.id to roms.setFor(it) } }

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
            !roms.has(BIOS) -> ask = Pick.Bios
            roms.setFor(game) == null -> ask = Pick.Rom(game)
            else -> play(game)
        }

        Box(Modifier.fillMaxSize().background(Palette.bg).systemBarsPadding()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header() }
                item(span = { GridItemSpan(maxLineSpan) }) { BiosCard(hasBios) { launch(Pick.Bios) } }
                for ((series, games) in GAMES.groupBy { it.series }) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            series.uppercase(),
                            color = Palette.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(top = 18.dp, start = 2.dp),
                        )
                    }
                    items(games, key = { it.id }) { game ->
                        val set = sets[game.id]
                        GameCard(
                            game,
                            set,
                            onClick = { open(game) },
                            onLongClick = { if (set != null) remove = game },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "ROMs are copied into this app's private storage and never leave your phone. " +
                            "Long-press a game to remove its ROM.",
                        color = Palette.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                    )
                }
            }
        }

        ask?.let { p ->
            val (title, body) = when (p) {
                Pick.Bios -> "Add the Neo Geo BIOS" to "Every game needs neogeo.zip. Pick it from your phone."
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
                        roms.setFor(game)?.let { roms.remove("$it.zip") }
                        remove = null
                        refresh++
                    }) { Text("Remove", color = Palette.red) }
                },
                dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun Header() {
        Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            Row {
                Text("KOF ", fontSize = 34.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.text)
                Text("Arcade", fontSize = 34.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Palette.gold)
            }
            Text("Neo Geo classics, made for touch.", color = Palette.muted)
        }
    }

    @Composable
    private fun BiosCard(ready: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (ready) Palette.panel else Palette.red.copy(alpha = 0.08f))
                .border(1.dp, if (ready) Palette.line else Palette.red.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ready) Palette.green else Palette.red))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Neo Geo BIOS", fontWeight = FontWeight.Bold, color = Palette.text)
                Text(
                    if (ready) "Ready" else "Needed by every game: add neogeo.zip",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
            }
            if (ready) {
                Text("✓", color = Palette.green, fontSize = 18.sp)
            } else {
                Pill("Add", filled = true)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GameCard(game: Game, set: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
        val tint = when (game.layout) {
            Layout.FIGHTER -> Palette.red
            Layout.SHOOTER -> Palette.green
            Layout.BRAWLER -> Palette.blue
        }
        Column(
            Modifier
                .heightIn(min = 100.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.panel)
                .background(
                    Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.22f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
                        radius = 420f,
                    ),
                )
                .border(BorderStroke(1.dp, Palette.line), RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp),
        ) {
            Text(game.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = Palette.text)
            Text(game.subtitle ?: "${set ?: game.sets.first()}.zip", fontSize = 12.sp, color = Palette.muted)
            Spacer(Modifier.size(10.dp))
            Pill(if (set != null) "Play" else "Add ROM", filled = set != null)
        }
    }

    @Composable
    private fun Pill(text: String, filled: Boolean) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color(0xFF1D1400) else Palette.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (filled) Palette.gold else Color.Transparent)
                .border(1.dp, if (filled) Palette.gold else Palette.line, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
