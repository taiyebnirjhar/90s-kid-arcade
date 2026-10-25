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
import com.kofarcade.core.net.TCP_PORT
import com.kofarcade.core.net.probe
import kotlinx.coroutines.Dispatchers
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

/** A room found on the network: [players] in of [maxPlayers]. */
data class HostChoice(val title: String, val hostName: String, val have: Boolean, val players: Int = 1, val maxPlayers: Int = 2, val code: String = "")

private fun playerName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

/**
 * MULTIPLAYER: one phone opens a room and starts playing; the others join it
 * whenever there's a free seat (one phone per player, as many as the game
 * takes), on the same Wi-Fi or on a phone's hotspot. Anyone can leave; the
 * room carries on.
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
                Netplay.room = withContext(Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    Room.host(playerName(), set, game.fullTitle, rom, bios, game.players)
                }
                onStart(game, set)
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open a room: ${e.message}", Toast.LENGTH_LONG).show()
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
    DisposableEffect(Unit) {
        onDispose { left.set(true) }
    }

    DisposableEffect(Unit) {
        val listener = runCatching {
            BeaconListener { beacon, from ->
                val found = FoundHost(beacon, from)
                if (hosts.none { it.address == from && it.beacon == beacon }) {
                    hosts.removeAll { it.address == from }
                    hosts += found
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
        status = "Waiting for ${found.beacon.name} to let you in…"
        scope.launch {
            try {
                val room = withContext(Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    Room.join(found.address, found.beacon.tcpPort, playerName(), set, rom, bios).also { room ->
                        if (left.get()) room.close()
                    }
                }
                if (left.get()) return@launch
                Netplay.room = room
                onJoined(game, set)
            } catch (e: Refused) {
                status = e.message
            } catch (e: Exception) {
                status = "Couldn't connect: ${e.message}"
            }
            busy = false
        }
    }

    JoinScreen(
        hosts = hosts.map {
            HostChoice(it.beacon.title, it.beacon.name, roms.has("${it.beacon.set}.zip"), it.beacon.players, it.beacon.maxPlayers, it.beacon.code)
        },
        address = address,
        onAddress = { address = it },
        status = status,
        needsGame = needsGame,
        busy = busy,
        onJoinHost = { i -> hosts.getOrNull(i)?.let(::connect) },
        onJoinAddress = {
            busy = true
            status = "Looking for $address…"
            scope.launch {
                try {
                    val found = withContext(Dispatchers.IO) {
                        val ip = InetAddress.getByName(address.trim())
                        FoundHost(probe(ip, TCP_PORT), ip)
                    }
                    busy = false
                    connect(found)
                } catch (e: Exception) {
                    status = "No game found at $address: ${e.message}"
                    busy = false
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
            "All phones on the same Wi-Fi, or on one phone's hotspot. Each needs the same game " +
                "(and BIOS for Neo Geo games): get them from the library on each phone. " +
                "Join any time there's a free seat, leave any time: the game goes on for the others. " +
                "Most games take 2 players; Cadillacs and Dinosaurs takes 3.",
            color = Palette.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        BigChoice("Open a room", "Pick the game and start playing; friends join as you play.", badge = "1P", gold = true, onClick = onHost)
        Spacer(Modifier.height(12.dp))
        BigChoice("Join a room", "Find the room a friend is playing in.", badge = "2P+", onClick = onJoin)
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
        SectionLabel("Rooms on this network")
        if (hosts.isEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Looking for rooms…", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("On a friend's phone: Multiplayer → Open a room.", color = Palette.muted, fontSize = 13.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.forEachIndexed { i, h ->
                Panel(onClick = if (busy) null else ({ onJoinHost(i) })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                            Text(
                                (if (h.code.isNotEmpty()) "Room ${h.code} · " else "") +
                                    "${h.hostName} · ${h.players} of ${h.maxPlayers} in",
                                color = Palette.muted,
                                fontSize = 13.sp,
                            )
                        }
                        when {
                            !h.have -> Tag("Need game", Palette.red)
                            h.players >= h.maxPlayers -> Tag("Full", Palette.muted)
                            else -> Tag("Join", Palette.gold, filled = true)
                        }
                    }
                }
            }
        }
        SectionLabel("Or join by address")
        Panel {
            ArcadeField(address, onAddress, "Address shown on the host", placeholder = "192.168.43.1", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            GoldButton("Join", onJoinAddress, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank())
        }
        Spacer(Modifier.height(24.dp))
    }
}
