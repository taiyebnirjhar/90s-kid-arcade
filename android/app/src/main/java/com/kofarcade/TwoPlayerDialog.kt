package com.kofarcade

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.gameForSet
import com.kofarcade.core.net.Beacon
import com.kofarcade.core.net.BeaconListener
import com.kofarcade.core.net.PROTOCOL
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

private sealed interface TwoPlayer {
    data object Menu : TwoPlayer
    data object PickGame : TwoPlayer
    data object Join : TwoPlayer
}

/** What a found host looks like in the Join list. */
private data class FoundHost(val beacon: Beacon, val address: InetAddress)

/** A game in the pick list. */
data class GameChoice(val title: String, val subtitle: String)

/** A room found on the network: [players] playing of [maxPlayers], [watchers] watching. */
data class HostChoice(
    val title: String,
    val hostName: String,
    val have: Boolean,
    val players: Int = 1,
    val maxPlayers: Int = 2,
    val key: String = "",
    val watchers: Int = 0,
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
 * MULTIPLAYER: one phone opens a room and starts playing; it shows the
 * room's key. Others join with the key (or from the list of rooms nearby),
 * on the same Wi-Fi or on a phone's hotspot, and watch; COIN or START takes
 * a free seat. Anyone can leave; the room carries on.
 */
@Composable
fun TwoPlayerDialog(roms: RomStore, onStart: (Game, String) -> Unit, onLibrary: () -> Unit, onClose: () -> Unit) {
    var screen by remember { mutableStateOf<TwoPlayer>(TwoPlayer.Menu) }
    val context = LocalContext.current

    // Android drops broadcast packets (how hosts are found) unless asked.
    DisposableEffect(Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("90s-kid-arcade").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        onDispose { runCatching { lock.release() } }
    }
    val back = { if (screen == TwoPlayer.Menu) onClose() else screen = TwoPlayer.Menu }
    BackHandler(onBack = back)

    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }

    // Opens a room for the game and starts playing; others join as it runs.
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
                    Room.host(playerName(), set, game.fullTitle, rom, bios, game.players, build = emulatorBuild(context))
                }
                if (!isActive) {
                    room.close()
                    return@launch
                }
                Netplay.room = room
                onStart(game, set)
            } catch (e: Exception) {
                if (isActive) Toast.makeText(context, "Couldn't open a room: ${e.message}", Toast.LENGTH_LONG).show()
            }
            opening = false
        }
    }

    when (screen) {
        TwoPlayer.Menu -> TwoPlayerMenu(onHost = { screen = TwoPlayer.PickGame }, onJoin = { screen = TwoPlayer.Join }, onBack = back)
        TwoPlayer.PickGame -> {
            val hasBios = remember { roms.has(BIOS) }
            val ready = remember { GAMES.filter { !it.neoGeo || hasBios }.mapNotNull { g -> roms.setFor(g)?.let { g to it } } }
            PickGameScreen(
                games = ready.map { (g, _) -> GameChoice(g.title, g.subtitle ?: g.series) },
                ready = ready.isNotEmpty(),
                onPick = { i -> ready[i].let { (g, set) -> host(g, set) } },
                onLibrary = onLibrary,
                onBack = back,
            )
        }
        TwoPlayer.Join -> Joining(roms, onLibrary, back) { game, set -> onStart(game, set) }
    }
}

@Composable
private fun Joining(roms: RomStore, onLibrary: () -> Unit, onBack: () -> Unit, onJoined: (Game, String) -> Unit) {
    val hosts = remember { mutableStateListOf<FoundHost>() }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var needsGame by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Gone from this screen: a join that's let in afterwards leaves at once.
    val left = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { left.set(true) }
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
        }.onFailure { status = "Can't look for games: ${it.message}" }.getOrNull()
        onDispose { listener?.close() }
    }

    fun connect(found: FoundHost) {
        val set = found.beacon.set
        val game = gameForSet(set)
        needsGame = false
        if (game == null || !roms.has("$set.zip")) {
            needsGame = true
            status = "You need ${found.beacon.title} on this phone first."
            return
        }
        busy = true
        status = "Joining ${found.beacon.name}'s room…"
        scope.launch {
            try {
                val room = withContext(NonCancellable + Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    Room.join(found.address, found.beacon.tcpPort, playerName(), set, rom, bios, build = emulatorBuild(context)).also { room ->
                        if (left.get()) room.close()
                    }
                }
                if (left.get() || !isActive) {
                    room.close()
                    return@launch
                }
                Netplay.room = room
                onJoined(game, set)
            } catch (e: Refused) {
                status = e.message
            } catch (e: Exception) {
                if (isActive) status = "Couldn't connect: ${e.message}"
            }
            busy = false
        }
    }

    JoinScreen(
        hosts = hosts.map {
            HostChoice(
                it.beacon.title, it.beacon.name, roms.has("${it.beacon.set}.zip"), it.beacon.players, it.beacon.maxPlayers,
                it.beacon.key, it.beacon.watchers,
            )
        },
        address = address,
        onAddress = { address = it },
        status = status,
        needsGame = needsGame,
        busy = busy,
        onJoinHost = { i -> hosts.getOrNull(i)?.let(::connect) },
        onJoinAddress = {
            val target = parseRoomKey(address)
            if (target == null) {
                status = "That's not a room key. It looks like K7Q-2M: in the host's pause menu."
                needsGame = false
            } else {
                busy = true
                status = "Looking for room ${address.trim().uppercase()}…"
                scope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) {
                            val (ip, port) = target
                            FoundHost(probe(ip, port).copy(tcpPort = port), ip)
                        }
                        busy = false
                        connect(found)
                    } catch (e: Exception) {
                        status = "No room found for ${address.trim()}. Are both phones on the same Wi-Fi or hotspot? (${e.message})"
                        busy = false
                    }
                }
            }
        },
        onLibrary = onLibrary,
        onBack = onBack,
    )
}

// ----- The pages, from plain data (so they can be previewed) -----

@Composable
fun TwoPlayerMenu(onHost: () -> Unit, onJoin: () -> Unit, onBack: () -> Unit) {
    ArcadeScreen(title = "Multiplayer", kicker = "One phone per player", onBack = onBack) {
        Text(
            "All phones on the same Wi-Fi or on one phone's hotspot (no internet needed). Each needs the same game " +
                "(and BIOS for Neo Geo games): get them from the library on each phone. " +
                "Join with the room's key and watch; press COIN or START to take a free seat. Leave any time: " +
                "the game goes on for the others. Most games take 2 players; Cadillacs and Dinosaurs takes 3.",
            color = Palette.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        BigChoice("Open a room", "Pick the game and play; you get a room key for friends.", badge = "1P", gold = true, onClick = onHost)
        Spacer(Modifier.height(12.dp))
        BigChoice("Join a room", "Type a friend's room key, or pick a room nearby.", badge = "2P+", onClick = onJoin)
    }
}

@Composable
private fun BigChoice(title: String, sub: String, badge: String, gold: Boolean = false, onClick: () -> Unit) {
    Panel(highlight = gold, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (gold) Palette.gold else Palette.bg)
                    .border(1.dp, if (gold) Palette.gold else Palette.line, RoundedCornerShape(16.dp)),
            ) {
                Text(badge, color = if (gold) OnGold else Palette.gold, fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text(sub, color = Palette.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun PickGameScreen(games: List<GameChoice>, ready: Boolean, onPick: (Int) -> Unit, onLibrary: () -> Unit, onBack: () -> Unit) {
    ArcadeScreen(title = "Open a room", kicker = "Pick the game", onBack = onBack) {
        if (!ready) {
            Panel {
                Text("Add a game first (Neo Geo games also need the BIOS).", color = Palette.text, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                GoldButton("Open the library", onLibrary)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEachIndexed { i, g ->
                    Panel(onClick = { onPick(i) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                                Text(g.subtitle, color = Palette.muted, fontSize = 13.sp)
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape).background(Palette.gold)) {
                                PlayIcon(Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JoinScreen(
    hosts: List<HostChoice>,
    address: String,
    onAddress: (String) -> Unit,
    status: String?,
    needsGame: Boolean,
    busy: Boolean,
    onJoinHost: (Int) -> Unit,
    onJoinAddress: () -> Unit,
    onLibrary: () -> Unit,
    onBack: () -> Unit,
) {
    ArcadeScreen(title = "Join a room", kicker = "One phone per player", onBack = onBack) {
        Panel(highlight = true) {
            ArcadeField(address, onAddress, "Room key", placeholder = "K7Q-2M", keyboardType = KeyboardType.Ascii)
            Spacer(Modifier.height(12.dp))
            GoldButton("Join", onJoinAddress, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank())
            Spacer(Modifier.height(8.dp))
            Text("The host sees it when the room opens, and in its pause menu.", color = Palette.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        status?.let {
            Panel(highlight = !needsGame) {
                Text(it, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (needsGame) {
                    Spacer(Modifier.height(12.dp))
                    GoldButton("Open the library", onLibrary)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        SectionLabel("Or a room nearby")
        if (hosts.isEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Looking for rooms…", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Not showing up? Type the room key above.", color = Palette.muted, fontSize = 13.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.forEachIndexed { i, h ->
                Panel(onClick = if (busy) null else ({ onJoinHost(i) })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                            Text(
                                (if (h.key.isNotEmpty()) "Room ${h.key} · " else "") +
                                    "${h.hostName} · ${h.players} of ${h.maxPlayers} playing" +
                                    if (h.watchers > 0) " · ${h.watchers} watching" else "",
                                color = Palette.muted,
                                fontSize = 13.sp,
                            )
                        }
                        when {
                            !h.have -> Tag("Need game", Palette.red)
                            h.players >= h.maxPlayers -> Tag("Watch", Palette.gold)
                            else -> Tag("Join", Palette.gold, filled = true)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
