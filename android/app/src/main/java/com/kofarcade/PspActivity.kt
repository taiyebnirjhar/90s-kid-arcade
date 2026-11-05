package com.kofarcade

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.io.FileInputStream

/**
 * PSP games (docs/psp-plan.md, P1): the games on this phone, and "Add a game"
 * to pick one from the phone's files (.iso, .cso, .chd, or a .7z / .zip
 * download, unpacked here). Tapping a game plays it ([PspGameActivity]).
 */
class PspActivity : ComponentActivity() {
    private val dir by lazy { File(filesDir, "psp/games") }
    private var games by mutableStateOf<List<File>>(emptyList())
    private var adding by mutableStateOf<String?>(null)
    private var added by mutableStateOf(0L)
    private var error by mutableStateOf<String?>(null)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::add) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        games = PspGames.list(dir)
        setContent { KofTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        games = PspGames.list(dir)
    }

    private fun add(uri: Uri) {
        val name = displayName(uri) ?: "game"
        adding = name
        added = 0
        error = null
        // Unpacking a GB or two takes a minute: keep the screen on meanwhile.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                        PspGames.import(
                            name, dir,
                            open = { contentResolver.openInputStream(uri)!! },
                            channel = { FileInputStream(pfd.fileDescriptor).channel },
                            progress = { added = it },
                        )
                    }
                }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            adding = null
            result.onFailure { e -> error = e.message ?: "Couldn't add $name" }
            games = PspGames.list(dir)
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun play(game: File) {
        startActivity(Intent(this, PspGameActivity::class.java).putExtra(PspGameActivity.EXTRA_PATH, game.absolutePath))
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PSP games", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(
                "Test version: pick your game file from the phone (.7z, .zip, .iso, .cso or .chd). " +
                    "A speed counter shows while you play.",
                color = Palette.muted, fontSize = 13.sp,
            )
            val busy = adding
            if (busy != null) {
                Text("Adding $busy… ${added / 1_000_000} MB", color = Palette.text, fontSize = 14.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                GoldButton("Add a game", { pick.launch(arrayOf("*/*")) })
            }
            error?.let { Text(it, color = Palette.red, fontSize = 13.sp) }
            Spacer(Modifier.height(4.dp))
            if (games.isEmpty() && busy == null) {
                Text("No PSP games yet.", color = Palette.muted, fontSize = 14.sp)
            }
            for (g in games) GameRow(g)
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
            GhostButton("Remove", {
                game.delete()
                games = PspGames.list(dir)
            }, compact = true, danger = true)
            Spacer(Modifier.padding(4.dp))
            GoldButton("Play", { play(game) }, compact = true, play = true)
        }
    }
}
