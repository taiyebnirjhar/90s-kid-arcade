package com.kofarcade

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kofarcade.core.BIOS
import com.kofarcade.core.Game
import com.kofarcade.core.GAMES
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.bg).systemBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2 PLAYERS", color = Palette.gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (screen != TwoPlayer.Menu) TextButton(onClick = { screen = TwoPlayer.Menu }) { Text("Back") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.height(8.dp))
            when (val s = screen) {
                TwoPlayer.Menu -> {
                    Text(
                        "Play together on two phones: both on the same Wi-Fi, or one phone's hotspot with the other " +
                            "connected to it. One hosts (player 1), the other joins (player 2). Both need the same game " +
                            "and Neo Geo BIOS; the library has them.",
                        color = Palette.muted,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = TwoPlayer.PickGame }, modifier = Modifier.fillMaxWidth()) { Text("Host a game") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { screen = TwoPlayer.Join }, modifier = Modifier.fillMaxWidth()) { Text("Join a game") }
                }
                TwoPlayer.PickGame -> PickGame(roms, onLibrary) { game, set -> screen = TwoPlayer.Hosting(game, set) }
                is TwoPlayer.Hosting -> Hosting(roms, s.game, s.set) { onStart(s.game, s.set) }
                TwoPlayer.Join -> Joining(roms, onLibrary) { game, set -> onStart(game, set) }
            }
        }
    }
}

private fun playerName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

@Composable
private fun PickGame(roms: RomStore, onLibrary: () -> Unit, onPick: (Game, String) -> Unit) {
    val ready = remember { GAMES.mapNotNull { g -> roms.setFor(g)?.let { g to it } } }
    Text("Pick the game to host:", color = Palette.text, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (!roms.has(BIOS) || ready.isEmpty()) {
        Text("Add the Neo Geo BIOS and a game first.", color = Palette.muted)
        TextButton(onClick = onLibrary) { Text("Open the library") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ready.forEach { (game, set) -> Choice(game.title, game.subtitle ?: set) { onPick(game, set) } }
    }
}

@Composable
private fun Hosting(roms: RomStore, game: Game, set: String, onConnected: () -> Unit) {
    var status by remember { mutableStateOf("Waiting for player 2…") }
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
                val bios = roms.fingerprint(BIOS) ?: error("Can't read the BIOS")
                val h = NetHost(playerName(), set, game.title, rom, bios).also { host = it }
                beacon = BeaconSender(Beacon(PROTOCOL, playerName(), set, game.title, rom, h.port))
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
                    status = "Starting…"
                    main.launch { onConnected() }
                }
            } catch (e: Exception) {
                if (!left) status = "Hosting stopped: ${e.message}"
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

    Text(game.title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(status, color = Palette.muted)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "On the other phone: 2 PLAYERS → Join a game. It should find this phone by itself; if not, type this " +
            "address there: ${addresses.joinToString(" or ").ifEmpty { "(not on a network)" }}",
        color = Palette.muted,
        fontSize = 13.sp,
    )
    request?.let { hello ->
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
                .border(1.dp, Palette.gold, RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Text("${hello.name} wants to join", color = Palette.text, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Button(onClick = { decision?.complete(null) }) { Text("Accept") }
                OutlinedButton(onClick = { decision?.complete("The host said no") }) { Text("Decline") }
            }
        }
    }
}

@Composable
private fun Joining(roms: RomStore, onLibrary: () -> Unit, onJoined: (Game, String) -> Unit) {
    val hosts = remember { mutableStateListOf<FoundHost>() }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
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
        if (game == null || !roms.has("$set.zip")) {
            status = "You need ${found.beacon.title} ($set.zip) on this phone first. Get it from the library."
            return
        }
        busy = true
        status = "Waiting for ${found.beacon.name} to accept…"
        scope.launch {
            try {
                val link = withContext(Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = roms.fingerprint(BIOS) ?: error("Can't read the BIOS")
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

    Text("Games on this network:", color = Palette.text, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (hosts.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking… (the other phone: 2 PLAYERS → Host a game)", color = Palette.muted, fontSize = 13.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hosts.forEach { h ->
            val have = roms.has("${h.beacon.set}.zip")
            Choice(h.beacon.title, "${h.beacon.name}" + if (have) "" else " · you don't have this game", enabled = !busy) { connect(h) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Or type the address shown on the host:", color = Palette.muted, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            placeholder = { Text("192.168.43.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
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
            enabled = !busy && address.isNotBlank(),
        ) { Text("Join") }
    }
    status?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = Palette.text, fontSize = 14.sp)
        if ("library" in it) TextButton(onClick = onLibrary) { Text("Open the library") }
    }
}

@Composable
private fun Choice(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
    ) {
        Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Palette.muted, fontSize = 12.sp)
    }
}
