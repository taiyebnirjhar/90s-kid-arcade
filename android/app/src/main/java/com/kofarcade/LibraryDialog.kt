package com.kofarcade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameForSet
import com.kofarcade.core.setName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One library file as the page shows it. */
data class LibraryRowUi(val file: String, val title: String, val detail: String, val state: RowState)

sealed interface RowState {
    data object Idle : RowState
    data object OnPhone : RowState
    data class Downloading(val fraction: Float) : RowState
    data class Done(val message: String, val ok: Boolean) : RowState
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
private data class Download(val done: Long = 0, val result: String? = null, val ok: Boolean = false)

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
            val check = withContext(Dispatchers.IO) {
                val tmp = roms.partFile("library")
                library().download(entry.name, tmp) { downloads[entry.name] = Download(it) }
                roms.add(tmp)
            }
            val set = when (check) {
                RomCheck.Bios -> "neogeo"
                is RomCheck.Game -> check.set.name
                else -> null
            }
            prefs.setLibrarySet(entry.name, set)
            downloads[entry.name] = Download(entry.size, check.message(), ok = set != null)
            onChanged()
        } catch (e: Exception) {
            downloads[entry.name] = Download(0, "Download failed: ${e.message}")
        }
    }

    fun downloadAll(picked: List<Library.Entry>) {
        busy = true
        scope.launch {
            // The BIOS first, so the games can be played as soon as they land.
            picked.sortedBy { if (setName(it.name).startsWith("neogeo")) 0 else 1 }.forEach { download(it) }
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (!editing) load() }
    BackHandler { if (!busy) onClose() }

    val rows = entries.orEmpty().map { e ->
        // Once downloaded, the check says what it is; before, the file name
        // usually does ("sengoku3 (1).zip" -> Sengoku 3).
        val set = prefs.librarySet(e.name) ?: setName(e.name).replace(Regex(""" \(\d+\)$"""), "")
        val title = if (set == "neogeo") "Neo Geo BIOS" else gameForSet(set)?.fullTitle
        val d = downloads[e.name]
        LibraryRowUi(
            file = e.name,
            title = title ?: e.name.removeSuffix(".zip"),
            detail = (if (title != null) e.name + " · " else "") + "%.1f MB".format(e.size / 1_048_576.0),
            state = when {
                d != null && d.result == null -> RowState.Downloading(if (e.size > 0) d.done.toFloat() / e.size else 0f)
                d?.result != null -> RowState.Done(d.result, d.ok)
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
    ArcadeScreen(
        title = "My Library",
        kicker = "Your games, one tap away",
        onBack = onBack,
        actions = { if (!ui.askKey) GhostButton("Key", onChangeKey) },
    ) {
        when {
            ui.askKey -> Panel {
                Kicker("Unlock your library")
                Spacer(Modifier.height(6.dp))
                Text("Enter your library key to download your games. It stays on this phone.", color = Palette.muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                ArcadeField(key, onKey, "Key", password = true, placeholder = "••••••••")
                if (showUrl) {
                    Spacer(Modifier.height(12.dp))
                    ArcadeField(url, onUrl, "Library address", keyboardType = KeyboardType.Uri)
                }
                ui.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(18.dp))
                GoldButton("Unlock", onConnect, Modifier.fillMaxWidth(), enabled = key.isNotBlank() && url.isNotBlank())
                if (!showUrl) {
                    Text(
                        "Use another library",
                        color = Palette.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowUrl).padding(8.dp),
                    )
                }
            }
            ui.loading -> Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Opening your library…", color = Palette.text, fontSize = 15.sp)
                }
            }
            ui.error != null -> Panel {
                Kicker("Can't reach the library", Palette.red)
                Spacer(Modifier.height(6.dp))
                Text(ui.error, color = Palette.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                GoldButton("Try again", onRetry)
            }
            ui.rows.isEmpty() -> Panel {
                Text("The library is empty. Upload zips to your R2 bucket, then open this again.", color = Palette.muted)
            }
            else -> {
                val waiting = ui.rows.count { it.state == RowState.Idle }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("${ui.rows.size} games and files", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (waiting == 0) "All on this phone" else "$waiting not on this phone yet", color = Palette.muted, fontSize = 13.sp)
                    }
                    if (waiting > 0) GoldButton("Get all", onDownloadAll, enabled = !ui.busy)
                }
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.rows.forEach { row -> LibraryRow(row, ui.busy) { onDownload(row.file) } }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LibraryRow(row: LibraryRowUi, busy: Boolean, onDownload: () -> Unit) {
    Panel(highlight = row.state is RowState.Downloading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(row.detail, color = Palette.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            when (val s = row.state) {
                RowState.Idle -> GoldButton("Get", onDownload, enabled = !busy)
                RowState.OnPhone -> Tag("On phone", Palette.green)
                is RowState.Downloading -> Text("${(s.fraction * 100).toInt()}%", color = Palette.gold, fontSize = 16.sp, fontWeight = FontWeight.Black)
                is RowState.Done -> if (s.ok) Tag("Ready", Palette.green) else Tag("Problem", Palette.red)
            }
        }
        when (val s = row.state) {
            is RowState.Downloading -> {
                Spacer(Modifier.height(12.dp))
                ProgressBar(s.fraction)
            }
            is RowState.Done -> {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background((if (s.ok) Palette.green else Palette.red).copy(alpha = 0.1f)).padding(12.dp),
                ) { Text(s.message, color = if (s.ok) Palette.green else Palette.text, fontSize = 13.sp) }
            }
            else -> Unit
        }
    }
}
