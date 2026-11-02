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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    /** On the phone and ready to start (a Neo Geo game: the BIOS is here too): Play shows. */
    val playable: Boolean = false,
)

sealed interface RowState {
    data object Idle : RowState
    data object OnPhone : RowState
    /** In the queue, its turn to come. */
    data object Queued : RowState
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

/**
 * Your private ROM library: asks for the key once (the address is built
 * in, see [Library.DEFAULT_URL], and can be changed), then lists its zips
 * with a button each. Downloads run in the app's queue ([LibraryDownloads]),
 * not this page: leaving it, or the app, doesn't stop them (a notification
 * shows them). Every download is checked whole and like an import, so a cut
 * or broken zip says so instead of passing as on the phone.
 */
@Composable
fun LibraryDialog(prefs: Prefs, roms: RomStore, onChanged: () -> Unit, onClose: () -> Unit, onPlay: (Game) -> Unit = {}) {
    var editing by remember { mutableStateOf(prefs.libraryKey == null) }
    var entries by remember { mutableStateOf<List<Library.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val queue = remember { LibraryDownloads.get(context) }
    val downloads by queue.state.collectAsState()
    val busy by queue.active.collectAsState()
    val landed by queue.installed.collectAsState()
    var key by remember { mutableStateOf(prefs.libraryKey.orEmpty()) }
    var url by remember { mutableStateOf(prefs.libraryUrl) }
    var showUrl by remember { mutableStateOf(prefs.libraryUrl != Library.DEFAULT_URL) }
    val scope = rememberCoroutineScope()
    // A game landed (here or while away): the home screen looks again.
    LaunchedEffect(landed) { if (landed > 0) onChanged() }

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
            } catch (e: Library.BusyLibrary) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't reach the library: ${e.message}"
            }
            loading = false
        }
    }

    fun installed(entry: Library.Entry): Boolean = prefs.librarySet(entry.name)?.let { roms.has("$it.zip") } == true

    val download = rememberDownloadStarter(queue)

    LaunchedEffect(Unit) { if (!editing) load() }
    // Back leaves the page; the downloads keep going (the notification shows them).
    BackHandler {
        if (busy) android.widget.Toast.makeText(context, "Downloads keep going: see the notification", android.widget.Toast.LENGTH_SHORT).show()
        onClose()
    }

    val rows = entries.orEmpty().map { e ->
        // Once downloaded, the check says what it is; before, the file name
        // usually does ("sengoku3 (1).zip" -> Sengoku 3).
        val set = prefs.librarySet(e.name) ?: setName(e.name).replace(Regex(""" \(\d+\)$"""), "")
        val game = gameForSet(set)
        val title = if (set == "neogeo") "Neo Geo BIOS" else game?.fullTitle
        val d = downloads[e.name]
        val here = installed(e)
        LibraryRowUi(
            playable = here && game != null && (!game.neoGeo || roms.has(com.kofarcade.core.BIOS)),
            game = game,
            set = set,
            file = e.name,
            title = title ?: e.name.removeSuffix(".zip"),
            detail = (if (title != null) e.name + " · " else "") + "%.1f MB".format(e.size / 1_048_576.0),
            state = when {
                d != null && d.queued -> RowState.Queued
                d != null && d.result == null -> RowState.Downloading(if (e.size > 0) d.done.toFloat() / e.size else 0f)
                // Got, then removed from the phone (long-press on home): not on the phone any more.
                d?.result != null && d.ok && !installed(e) -> RowState.Idle
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
        onDownload = { file -> entries?.find { it.name == file }?.let { download(listOf(it)) } },
        onDownloadAll = {
            val waiting = rows.filter { it.state == RowState.Idle || (it.state as? RowState.Done)?.retry == true }.map { it.file }.toSet()
            download(entries.orEmpty().filter { it.name in waiting })
        },
        onStop = {
            queue.cancelAll()
            android.widget.Toast.makeText(context, "Downloads stopped", android.widget.Toast.LENGTH_SHORT).show()
        },
        onBack = onClose,
        onPlay = { file -> rows.find { it.file == file }?.game?.let(onPlay) },
    )
}

// Asked once per run of the app (a "no" isn't asked again and again).
private var askedNotify = false

/**
 * Starts downloads in [queue]. Android 13 on asks before an app shows
 * notifications: asked once, as the first download starts (the downloads go
 * on either way; without it, their progress shows only in the app).
 */
@Composable
fun rememberDownloadStarter(queue: DownloadQueue): (List<Library.Entry>) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<List<Library.Entry>?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {
        pending?.let(queue::enqueue)
        pending = null
    }
    return { picked ->
        if (picked.isNotEmpty()) {
            val needs = android.os.Build.VERSION.SDK_INT >= 33 && !askedNotify &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needs) {
                askedNotify = true
                pending = picked
                ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                queue.enqueue(picked)
            }
        }
    }
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
    onStop: () -> Unit = {},
    onPlay: (String) -> Unit = {},
) {
    val listed = !ui.askKey && !ui.loading && ui.error == null && ui.rows.isNotEmpty()
    val waiting = ui.rows.count { it.state == RowState.Idle || (it.state as? RowState.Done)?.retry == true }
    val coming = ui.rows.count { it.state == RowState.Queued || it.state is RowState.Downloading }
    // Came, but the zip isn't a whole game (the check says what's missing).
    val problems = ui.rows.count { (it.state as? RowState.Done)?.let { d -> !d.ok && !d.retry } == true }
    ArcadeScreen(
        title = "My Library",
        kicker = "Your games, one tap away",
        onBack = onBack,
        actions = { if (!ui.askKey) GhostButton("Key", onChangeKey, compact = true) },
        // The page's one main action, in thumb reach.
        bottomBar = if (listed && (waiting > 0 || ui.busy)) {
            {
                if (ui.busy) {
                    // Downloading: how many are left, and Stop (they go on in the background otherwise).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GoldButton(
                            if (waiting > 0) "Get the other $waiting too" else "Downloading $coming…",
                            onDownloadAll,
                            Modifier.weight(1f),
                            enabled = waiting > 0,
                        )
                        Spacer(Modifier.width(10.dp))
                        GhostButton("Stop", onStop, danger = true)
                    }
                } else {
                    GoldButton("Get all $waiting", onDownloadAll, Modifier.fillMaxWidth())
                }
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
                Text(
                    buildString {
                        append(
                            when {
                                coming > 0 -> "$coming downloading · keeps going if you leave"
                                waiting == 0 && problems == 0 -> "All on this phone"
                                waiting == 0 -> "The rest are on this phone"
                                else -> "$waiting not on this phone yet"
                            },
                        )
                        if (problems > 0) append(" · $problems with a problem")
                    },
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                CardGrid(ui.rows) { row, m -> LibraryCard(row, ui.busy, m, onPlay = { onPlay(row.file) }) { onDownload(row.file) } }
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
private fun LibraryCard(row: LibraryRowUi, busy: Boolean, modifier: Modifier, onPlay: () -> Unit = {}, onDownload: () -> Unit) {
    // More can be added while others download: they wait their turn.
    val canGet = row.state == RowState.Idle || (row.state as? RowState.Done)?.retry == true
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
                // Tap: get it, or (on the phone, ready) play it.
                .clickable(interactionSource = source, indication = null, enabled = canGet || row.playable, onClick = if (canGet) onDownload else onPlay),
        ) {
            if (row.game != null) {
                ArtImage(row.game, row.set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, grey = row.state == RowState.Idle)
            } else {
                FileTile(row.title, Modifier.matchParentSize(), bios = row.set?.startsWith("neogeo") == true)
            }
            val tag = when (val s = row.state) {
                RowState.Idle -> "Not on phone" to Palette.muted
                RowState.Queued -> "Waiting" to Palette.muted
                RowState.OnPhone -> "On phone" to Palette.green
                // Short: a phone's card is narrow (the bar on the picture says the rest).
                is RowState.Downloading -> "Getting ${(s.fraction * 100).toInt()}%" to Palette.gold
                is RowState.Done -> if (s.ok) "Ready" to Palette.green else "Problem" to Palette.red
            }
            PictureTag(tag.first, tag.second, Modifier.align(Alignment.TopStart).padding(8.dp))
            when (val s = row.state) {
                RowState.Idle -> GoldButton("Get", onDownload, Modifier.align(Alignment.BottomEnd).padding(8.dp), compact = true)
                RowState.OnPhone, is RowState.Done -> if (row.playable) {
                    GoldButton("Play", onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp), play = true, compact = true)
                }
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
internal fun ChipIcon(modifier: Modifier) {
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
