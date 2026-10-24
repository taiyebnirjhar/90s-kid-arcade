package com.kofarcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameForSet
import com.kofarcade.core.setName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One library file's download: bytes so far, then what the check found. */
private data class Download(val done: Long = 0, val result: String? = null, val ok: Boolean = false)

/**
 * Your private ROM library: asks for the key once (the address is built
 * in, see [Library.DEFAULT_URL], and can be changed), then lists
 * its zips with a Download button each. Every download is checked like an
 * import, so a broken zip says what's missing instead of failing in the game.
 */
@Composable
fun LibraryDialog(prefs: Prefs, roms: RomStore, onChanged: () -> Unit, onClose: () -> Unit) {
    var editing by remember { mutableStateOf(prefs.libraryKey == null) }
    var entries by remember { mutableStateOf<List<Library.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Download>() }
    var busy by remember { mutableStateOf(false) }
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

    fun installed(entry: Library.Entry): Boolean =
        prefs.librarySet(entry.name)?.let { roms.has("$it.zip") } == true

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

    LaunchedEffect(Unit) { if (!editing) load() }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.bg).systemBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My library", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (!editing) TextButton(onClick = { editing = true }) { Text("Change key") }
                TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
            }
            if (editing) {
                var url by remember { mutableStateOf(prefs.libraryUrl) }
                var key by remember { mutableStateOf(prefs.libraryKey.orEmpty()) }
                var showUrl by remember { mutableStateOf(prefs.libraryUrl != Library.DEFAULT_URL) }
                Text(
                    "Enter the library key to download your games. It stays on this phone.",
                    color = Palette.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showUrl) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Library address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { showUrl = true }) { Text("Use another library") }
                }
                error?.let { Text(it, color = Palette.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Button(
                    onClick = {
                        prefs.libraryUrl = url.trim()
                        prefs.libraryKey = key.trim()
                        editing = false
                        load()
                    },
                    enabled = url.isNotBlank() && key.isNotBlank(),
                    modifier = Modifier.padding(top = 14.dp),
                ) { Text("Connect") }
            } else {
                LibraryList(
                    loading, error, entries, downloads, busy, prefs, ::installed,
                    onRetry = { load() },
                    onDownload = { picked ->
                        busy = true
                        scope.launch {
                            // The BIOS first, so the games can be played as soon as they land.
                            picked.sortedBy { if (setName(it.name).startsWith("neogeo")) 0 else 1 }.forEach { download(it) }
                            busy = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryList(
    loading: Boolean,
    error: String?,
    entries: List<Library.Entry>?,
    downloads: Map<String, Download>,
    busy: Boolean,
    prefs: Prefs,
    installed: (Library.Entry) -> Boolean,
    onRetry: () -> Unit,
    onDownload: (List<Library.Entry>) -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> Column {
            Text(error, color = Palette.red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
        entries.isNullOrEmpty() -> Text(
            "The library is empty. Upload zips to your R2 bucket, then reopen this.",
            color = Palette.muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        else -> Column {
            val list = entries.orEmpty()
            // Recounted as downloads finish.
            val finished = downloads.values.count { it.result != null }
            val missing = remember(list, finished) { list.filterNot(installed) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "${list.size} files, ${list.size - missing.size} on this phone",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                if (missing.isNotEmpty()) {
                    Button(onClick = { onDownload(missing) }, enabled = !busy) { Text("Download all") }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.name }) { entry ->
                    LibraryRow(entry, installed(entry), downloads[entry.name], prefs.librarySet(entry.name), busy) {
                        onDownload(listOf(entry))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    entry: Library.Entry,
    installed: Boolean,
    download: Download?,
    set: String?,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val title = set?.let { if (it == "neogeo") "Neo Geo BIOS" else gameForSet(it)?.title }
                Text(title ?: entry.name, color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (title != null) entry.name + " · " else "") + "%.1f MB".format(entry.size / 1_048_576.0),
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            val downloading = download != null && download.result == null
            when {
                downloading -> Text(
                    if (entry.size > 0) "${download!!.done * 100 / entry.size}%" else "…",
                    color = Palette.gold,
                    fontWeight = FontWeight.Bold,
                )
                installed -> Text("On phone", color = Palette.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else -> Button(onClick = onDownload, enabled = !busy) { Text("Download") }
            }
        }
        if (download != null && download.result == null && entry.size > 0) {
            LinearProgressIndicator(
                progress = { (download.done.toFloat() / entry.size).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        download?.result?.let {
            Text(it, color = if (download.ok) Palette.green else Palette.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
