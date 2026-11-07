package com.kofarcade

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PSP games (docs/psp-plan.md, P1): the games on this phone, and "Add a game"
 * to pick one from the phone's files (.iso, .cso, .chd, or a .7z / .zip
 * download, unpacked here). Tapping a game plays it ([PspGameActivity]).
 */
class PspActivity : ComponentActivity() {
    private val dir by lazy { File(filesDir, "psp/games") }
    private var games by mutableStateOf<List<File>>(emptyList())
    private var error by mutableStateOf<String?>(null)

    // The PSP library (a public Google Drive folder): its link, its games, and how listing it went.
    private val prefs by lazy { Prefs(this) }
    private var link by mutableStateOf("")
    private var driveGames by mutableStateOf<List<Library.Entry>>(emptyList())
    private var driveNote by mutableStateOf<String?>(null)
    private var listing by mutableStateOf(false)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::add) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        games = PspGames.list(dir)
        link = prefs.pspDriveLink.orEmpty()
        if (link.isNotEmpty()) loadDrive()
        // A game landing from the library shows in the list at once.
        lifecycleScope.launch { LibraryDownloads.get(this@PspActivity).installed.collect { games = PspGames.list(dir) } }
        // And one added from the phone's files, with how it went.
        lifecycleScope.launch {
            PspImports.state.collect { s ->
                if (s != null && !s.running) {
                    games = PspGames.list(dir)
                    if (!s.ok) error = s.result
                    PspImports.clear()
                }
            }
        }
        setContent { KofTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        games = PspGames.list(dir)
    }

    /** Adds the picked game in the background ([PspImports]): it goes on after this page is left. */
    private fun add(uri: Uri) {
        error = null
        // Read it again later, from the service, after this page is gone.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (!PspImports.start(this, uri, displayName(uri) ?: "game")) error = "A game is being added already: wait for it to finish"
    }

    /** Lists the Drive folder's games (off the main thread). */
    private fun loadDrive() {
        val l = link.trim()
        if (DriveFolder.folderId(l) == null) {
            driveNote = "Paste the Google Drive folder's link (shared with anyone who has the link)"
            return
        }
        prefs.pspDriveLink = l
        listing = true
        driveNote = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DriveFolder.list(l) } }
            listing = false
            result.onSuccess { driveGames = it; if (it.isEmpty()) driveNote = "No PSP games in that folder" }
                .onFailure { driveNote = it.message ?: "Couldn't open the folder" }
        }
    }

    /** Downloads a Drive game (the app's download queue: in the background, picked up after drops). */
    private fun getFromDrive(e: Library.Entry) {
        // The download and the unpacked game sit side by side for a moment: about 3 GB for the biggest.
        if (filesDir.usableSpace < 3_500_000_000L) {
            driveNote = "Free some space first: a PSP game needs up to 3.5 GB while it's added (${filesDir.usableSpace / 1_000_000} MB free)"
            return
        }
        LibraryDownloads.get(this).enqueue(listOf(e))
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** The game's last session log, copied for the chat. */
    private fun copyLog(game: File) {
        val f = PspGames.logFile(filesDir, game.name)
        if (!f.exists()) {
            android.widget.Toast.makeText(this, "No log yet: play the game first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PSP game log", f.readText()))
        android.widget.Toast.makeText(this, "Game log copied: paste it in the chat", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun play(game: File) {
        startActivity(Intent(this, PspGameActivity::class.java).putExtra(PspGameActivity.EXTRA_PATH, game.absolutePath))
    }

    @Composable
    private fun Screen() {
        ArcadeScreen(title = "PSP games", onBack = ::finish) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Test version: pick your game file from the phone (.7z, .zip, .iso, .cso or .chd). " +
                    "A speed counter shows while you play.",
                color = Palette.muted, fontSize = 13.sp,
            )
            val adding by PspImports.state.collectAsState()
            val busy = adding?.takeIf { it.running }
            if (busy != null) {
                Text("Adding ${busy.name}… ${busy.bytes / 1_000_000} MB", color = Palette.text, fontSize = 14.sp)
                Text("You can leave this page: it carries on in the background.", color = Palette.muted, fontSize = 12.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                GoldButton("Add a game", { pick.launch(arrayOf("*/*")) })
            }
            error?.let { Text(it, color = Palette.red, fontSize = 13.sp) }
            DriveLibrary()
            Spacer(Modifier.height(4.dp))
            if (games.isEmpty() && busy == null) {
                Text("No PSP games yet.", color = Palette.muted, fontSize = 14.sp)
            }
            for (g in games) GameRow(g)
        } }
    }

    /** The PSP library: the Drive folder's games, each with Get (or its download's progress). */
    @Composable
    private fun DriveLibrary() {
        val downloads by LibraryDownloads.get(this).state.collectAsState()
        Text("PSP library", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(
            "Games from your Google Drive folder. Downloads go on in the background and pick up after a drop.",
            color = Palette.muted, fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeField(link, { link = it }, label = null, modifier = Modifier.weight(1f), placeholder = "Google Drive folder link", onDone = ::loadDrive)
            Spacer(Modifier.padding(4.dp))
            GhostButton(if (listing) "…" else "Open", ::loadDrive, compact = true, enabled = !listing)
        }
        driveNote?.let { Text(it, color = Palette.red, fontSize = 12.sp) }
        for (e in driveGames) {
            val (_, file) = DriveFolder.parseEntry(e.name) ?: continue
            val d = downloads[e.name]
            val title = LibraryDownloads.title(e.name)
            val onPhone = games.any { g -> PspGames.title(g).lowercase().let { t -> title.lowercase().split(' ', '-', ':').filter { it.length > 3 }.all { w -> w in t } } }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
                    .border(1.dp, Palette.line, RoundedCornerShape(14.dp)).padding(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val line = when {
                        d == null -> if (onPhone) "On this phone" else file
                        d.queued -> "Waiting its turn…"
                        d.result != null -> d.result
                        else -> "Downloading… ${d.done / 1_000_000} MB"
                    }
                    Text(line, color = Palette.muted, fontSize = 12.sp)
                }
                if (!onPhone && (d == null || d.retry || (d.result != null && !d.ok))) {
                    GoldButton("Get", { getFromDrive(e) }, compact = true)
                }
            }
        }
    }

    @Composable
    private fun GameRow(game: File) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
                .border(1.dp, Palette.line, RoundedCornerShape(14.dp)).clickable { play(game) }.padding(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(PspGames.title(game), color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${game.extension.uppercase()} · ${game.length() / 1_000_000} MB", color = Palette.muted, fontSize = 12.sp)
            }
            GhostButton("Log", { copyLog(game) }, compact = true)
            Spacer(Modifier.padding(4.dp))
            GhostButton("Remove", {
                game.delete()
                games = PspGames.list(dir)
            }, compact = true, danger = true)
            Spacer(Modifier.padding(4.dp))
            GoldButton("Play", { play(game) }, compact = true, play = true)
        }
    }
}
