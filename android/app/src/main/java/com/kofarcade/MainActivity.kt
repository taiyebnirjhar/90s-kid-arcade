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
import androidx.compose.runtime.setValue
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.RomCheck
import com.kofarcade.core.gameById
import com.kofarcade.core.gameForSet

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
        var library by remember { mutableStateOf(false) }
        var twoPlayer by remember { mutableStateOf(false) }
        var crash by remember { mutableStateOf(CrashLog.take(this)) }
        // Re-read what's on disk whenever `refresh` changes (after a pick or a return from a game).
        val hasBios = remember(refresh) { roms.has(BIOS) }
        val sets = remember(refresh) { GAMES.associate { it.id to roms.setFor(it) } }
        val last = remember(refresh) { prefs.lastGame?.let(::gameById) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                result = roms.import(uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't add the file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            refresh++
        }
        fun launch() {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }

        fun open(game: Game) = when {
            !hasBios -> ask = Pick.Bios
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
                !hasBios -> "Get started"
                hero == last && sets[hero.id] != null -> "Continue"
                sets[hero.id] != null -> "Ready to play"
                else -> "Get started"
            },
            heroAction = when {
                !hasBios -> "Add BIOS"
                sets[hero.id] == null -> "Add ROM"
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
                onHero = { if (!hasBios) ask = Pick.Bios else open(hero) },
                onGame = ::open,
                onLongGame = { if (sets[it.id] != null) remove = it },
            )
        }

        ask?.let { p ->
            val (title, body) = when (p) {
                Pick.Bios -> if (hasBios) {
                    "Replace the BIOS?" to "Pick another neogeo.zip to use instead of the current one, or get it from your library."
                } else {
                    "Add the Neo Geo BIOS" to "Every game needs neogeo.zip. Pick it from your phone, or get it from your library."
                }
                is Pick.Rom -> "Add ${p.game.title}" to
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
                primary = if (game != null && hasBios) {
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
            ArcadeDialog(
                title = "Remove ${game.title}?",
                message = "Deletes the ROM from this app. You can add it again any time.",
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
