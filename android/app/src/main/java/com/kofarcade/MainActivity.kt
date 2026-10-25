package com.kofarcade

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameById
import com.kofarcade.core.gameForSet
import com.kofarcade.core.romSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: [HomeScreen], plus the dialogs and pages it opens (adding a ROM, the
 * library, two players).
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

    private fun play(game: Game, netplay: Boolean = false, set: String? = roms.setFor(game)) {
        set ?: return
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_GAME, game.id)
                .putExtra(GameActivity.EXTRA_SET, set)
                .putExtra(GameActivity.EXTRA_NETPLAY, netplay),
        )
    }

    /** What the "add" dialog is for: the BIOS, or a game's ROM. */
    private sealed interface Pick {
        data object Bios : Pick
        data class Rom(val game: Game) : Pick
    }

    @Composable
    private fun Home() {
        var ask by remember { mutableStateOf<Pick?>(null) }
        var remove by remember { mutableStateOf<Game?>(null) }
        var result by remember { mutableStateOf<RomCheck?>(null) }
        var library by rememberSaveable { mutableStateOf(false) }
        var twoPlayer by remember { mutableStateOf(false) }
        var crash by remember { mutableStateOf(CrashLog.take(this)) }
        // Re-read what's on disk whenever `refresh` changes (after a pick or a return from a game).
        val hasBios = remember(refresh) { roms.has(BIOS) }
        val sets = remember(refresh) { GAMES.associate { it.id to roms.setFor(it) } }
        val last = remember(refresh) { prefs.lastGame?.let(::gameById) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Copying and checking a big zip takes seconds: off the main thread.
            Toast.makeText(this, "Checking the zip…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                try {
                    result = withContext(Dispatchers.IO) { roms.import(uri) }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Couldn't add the file: ${e.message}", Toast.LENGTH_LONG).show()
                }
                refresh++
            }
        }
        fun launch() {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }

        // Neo Geo games need the BIOS; Capcom's don't.
        fun needsBios(game: Game) = game.neoGeo && !hasBios

        fun open(game: Game) = when {
            needsBios(game) -> ask = Pick.Bios
            sets[game.id] == null -> ask = Pick.Rom(game)
            else -> play(game)
        }

        // The banner shows the last game played, else the first one ready, else KOF '97.
        val hero = last?.takeIf { sets[it.id] != null }
            ?: GAMES.firstOrNull { sets[it.id] != null }
            ?: GAMES.first()
        val ui = HomeUi(
            hasBios = hasBios,
            sets = sets,
            hero = hero,
            heroLabel = when {
                needsBios(hero) -> "Get started"
                hero == last && sets[hero.id] != null -> "Last played"
                sets[hero.id] != null -> "Ready to play"
                else -> "Get started"
            },
            heroAction = when {
                needsBios(hero) -> "Add BIOS"
                sets[hero.id] == null -> "Add ROM"
                hero == last -> "Continue"
                else -> "Play"
            },
        )

        when {
            library -> LibraryDialog(prefs, roms, onChanged = { refresh++ }, onClose = { library = false })
            twoPlayer -> TwoPlayerDialog(
                roms,
                onStart = { game, set ->
                    twoPlayer = false
                    play(game, netplay = true, set = set)
                },
                onLibrary = {
                    twoPlayer = false
                    library = true
                },
                onClose = { twoPlayer = false },
            )
            else -> HomeScreen(
                ui,
                onTwoPlayer = { twoPlayer = true },
                onLibrary = { library = true },
                onBios = { ask = Pick.Bios },
                onHero = { open(hero) },
                onGame = ::open,
                onLongGame = { if (sets[it.id] != null) remove = it },
            )
        }

        ask?.let { p ->
            val (title, body) = when (p) {
                Pick.Bios -> if (hasBios) {
                    "Replace the BIOS?" to "Pick another neogeo.zip to use instead of the current one, or get it from your library."
                } else {
                    "Add the Neo Geo BIOS" to "Neo Geo games need neogeo.zip. Pick it from your phone, or get it from your library."
                }
                is Pick.Rom -> "Add ${p.game.fullTitle}" to
                    "Pick its zip from your phone (any file name works: the app checks what's inside), or get it from your library."
            }
            ArcadeDialog(
                title = title,
                message = body,
                onDismiss = { ask = null },
                primary = "From my library" to {
                    ask = null
                    library = true
                },
                secondary = "Choose file" to {
                    ask = null
                    launch()
                },
            )
        }

        result?.let { check ->
            val game = (check as? RomCheck.Game)?.let { gameForSet(it.set.name) }
            val ok = check is RomCheck.Game || check == RomCheck.Bios
            ArcadeDialog(
                title = when (check) {
                    RomCheck.Bios, is RomCheck.Game -> "Ready to play"
                    is RomCheck.Incomplete -> "This zip won't run"
                    RomCheck.Unknown -> "Not a game the app knows"
                },
                message = check.message(),
                onDismiss = { result = null },
                tone = if (ok) Tone.GOOD else Tone.BAD,
                primary = if (game != null && !needsBios(game)) {
                    "Play" to {
                        result = null
                        play(game)
                    }
                } else {
                    "OK" to { result = null }
                },
            )
        }

        crash?.let { report ->
            ArcadeDialog(
                title = "The app stopped last time",
                message = "Sorry about that. Share the details so it can be fixed:\n\n" +
                    report.lines().filter { it.isNotBlank() }.take(4).joinToString("\n"),
                onDismiss = { crash = null },
                tone = Tone.BAD,
                primary = "Share details" to {
                    crash = null
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                            "Share crash details",
                        ),
                    )
                },
                secondary = "Close" to { crash = null },
            )
        }

        remove?.let { game ->
            // A hack stored without the files it shares with the original borrows them from its zip.
            val borrowers = sets[game.id]?.let { set ->
                roms.zips().filter { romSet(it)?.parent == set && gameForSet(it) != game }.mapNotNull(::gameForSet)
            }.orEmpty()
            ArcadeDialog(
                title = "Remove ${game.fullTitle}?",
                message = "Deletes the ROM from this app. You can add it again any time." +
                    if (borrowers.isEmpty()) "" else
                        " ${borrowers.joinToString(" and ") { it.fullTitle }} uses files from it and won't start without it.",
                onDismiss = { remove = null },
                tone = Tone.BAD,
                primary = "Keep it" to { remove = null },
                secondary = "Remove" to {
                    sets[game.id]?.let { roms.remove("$it.zip") }
                    remove = null
                    refresh++
                },
                dangerSecondary = true,
            )
        }
    }
}
