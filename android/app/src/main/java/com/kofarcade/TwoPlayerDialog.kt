package com.kofarcade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.gameForSet
import com.kofarcade.core.net.Beacon
import com.kofarcade.core.net.BeaconListener
import com.kofarcade.core.net.Refused
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.parseRoomKey
import com.kofarcade.core.net.probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** The room handed from the MULTIPLAYER screen to the game screen. */
object Netplay {
    @Volatile var room: Room? = null
}

/** Where the latest app downloads from (the website's button). */
const val APP_DOWNLOAD = "https://github.com/taiyebnirjhar/90s-kid-arcade/releases/latest/download/90s-Kid-Arcade.apk"

private sealed interface TwoPlayer {
    data object Hub : TwoPlayer
    data object PickGame : TwoPlayer
}

/** What a found host looks like in the list. */
private data class FoundHost(val beacon: Beacon, val address: InetAddress)

/** A room found on the network: [players] playing of [maxPlayers], [watchers] watching. */
data class HostChoice(
    val title: String,
    val hostName: String,
    val have: Boolean,
    val players: Int = 1,
    val maxPlayers: Int = 2,
    val key: String = "",
    val watchers: Int = 0,
    /** The game and the host's version of it, for its picture. */
    val game: Game? = null,
    val set: String? = null,
)

private fun playerName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

/**
 * Which FBNeo build this app runs (a hash of the core library), so phones
 * with different builds don't join a game they'd play a little differently.
 */
private fun emulatorBuild(context: Context): String = runCatching {
    val core = java.io.File(context.applicationInfo.nativeLibraryDir, "libfbneo_libretro_android.so")
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    core.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    digest.digest().take(8).joinToString("") { "%02x".format(it) }
}.getOrDefault("")

/**
 * MULTIPLAYER: host a game (pick it; the room's key is shared from the game
 * screen), or join one: type its key, or tap a room nearby. Joining phones
 * watch until they take a seat with COIN or START.
 */
@Composable
fun TwoPlayerDialog(roms: RomStore, onStart: (Game, String) -> Unit, onLibrary: () -> Unit, onClose: () -> Unit) {
    var screen by remember { mutableStateOf<TwoPlayer>(TwoPlayer.Hub) }
    val context = LocalContext.current

    // Android drops broadcast packets (how rooms are found) unless asked.
    DisposableEffect(Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("90s-kid-arcade").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        onDispose { runCatching { lock.release() } }
    }
    val back = { if (screen == TwoPlayer.Hub) onClose() else screen = TwoPlayer.Hub }
    BackHandler(onBack = back)

    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }

    // Opens a room for the game and starts playing; the game screen shows its key.
    fun host(game: Game, set: String) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                // Never dropped half-made: if the screen's gone by the time
                // the room is open, it's closed again.
                val room = withContext(NonCancellable + Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    Room.host(playerName(), set, game.fullTitle, rom, bios, game.players, build = emulatorBuild(context), device = Prefs(context).deviceId)
                }
                if (!isActive) {
                    room.close()
                    return@launch
                }
                Netplay.room = room
                onStart(game, set)
            } catch (e: Exception) {
                if (isActive) android.widget.Toast.makeText(context, "Couldn't open a room: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            opening = false
        }
    }

    when (screen) {
        TwoPlayer.Hub -> Hub(roms, onHost = { screen = TwoPlayer.PickGame }, onLibrary = onLibrary, onBack = back, onJoined = onStart)
        TwoPlayer.PickGame -> {
            val hasBios = remember { roms.has(BIOS) }
            val ready = remember { GAMES.filter { !it.neoGeo || hasBios }.mapNotNull { g -> roms.setFor(g)?.let { g to it } } }
            PickGameScreen(ready, onPick = { (g, set) -> host(g, set) }, onLibrary = onLibrary, onBack = back)
        }
    }
}

// The hub: finds rooms nearby, joins by key or by tap.
@Composable
private fun Hub(roms: RomStore, onHost: () -> Unit, onLibrary: () -> Unit, onBack: () -> Unit, onJoined: (Game, String) -> Unit) {
    val context = LocalContext.current
    val hosts = remember { mutableStateListOf<FoundHost>() }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<MpStatus?>(null) }
    var action by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Gone from this screen: a join that's let in afterwards leaves at once.
    val left = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { left.set(true) }
    }

    fun say(text: String, actionLabel: String? = null, bad: Boolean = true, onAction: (() -> Unit)? = null) {
        status = MpStatus(text, actionLabel, bad = bad)
        action = onAction
    }

    DisposableEffect(Unit) {
        val listener = runCatching {
            BeaconListener { beacon, from ->
                // On the main thread, and in place: a room's count changing
                // mustn't move it under a finger about to tap it.
                main.post {
                    val found = FoundHost(beacon, from)
                    val i = hosts.indexOfFirst { it.address == from }
                    if (i < 0) hosts += found else if (hosts[i] != found) hosts[i] = found
                }
            }
        }.onFailure { say("Can't look for rooms nearby (${it.message}). Joining with a key still works.") }.getOrNull()
        onDispose { listener?.close() }
    }

    fun connect(found: FoundHost) {
        val set = found.beacon.set
        val game = gameForSet(set)
        if (game == null || !roms.has("$set.zip")) {
            say("You need ${found.beacon.title} on this phone first.", "Get it from the library", onAction = onLibrary)
            return
        }
        busy = true
        say("Joining ${found.beacon.name}'s room…", bad = false)
        scope.launch {
            try {
                val room = withContext(NonCancellable + Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    Room.join(found.address, found.beacon.tcpPort, playerName(), set, rom, bios, build = emulatorBuild(context), device = Prefs(context).deviceId).also { room ->
                        if (left.get()) room.close()
                    }
                }
                if (left.get() || !isActive) {
                    room.close()
                    return@launch
                }
                Netplay.room = room
                status = null
                onJoined(game, set)
            } catch (e: Refused) {
                val m = e.message.orEmpty()
                when {
                    "app builds" in m || "app versions" in m -> say(m, "Get the latest app") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_DOWNLOAD)))
                    }
                    "differs" in m -> say(m, "Open the library", onAction = onLibrary)
                    else -> say(m)
                }
            } catch (e: Exception) {
                if (isActive) say("Couldn't join: ${e.message}")
            }
            busy = false
        }
    }

    fun joinByKey() {
        val target = parseRoomKey(key)
        if (target == null) {
            say("That's not a room key. It looks like K7Q-2M: the host sees it in the game, under the room's name.")
            return
        }
        busy = true
        say("Looking for room $key…", bad = false)
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    val (ip, port) = target
                    FoundHost(probe(ip, port).copy(tcpPort = port), ip)
                }
                busy = false
                connect(found)
            } catch (e: Exception) {
                busy = false
                if (isActive) say("No room $key here. Check the key, and that both phones are on the same Wi-Fi or hotspot.")
            }
        }
    }

    MultiplayerHub(
        rooms = hosts.map {
            HostChoice(
                it.beacon.title, it.beacon.name, roms.has("${it.beacon.set}.zip"), it.beacon.players, it.beacon.maxPlayers,
                it.beacon.key, it.beacon.watchers, gameForSet(it.beacon.set), it.beacon.set,
            )
        },
        key = key,
        onKey = { key = formatKeyInput(it) },
        status = status,
        busy = busy,
        onStatusAction = { action?.invoke() },
        onHost = onHost,
        onJoinKey = ::joinByKey,
        onJoinRoom = { i -> hosts.getOrNull(i)?.let(::connect) },
        onBack = onBack,
    )
}

// ----- The pages, from plain data (so they can be previewed) -----

@Composable
fun MultiplayerHub(
    rooms: List<HostChoice>,
    key: String,
    onKey: (String) -> Unit,
    status: MpStatus?,
    busy: Boolean,
    onStatusAction: () -> Unit,
    onHost: () -> Unit,
    onJoinKey: () -> Unit,
    onJoinRoom: (Int) -> Unit,
    onBack: () -> Unit,
) {
    ArcadeScreen(title = "Multiplayer", kicker = "Play together", onBack = onBack) {
        // Host.
        Panel(highlight = true, onClick = onHost) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.gold),
                ) { PlayIcon(Modifier.size(16.dp)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Host a game", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                    Text("Pick the game and play. Share the room key with friends.", color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        // Join with a key.
        SectionLabel("Join with a key")
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                ArcadeField(key, onKey, "Room key", Modifier.weight(1f), placeholder = "K7Q-2M", keyboardType = KeyboardType.Ascii)
                Spacer(Modifier.width(10.dp))
                GoldButton("Join", onJoinKey, enabled = !busy && key.isNotBlank())
            }
        }
        status?.let {
            Spacer(Modifier.height(10.dp))
            StatusPanel(it, onStatusAction)
        }
        // Rooms nearby.
        SectionLabel("Rooms nearby")
        if (rooms.isEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Looking on this Wi-Fi or hotspot…", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text("A friend's room shows up here. Not appearing? Type its key above.", color = Palette.muted, fontSize = 13.sp)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rooms.forEachIndexed { i, r -> NearbyRoomCard(r, enabled = !busy && r.have) { onJoinRoom(i) } }
            }
        }
        // How it works.
        SectionLabel("How it works")
        StepsStrip()
        Spacer(Modifier.height(10.dp))
        Text(
            "All phones on the same Wi-Fi or one phone's hotspot: no internet needed. Each needs the game (and the " +
                "Neo Geo BIOS); most games take 2 players, Cadillacs and Dinosaurs 3.",
            color = Palette.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Host: the games on this phone, as on the home screen. */
@Composable
fun PickGameScreen(ready: List<Pair<Game, String>>, onPick: (Pair<Game, String>) -> Unit, onLibrary: () -> Unit, onBack: () -> Unit) {
    ArcadeScreen(title = "Host a game", kicker = "Pick the game", onBack = onBack) {
        if (ready.isEmpty()) {
            Panel {
                Text("Add a game first (Neo Geo games also need the BIOS).", color = Palette.text, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                GoldButton("Open the library", onLibrary)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth > 600.dp) 3 else 2
                val gap = 12.dp
                val cardWidth = (maxWidth - gap * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    ready.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { pick ->
                                GameCard(pick.first, pick.second, onClick = { onPick(pick) }, onLongClick = {}, width = cardWidth)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
