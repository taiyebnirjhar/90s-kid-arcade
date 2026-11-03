package com.kofarcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.gameById
import com.kofarcade.core.replay.MatchFile
import com.kofarcade.core.replay.MatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved match, as the list shows it. */
data class ReplayRow(
    val file: String,
    val info: MatchInfo,
    val starred: Boolean,
    /** Its game is on this phone (it can be watched). */
    val playable: Boolean,
)

/** The saved matches (docs/recording-plan.md): watch one, star it to keep it, or delete it. */
@Composable
fun ReplaysDialog(prefs: Prefs, roms: RomStore, onWatch: (ReplayRow, video: Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MatchStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<ReplayRow>?>(null) }
    var space by remember { mutableStateOf(0L) }
    var saving by remember { mutableStateOf(prefs.saveMatches) }
    BackHandler(onBack = onClose)
    LaunchedEffect(version) {
        rows = withContext(Dispatchers.IO) {
            store.list().mapNotNull { m ->
                val info = runCatching { File(store.dir, m.name).inputStream().buffered().use(MatchFile::readInfo) }.getOrNull()
                    ?: return@mapNotNull null
                val playable = gameById(info.game)?.let { roms.setFor(it) != null } == true
                ReplayRow(m.name, info, m.starred, playable)
            }
        }
        space = withContext(Dispatchers.IO) { store.bytes() }
    }
    ReplaysScreen(
        ReplaysUi(rows, saving, space),
        onBack = onClose,
        onWatch = { onWatch(it, false) },
        onVideo = { onWatch(it, true) },
        onStar = { row ->
            store.star(row.file, !row.starred)
            version++
        },
        onDelete = { row ->
            File(store.dir, row.file).delete()
            store.star(row.file, false)
            version++
        },
        onSaving = {
            prefs.saveMatches = it
            saving = it
        },
    )
}

/** The list's state, as plain data (so it can be previewed). */
data class ReplaysUi(
    /** null while it's read. */
    val rows: List<ReplayRow>?,
    val saving: Boolean,
    val bytes: Long = 0,
)

@Composable
fun ReplaysScreen(
    ui: ReplaysUi,
    onBack: () -> Unit,
    onWatch: (ReplayRow) -> Unit,
    onVideo: (ReplayRow) -> Unit = {},
    onStar: (ReplayRow) -> Unit,
    onDelete: (ReplayRow) -> Unit,
    onSaving: (Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf<ReplayRow?>(null) }
    ArcadeScreen(title = "Replays", kicker = "Your matches", onBack = onBack) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                "Save my matches", ui.saving, onSaving,
                sub = if (ui.saving) "The last 20 are kept, and starred ones always" else "Off: new matches aren't saved",
            )
            val rows = ui.rows
            when {
                rows == null -> repeat(3) { Skeleton(Modifier.fillMaxWidth().height(76.dp), RoundedCornerShape(18.dp)) }
                rows.isEmpty() -> EmptyState(
                    "No matches yet",
                    if (ui.saving) "Play a game: each match is saved here by itself." else "Turn on Save my matches above, then play: your matches show up here.",
                    icon = { RecordIcon(Modifier.size(32.dp)) },
                )
                else -> {
                    rows.forEach { row -> ReplayCard(row, onWatch = { onWatch(row) }, onVideo = { onVideo(row) }, onStar = { onStar(row) }, onDelete = { asking = row }) }
                    Text(
                        "${rows.size} saved · ${space(ui.bytes)} on this phone · Video plays the match through once to make it",
                        color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    asking?.let { row ->
        ArcadeDialog(
            title = "Delete this match?",
            message = "${row.info.title}, ${whenSaved(row.info.startedAt)}. It can't be brought back.",
            onDismiss = { asking = null },
            primary = "Keep it" to { asking = null },
            secondary = "Delete" to {
                asking = null
                onDelete(row)
            },
            dangerSecondary = true,
        )
    }
}

@Composable
private fun ReplayCard(row: ReplayRow, onWatch: () -> Unit, onVideo: () -> Unit, onStar: () -> Unit, onDelete: () -> Unit) {
    val info = row.info
    Panel(onClick = if (row.playable) onWatch else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp).clip(CircleShape)
                    .background(if (row.playable) Palette.gold else Palette.line),
            ) { PlayIcon(Modifier.size(16.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(info.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val who = info.players.filter { it.isNotBlank() }
                Text(
                    listOfNotNull(
                        if (who.size >= 2) who.joinToString(" vs ") else if (info.room) "Room match" else null,
                        length(info.seconds),
                        whenSaved(info.startedAt),
                    ).joinToString(" · "),
                    color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!row.playable) Text("Its game isn't on this phone", color = Palette.red, fontSize = 12.sp)
            }
            if (row.playable) {
                GhostButton("Video", onVideo, compact = true)
                Spacer(Modifier.width(4.dp))
            }
            IconSpot("Star", onStar) { StarIcon(Modifier.size(20.dp), on = row.starred) }
            IconSpot("Delete", onDelete) { Text("✕", color = Palette.muted, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun IconSpot(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
    ) { content() }
}

/** A five-pointed star, filled gold when [on]. */
@Composable
fun StarIcon(modifier: Modifier = Modifier, on: Boolean) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = size.minDimension / 2f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) c else c * 0.45f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = c + r * Math.cos(a).toFloat()
            val y = c + r * Math.sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (on) drawPath(path, Palette.gold)
        else drawPath(path, Palette.muted, style = androidx.compose.ui.graphics.drawscope.Stroke(width = c * 0.14f))
    }
}

private fun space(bytes: Long): String =
    if (bytes < 1_048_576) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / 1_048_576.0)

private fun length(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun whenSaved(at: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(at))
