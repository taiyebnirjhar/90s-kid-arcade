package com.kofarcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.BIOS
import com.kofarcade.core.gameForSet
import com.kofarcade.core.romSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A game on this phone, for the Storage page: what it's called, how big, and what removing it means. */
data class StoredGame(
    val id: String,
    val title: String,
    val bytes: Long,
    val psp: Boolean = false,
    /** Removing it also stops these (hacks that borrow its files; every Neo Geo game for the BIOS). */
    val warning: String? = null,
)

data class StorageUi(val games: List<StoredGame>?, val freeBytes: Long)

/**
 * Storage (the owner, 2026-11-08: "once game is added there is no way to
 * delete them ... now they want to free space"): every game on the phone,
 * biggest first, with its size and Remove. A PSP game's own saves and where it
 * was left stay (Export save keeps them anywhere), so adding it back goes on
 * from there.
 */
@Composable
fun StorageDialog(roms: RomStore, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf(StorageUi(null, 0)) }
    BackHandler(onBack = onClose)
    LaunchedEffect(version) {
        ui = withContext(Dispatchers.IO) { storageUi(context.filesDir, roms) }
    }
    StorageScreen(
        ui,
        onBack = onClose,
        onRemove = { g ->
            if (g.psp) File(context.filesDir, "psp/games/${g.id}").delete() else roms.remove("${g.id}.zip")
            version++
            onChanged()
        },
    )
}

private fun storageUi(filesDir: File, roms: RomStore): StorageUi {
    val zips = roms.zips()
    val arcade = zips.map { set ->
        val file = roms.file("$set.zip")
        val game = gameForSet(set)
        val bios = "$set.zip" == BIOS
        val borrowers = zips.filter { romSet(it)?.parent == set && gameForSet(it) != game }.mapNotNull(::gameForSet)
        StoredGame(
            id = set,
            title = if (bios) "Neo Geo BIOS" else game?.fullTitle ?: set,
            bytes = file.length(),
            warning = when {
                bios -> "Every Neo Geo game needs it: they won't start without it."
                borrowers.isNotEmpty() -> "${borrowers.joinToString(" and ") { it.fullTitle }} uses files from it and won't start without it."
                else -> null
            },
        )
    }
    val psp = PspGames.list(File(filesDir, "psp/games")).map { f ->
        StoredGame(id = f.name, title = PspGames.title(f), bytes = f.length(), psp = true)
    }
    return StorageUi((arcade + psp).sortedByDescending { it.bytes }, filesDir.usableSpace)
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "%.0f MB".format(bytes / 1e6).let { if (it == "0 MB") "under 1 MB" else it }
}

@Composable
fun StorageScreen(ui: StorageUi, onBack: () -> Unit, onRemove: (StoredGame) -> Unit) {
    var asking by remember { mutableStateOf<StoredGame?>(null) }
    ArcadeScreen(title = "Storage", kicker = "Free up space", onBack = onBack) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val games = ui.games
            if (games == null) {
                Text("Looking…", color = Palette.muted, fontSize = 14.sp)
                return@Column
            }
            Text(
                "Games use ${size(games.sumOf { it.bytes })} · ${size(ui.freeBytes)} free on this phone",
                color = Palette.muted, fontSize = 13.sp,
            )
            if (games.isEmpty()) {
                Text("No games on this phone yet.", color = Palette.muted, fontSize = 14.sp)
                return@Column
            }
            Panel {
                games.forEachIndexed { i, g ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(g.title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text((if (g.psp) "PSP · " else "") + size(g.bytes), color = Palette.muted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        GhostButton("Remove", { asking = g }, compact = true, danger = true)
                    }
                }
            }
            Text(
                "Removing a game frees its space. You can add it again any time from your library or the phone's files." +
                    " A PSP game's saves stay on the phone.",
                color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
    asking?.let { g ->
        ArcadeDialog(
            title = "Remove ${g.title}?",
            message = "Frees ${size(g.bytes)}." + (g.warning?.let { " $it" } ?: "") +
                if (g.psp) " Its saves stay: add it back and it goes on from there." else " You can add it again any time.",
            onDismiss = { asking = null },
            tone = Tone.BAD,
            primary = "Keep it" to { asking = null },
            secondary = "Remove" to {
                onRemove(g)
                asking = null
            },
            dangerSecondary = true,
        )
    }
}
