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
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.gameForSet
import com.kofarcade.core.net.Beacon
import com.kofarcade.core.net.BeaconListener
import com.kofarcade.core.net.BeaconSender
import com.kofarcade.core.net.Hello
import com.kofarcade.core.net.Link
import com.kofarcade.core.net.NetHost
import com.kofarcade.core.net.PROTOCOL
import com.kofarcade.core.net.Refused
import com.kofarcade.core.net.TCP_PORT
import com.kofarcade.core.net.join
import com.kofarcade.core.net.localAddresses
import com.kofarcade.core.net.probe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** The connection handed from the 2 PLAYERS screen to the game screen. */
object Netplay {
    @Volatile var link: Link? = null
}

private sealed interface TwoPlayer {
    data object Menu : TwoPlayer
    data object PickGame : TwoPlayer
    data class Hosting(val game: Game, val set: String) : TwoPlayer
    data object Join : TwoPlayer
}

/** What a found host looks like in the Join list. */
private data class FoundHost(val beacon: Beacon, val address: InetAddress)

/** A game in the pick list. */
data class GameChoice(val title: String, val subtitle: String)

/** A hosted game found on the network. */
data class HostChoice(val title: String, val hostName: String, val have: Boolean)

private fun playerName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

/**
 * 2 PLAYERS: one phone hosts a game, the other joins it, on the same Wi-Fi or
 * on the host's hotspot. Then both open the game, the host as player 1.
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

    when (val s = screen) {
        TwoPlayer.Menu -> TwoPlayerMenu(onHost = { screen = TwoPlayer.PickGame }, onJoin = { screen = TwoPlayer.Join }, onBack = back)
        TwoPlayer.PickGame -> {
            val hasBios = remember { roms.has(BIOS) }
            val ready = remember { GAMES.filter { !it.neoGeo || hasBios }.mapNotNull { g -> roms.setFor(g)?.let { g to it } } }
            PickGameScreen(
                games = ready.map { (g, set) -> GameChoice(g.title, g.subtitle ?: g.series) },
                ready = ready.isNotEmpty(),
                onPick = { i -> ready[i].let { (g, set) -> screen = TwoPlayer.Hosting(g, set) } },
                onLibrary = onLibrary,
                onBack = back,
            )
        }
        is TwoPlayer.Hosting -> Hosting(roms, s.game, s.set, back) { onStart(s.game, s.set) }
        TwoPlayer.Join -> Joining(roms, onLibrary, back) { game, set -> onStart(game, set) }
    }
}

@Composable
private fun Hosting(roms: RomStore, game: Game, set: String, onBack: () -> Unit, onConnected: () -> Unit) {
    var status by remember { mutableStateOf("Waiting for player 2") }
    var failed by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Hello?>(null) }
    var decision by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    val addresses = remember { localAddresses() }
    val main = rememberCoroutineScope()

    DisposableEffect(game, set) {
        var host: NetHost? = null
        var beacon: BeaconSender? = null
        var left = false
        val thread = Thread({
            try {
                val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                val h = NetHost(playerName(), set, game.fullTitle, rom, bios).also { host = it }
                beacon = BeaconSender(Beacon(PROTOCOL, playerName(), set, game.fullTitle, rom, h.port))
                val link = h.accept { hello ->
                    // Ask on screen; the network thread waits for the tap.
                    val answer = CompletableDeferred<String?>()
                    decision = answer
                    request = hello
                    runBlocking { answer.await() }.also { request = null }
                }
                beacon?.close()
                h.close()
                if (left) {
                    link.close()
                } else {
                    Netplay.link = link
                    status = "Starting the game"
                    main.launch { onConnected() }
                }
            } catch (e: Exception) {
                if (!left) {
                    failed = true
                    status = "Hosting stopped: ${e.message}"
                }
            }
        }, "net-host")
        thread.isDaemon = true
        thread.start()
        onDispose {
            left = true
            beacon?.close()
            host?.close()
            decision?.complete("The host left")
        }
    }

    HostingScreen(
        title = game.fullTitle,
        status = status,
        failed = failed,
        addresses = addresses,
        requestName = request?.name,
        onAccept = { decision?.complete(null) },
        onDecline = { decision?.complete("The host said no") },
        onBack = onBack,
    )
}

@Composable
private fun Joining(roms: RomStore, onLibrary: () -> Unit, onBack: () -> Unit, onJoined: (Game, String) -> Unit) {
    val hosts = remember { mutableStateListOf<FoundHost>() }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var needsGame by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        status = "Waiting for ${found.beacon.name} to accept…"
        scope.launch {
            try {
                val link = withContext(Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    join(found.address, found.beacon.tcpPort, playerName(), set, rom, bios)
                }
                Netplay.link = link
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
        hosts = hosts.map { HostChoice(it.beacon.title, it.beacon.name, roms.has("${it.beacon.set}.zip")) },
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
    ArcadeScreen(title = "2 Players", kicker = "Two phones, one game", onBack = onBack) {
        Text(
            "Both phones on the same Wi-Fi, or one phone's hotspot with the other connected to it. " +
                "Both need the same game and BIOS: get them from the library on each phone.",
            color = Palette.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        BigChoice("Host a game", "You're player 1. Pick the game; your partner joins it.", badge = "1P", gold = true, onClick = onHost)
        Spacer(Modifier.height(12.dp))
        BigChoice("Join a game", "You're player 2. Find the game your partner is hosting.", badge = "2P", onClick = onJoin)
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
    ArcadeScreen(title = "Host a game", kicker = "Step 1 · pick the game", onBack = onBack) {
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
fun HostingScreen(
    title: String,
    status: String,
    failed: Boolean,
    addresses: List<String>,
    requestName: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
) {
    ArcadeScreen(title = title, kicker = "Hosting · you're player 1", onBack = onBack) {
        Panel(highlight = requestName != null) {
            if (requestName != null) {
                Kicker("Player 2 is here")
                Spacer(Modifier.height(6.dp))
                Text("$requestName wants to join", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldButton("Accept", onAccept, Modifier.weight(1f))
                    GhostButton("Decline", onDecline, Modifier.weight(1f))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (failed) Palette.red else Palette.gold)
                    Spacer(Modifier.width(10.dp))
                    Text(status, color = if (failed) Palette.red else Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "On the other phone: 2 Players → Join a game. It finds this phone by itself.",
                    color = Palette.muted,
                    fontSize = 14.sp,
                )
            }
        }
        SectionLabel("If it doesn't find you")
        Panel {
            Text("Type this address on the other phone:", color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (addresses.isEmpty()) {
                Text("Not on a network. Turn on Wi-Fi or your hotspot.", color = Palette.red, fontSize = 15.sp)
            } else {
                addresses.forEach { Text(it, color = Palette.gold, fontSize = 24.sp, fontWeight = FontWeight.Black) }
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
    ArcadeScreen(title = "Join a game", kicker = "You're player 2", onBack = onBack) {
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
        SectionLabel("Games on this network")
        if (hosts.isEmpty()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot()
                    Spacer(Modifier.width(10.dp))
                    Text("Looking for games…", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("On the other phone: 2 Players → Host a game.", color = Palette.muted, fontSize = 13.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.forEachIndexed { i, h ->
                Panel(onClick = if (busy) null else ({ onJoinHost(i) })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                            Text("Hosted by ${h.hostName}", color = Palette.muted, fontSize = 13.sp)
                        }
                        if (h.have) Tag("Join", Palette.gold, filled = true) else Tag("Need game", Palette.red)
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
