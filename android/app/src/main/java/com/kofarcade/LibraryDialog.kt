package com.kofarcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.Art
import com.kofarcade.core.Game
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameForSet
import com.kofarcade.core.setName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One library file as the page shows it; [game] and [set] pick its picture. */
data class LibraryRowUi(
    val file: String,
    val title: String,
    val detail: String,
    val state: RowState,
    val game: Game? = null,
    val set: String? = null,
)

sealed interface RowState {
    data object Idle : RowState
    data object OnPhone : RowState
    data class Downloading(val fraction: Float) : RowState
    /** [retry]: it didn't come (the network, or cancelled): it can be got again. */
    data class Done(val message: String, val ok: Boolean, val retry: Boolean = false) : RowState
}

/** Everything the library page shows. */
data class LibraryUi(
    val askKey: Boolean,
    val loading: Boolean = false,
    val error: String? = null,
    val rows: List<LibraryRowUi> = emptyList(),
    val busy: Boolean = false,
)

/** One library file's download: bytes so far, then what the check found. */
private data class Download(val done: Long = 0, val result: String? = null, val ok: Boolean = false, val retry: Boolean = false)

/**
 * Your private ROM library: asks for the key once (the address is built
 * in, see [Library.DEFAULT_URL], and can be changed), then lists its zips
 * with a button each. Every download is checked like an import, so a broken
 * zip says what's missing instead of failing in the game.
 */
@Composable
fun LibraryDialog(prefs: Prefs, roms: RomStore, onChanged: () -> Unit, onClose: () -> Unit) {
    var editing by remember { mutableStateOf(prefs.libraryKey == null) }
    var entries by remember { mutableStateOf<List<Library.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Download>() }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var key by remember { mutableStateOf(prefs.libraryKey.orEmpty()) }
    var url by remember { mutableStateOf(prefs.libraryUrl) }
    var showUrl by remember { mutableStateOf(prefs.libraryUrl != Library.DEFAULT_URL) }
    val scope = rememberCoroutineScope()

    fun library() = Library(prefs.libraryUrl, prefs.libraryKey.orEmpty())

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { library().list() }
            } catch (e: Library.WrongKey) {
                error = "Wrong key. Check it and try again."
                editing = true
            } catch (e: Exception) {
                error = "Couldn't reach the library: ${e.message}"
            }
            loading = false
        }
    }

    fun installed(entry: Library.Entry): Boolean = prefs.librarySet(entry.name)?.let { roms.has("$it.zip") } == true

    suspend fun download(entry: Library.Entry) {
        downloads[entry.name] = Download()
        try {
            val (check, set) = withContext(Dispatchers.IO) {
                // Its own scratch file: a download left running by a rotated
                // screen can't trip over the next one.
                val tmp = roms.partFile("library")
                try {
                    // The card's progress moves by whole percents: not a redraw of the page per 64 KB.
                    var shown = -1L
                    library().download(entry.name, tmp, cancelled = { !coroutineContext.isActive }) {
                        val pct = if (entry.size > 0) it * 100 / entry.size else it shr 20
                        if (pct != shown) {
                            shown = pct
                            downloads[entry.name] = Download(it)
                        }
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                val check = roms.add(tmp)
                val set = when (check) {
                    RomCheck.Bios -> "neogeo"
                    is RomCheck.Game -> check.set.name
                    else -> null
                }
                // Noted here, so a download that finishes after the screen
                // was rotated still shows as on the phone.
                prefs.setLibrarySet(entry.name, set)
                check to set
            }
            downloads[entry.name] = Download(entry.size, check.message(), ok = set != null)
            onChanged()
        } catch (e: Exception) {
            val why = if (e is kotlinx.coroutines.CancellationException || e is java.io.InterruptedIOException) "Cancelled" else "Download failed: ${e.message ?: e.javaClass.simpleName}"
            downloads[entry.name] = Download(0, "$why. Tap to try again.", retry = true)
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun downloadAll(picked: List<Library.Entry>) {
        busy = true
        job = scope.launch {
            try {
                // The BIOS first, so the games can be played as soon as they land.
                picked.sortedBy { if (setName(it.name).startsWith("neogeo")) 0 else 1 }.forEach { download(it) }
            } finally {
                busy = false
                job = null
            }
        }
    }

    LaunchedEffect(Unit) { if (!editing) load() }
    // Back while downloading stops the downloads (what's done stays); back again leaves.
    BackHandler {
        if (busy) {
            job?.cancel()
            android.widget.Toast.makeText(context, "Downloads stopped", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            onClose()
        }
    }

    val rows = entries.orEmpty().map { e ->
        // Once downloaded, the check says what it is; before, the file name
        // usually does ("sengoku3 (1).zip" -> Sengoku 3).
        val set = prefs.librarySet(e.name) ?: setName(e.name).replace(Regex(""" \(\d+\)$"""), "")
        val game = gameForSet(set)
        val title = if (set == "neogeo") "Neo Geo BIOS" else game?.fullTitle
        val d = downloads[e.name]
        LibraryRowUi(
            game = game,
            set = set,
            file = e.name,
            title = title ?: e.name.removeSuffix(".zip"),
            detail = (if (title != null) e.name + " · " else "") + "%.1f MB".format(e.size / 1_048_576.0),
            state = when {
                d != null && d.result == null -> RowState.Downloading(if (e.size > 0) d.done.toFloat() / e.size else 0f)
                d?.result != null -> RowState.Done(d.result, d.ok, d.retry)
                installed(e) -> RowState.OnPhone
                else -> RowState.Idle
            },
        )
    }
    LibraryScreen(
        ui = LibraryUi(askKey = editing, loading = loading, error = error, rows = rows, busy = busy),
        key = key,
        onKey = { key = it },
        url = url,
        onUrl = { url = it },
        showUrl = showUrl,
        onShowUrl = { showUrl = true },
        onConnect = {
            prefs.libraryUrl = url.trim()
            prefs.libraryKey = key.trim()
            editing = false
            load()
        },
        onChangeKey = { editing = true },
        onRetry = ::load,
        onDownload = { file -> entries?.find { it.name == file }?.let { downloadAll(listOf(it)) } },
        onDownloadAll = { downloadAll(entries.orEmpty().filterNot(::installed)) },
        onBack = { if (!busy) onClose() },
    )
}

/** The library page itself, from plain data (so it can be previewed). */
@Composable
fun LibraryScreen(
    ui: LibraryUi,
    key: String,
    onKey: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    showUrl: Boolean,
    onShowUrl: () -> Unit,
    onConnect: () -> Unit,
    onChangeKey: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (String) -> Unit,
    onDownloadAll: () -> Unit,
    onBack: () -> Unit,
) {
    val listed = !ui.askKey && !ui.loading && ui.error == null && ui.rows.isNotEmpty()
    val waiting = ui.rows.count { it.state == RowState.Idle || (it.state as? RowState.Done)?.retry == true }
    ArcadeScreen(
        title = "My Library",
        kicker = "Your games, one tap away",
        onBack = onBack,
        actions = { if (!ui.askKey) GhostButton("Key", onChangeKey, compact = true) },
        // The page's one main action, in thumb reach.
        bottomBar = if (listed && (waiting > 0 || ui.busy)) {
            {
                GoldButton(
                    if (ui.busy) "Downloading…" else "Get all $waiting",
                    onDownloadAll,
                    Modifier.fillMaxWidth(),
                    enabled = !ui.busy,
                )
            }
        } else {
            null
        },
    ) {
        when {
            ui.askKey -> Panel {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Palette.gold.copy(alpha = 0.14f)),
                ) { LockIcon(Modifier.size(22.dp)) }
                Spacer(Modifier.height(16.dp))
                Text("Unlock your library", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(8.dp))
                Text("Enter your library key to download your games. It stays on this phone.", color = Palette.muted, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                ArcadeField(key, onKey, "Key", password = true, placeholder = "••••••••", onDone = { if (key.isNotBlank() && url.isNotBlank()) onConnect() })
                if (showUrl) {
                    Spacer(Modifier.height(12.dp))
                    ArcadeField(url, onUrl, "Library address", keyboardType = KeyboardType.Uri)
                }
                ui.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Palette.red)
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Palette.red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(24.dp))
                GoldButton("Unlock", onConnect, Modifier.fillMaxWidth(), enabled = key.isNotBlank() && url.isNotBlank())
                if (!showUrl) {
                    Text(
                        "Use another library",
                        color = Palette.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onShowUrl).padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
            // Stand-in cards where the games will be, so the page doesn't jump when they arrive.
            ui.loading -> {
                Skeleton(Modifier.width(180.dp).height(20.dp))
                Spacer(Modifier.height(8.dp))
                Skeleton(Modifier.width(120.dp).height(14.dp))
                Spacer(Modifier.height(16.dp))
                CardGrid(List(6) { it }) { _, m ->
                    Column(m) {
                        Skeleton(Modifier.fillMaxWidth().aspectRatio(4f / 3f), RoundedCornerShape(16.dp))
                        Spacer(Modifier.height(8.dp))
                        Skeleton(Modifier.fillMaxWidth(0.7f).height(16.dp))
                        Spacer(Modifier.height(4.dp))
                        Skeleton(Modifier.fillMaxWidth(0.5f).height(12.dp))
                    }
                }
            }
            ui.error != null -> EmptyState(
                "Can't reach the library",
                ui.error,
                tone = Tone.BAD,
                action = "Try again" to onRetry,
            )
            ui.rows.isEmpty() -> EmptyState(
                "Your library is empty",
                "Upload game zips (and neogeo.zip) to your R2 bucket, then open this page again.",
                icon = { LockIcon(Modifier.size(24.dp), open = true) },
            )
            else -> {
                Text("${ui.rows.size} games and files", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text(if (waiting == 0) "All on this phone" else "$waiting not on this phone yet", color = Palette.muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                CardGrid(ui.rows) { row, m -> LibraryCard(row, ui.busy, m) { onDownload(row.file) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** [items] as a grid: two cards a row in portrait, more as the screen widens. */
@Composable
private fun <T> CardGrid(items: List<T>, card: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val columns = (maxWidth / 190.dp).toInt().coerceAtLeast(2)
        val cardWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { card(it, Modifier.width(cardWidth)) }
                }
            }
        }
    }
}

/** A drawn padlock; [open] lifts the shackle. */
@Composable
fun LockIcon(modifier: Modifier, open: Boolean = false, color: Color = Palette.gold) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, Offset(w * 0.12f, h * 0.44f), Size(w * 0.76f, h * 0.54f), CornerRadius(w * 0.12f))
        drawArc(
            color,
            180f,
            180f,
            useCenter = false,
            topLeft = Offset(w * 0.26f, if (open) -h * 0.04f else h * 0.06f),
            size = Size(w * 0.48f, h * 0.56f),
            style = Stroke(width = w * 0.12f),
        )
        drawLine(color, Offset(w * 0.26f, if (open) h * 0.24f else h * 0.34f), Offset(w * 0.26f, h * 0.46f), w * 0.12f)
        if (!open) drawLine(color, Offset(w * 0.74f, h * 0.34f), Offset(w * 0.74f, h * 0.46f), w * 0.12f)
        drawCircle(Palette.panel, w * 0.08f, Offset(w * 0.5f, h * 0.68f))
    }
}

/**
 * A library file as a card: the game's picture (grey while it isn't on the phone) with a tag for
 * where it is (and the download's progress on the picture itself), then its
 * name and file under it. Tap a card that's only in the library to get it.
 */
@Composable
private fun LibraryCard(row: LibraryRowUi, busy: Boolean, modifier: Modifier, onDownload: () -> Unit) {
    val canGet = (row.state == RowState.Idle || (row.state as? RowState.Done)?.retry == true) && !busy
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val gold = row.state is RowState.Downloading || lit(source)
    Column(modifier.pressScale(source)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(shape)
                .background(Palette.panel)
                .border(if (gold) 2.dp else 1.dp, if (gold) Palette.gold else Color.White.copy(alpha = 0.08f), shape)
                .clickable(interactionSource = source, indication = null, enabled = canGet, onClick = onDownload),
        ) {
            if (row.game != null) {
                ArtImage(row.game, row.set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, grey = row.state == RowState.Idle)
            } else {
                FileTile(row.title, Modifier.matchParentSize(), bios = row.set?.startsWith("neogeo") == true)
            }
            val tag = when (val s = row.state) {
                RowState.Idle -> "Not on phone" to Palette.muted
                RowState.OnPhone -> "On phone" to Palette.green
                // Short: a phone's card is narrow (the bar on the picture says the rest).
                is RowState.Downloading -> "Getting ${(s.fraction * 100).toInt()}%" to Palette.gold
                is RowState.Done -> if (s.ok) "Ready" to Palette.green else "Problem" to Palette.red
            }
            PictureTag(tag.first, tag.second, Modifier.align(Alignment.TopStart).padding(8.dp))
            when (val s = row.state) {
                RowState.Idle -> GoldButton("Get", onDownload, Modifier.align(Alignment.BottomEnd).padding(8.dp), enabled = !busy, compact = true)
                is RowState.Downloading -> Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 10.dp),
                ) { ProgressBar(s.fraction, thickness = 4.dp, track = Color.White.copy(alpha = 0.25f)) }
                else -> Unit
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(row.title, color = if (gold) Palette.gold else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.detail, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // What the check found: two lines on the card (so a grid row stays
        // even); a long one (a broken zip's missing files) opens in full on tap.
        (row.state as? RowState.Done)?.let { s ->
            var cut by remember { mutableStateOf(false) }
            var open by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = cut) { open = true },
            ) {
                Text(
                    s.message,
                    color = if (s.ok) Palette.green else Palette.text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { cut = it.hasVisualOverflow },
                )
                if (cut) {
                    Text("Details", color = Palette.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            if (open) {
                ArcadeDialog(
                    title = row.title,
                    message = s.message,
                    onDismiss = { open = false },
                    primary = "OK" to { open = false },
                    tone = if (s.ok) Tone.GOOD else Tone.BAD,
                )
            }
        }
    }
}

/**
 * A file with no game picture: the BIOS (a drawn chip) or a zip the app
 * doesn't know, its name in the app's heavy italic on a dark tile.
 */
@Composable
private fun FileTile(title: String, modifier: Modifier, bios: Boolean) {
    Column(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF231C0A), Palette.panel))).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (bios) {
            ChipIcon(Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(
            title.uppercase(),
            color = Palette.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A drawn microchip in gold, for the BIOS. */
@Composable
private fun ChipIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val pin = w * 0.07f
        for (i in 0..3) {
            val at = w * (0.26f + i * 0.16f)
            drawLine(Palette.gold, Offset(at, 0f), Offset(at, h * 0.2f), pin)
            drawLine(Palette.gold, Offset(at, h * 0.8f), Offset(at, h), pin)
            drawLine(Palette.gold, Offset(0f, at), Offset(w * 0.2f, at), pin)
            drawLine(Palette.gold, Offset(w * 0.8f, at), Offset(w, at), pin)
        }
        drawRoundRect(Palette.gold, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.68f), CornerRadius(w * 0.1f))
        drawRoundRect(Color(0xFF231C0A), Offset(w * 0.32f, h * 0.32f), Size(w * 0.36f, h * 0.36f), CornerRadius(w * 0.05f))
    }
}
