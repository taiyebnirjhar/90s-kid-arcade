package com.kofarcade

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.kofarcade.core.net.Redirected
import com.kofarcade.core.net.explainConnectionTrouble
import com.kofarcade.core.net.Refused
import com.kofarcade.core.net.Room
import com.kofarcade.core.net.parseRoomKey
import com.kofarcade.core.net.probe
import com.kofarcade.core.online.CantJoin
import com.kofarcade.core.online.ListedRoom
import com.kofarcade.core.online.OnlineError
import com.kofarcade.core.online.OnlineRooms
import com.kofarcade.core.online.explainOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** The room handed from the MULTIPLAYER screen to the game screen. */
object Netplay {
    @Volatile var room: Room? = null

    /** An internet room's connections and server record, closed with the game (null on Wi-Fi). */
    @Volatile var online: com.kofarcade.core.online.OnlineRoom? = null
}

/** Where the latest app downloads from (the website's button). */
const val APP_DOWNLOAD = "https://github.com/taiyebnirjhar/90s-kid-arcade/releases/latest/download/90s-Kid-Arcade.apk"

private sealed interface TwoPlayer {
    data object Hub : TwoPlayer
    /** Picking the game to host: on this Wi-Fi, or [online] over the internet. */
    data class PickGame(val online: Boolean = false) : TwoPlayer
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
fun TwoPlayerDialog(
    roms: RomStore,
    onStart: (Game, String) -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
    /** An internet room's code from a link: joined at once. */
    joinCode: String? = null,
    onCodeTaken: () -> Unit = {},
) {
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
    // Online rooms: open (listed, anyone joins) or locked (only with the code), as last picked.
    var openRoom by remember { mutableStateOf(Prefs(context).openRooms) }

    // Opens a room for the game and starts playing; the game screen shows its key
    // (or, [online], its code: the room is made on the server, friends anywhere join).
    fun host(game: Game, set: String, online: Boolean) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                // Never dropped half-made: if the screen's gone by the time
                // the room is open, it's closed again.
                val (room, internet) = withContext(NonCancellable + Dispatchers.IO) {
                    val rom = roms.fingerprint("$set.zip") ?: error("Can't read $set.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    if (online) {
                        // Signed in, with a profile (its name is the room's).
                        Online.account(context).sync(Online.defaultName())
                        val r = Online.rooms(context).host(
                            Online.playerName(context), set, game.fullTitle, rom, bios, game.players, emulatorBuild(context), Prefs(context).deviceId,
                            open = openRoom,
                        )
                        r.room to r
                    } else {
                        Room.host(Online.playerName(context), set, game.fullTitle, rom, bios, game.players, build = emulatorBuild(context), device = Prefs(context).deviceId) to null
                    }
                }
                // Gone back from the game picker meanwhile: the room's closed again, not started.
                if (!isActive || screen !is TwoPlayer.PickGame) {
                    withContext(NonCancellable + Dispatchers.IO) { internet?.close() ?: room.close() }
                    return@launch
                }
                Netplay.room = room
                Netplay.online = internet
                onStart(game, set)
            } catch (e: Exception) {
                val why = (e as? OnlineError)?.let(::explainRoomTrouble) ?: e.message ?: e.javaClass.simpleName
                if (isActive) android.widget.Toast.makeText(context, "Couldn't open a room: $why", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                opening = false
            }
        }
    }

    Crossfade(screen, animationSpec = tween(220), label = "multiplayer") { shown ->
        when (shown) {
            TwoPlayer.Hub -> Hub(
                roms,
                onHost = { screen = TwoPlayer.PickGame() },
                onHostOnline = { screen = TwoPlayer.PickGame(online = true) },
                onLibrary = onLibrary,
                onBack = back,
                onJoined = onStart,
                joinCode = joinCode,
                onCodeTaken = onCodeTaken,
            )
            is TwoPlayer.PickGame -> {
                val hasBios = remember { roms.has(BIOS) }
                val ready = remember { GAMES.filter { !it.neoGeo || hasBios }.mapNotNull { g -> roms.setFor(g)?.let { g to it } } }
                PickGameScreen(
                    ready,
                    onPick = { (g, set) -> host(g, set, shown.online) },
                    onLibrary = onLibrary,
                    onBack = back,
                    online = shown.online,
                    opening = opening,
                    open = openRoom,
                    onOpen = {
                        openRoom = it
                        Prefs(context).openRooms = it
                    },
                )
            }
        }
    }
}

// The hub: finds rooms nearby, joins by key or by tap.
@Composable
private fun Hub(
    roms: RomStore,
    onHost: () -> Unit,
    onHostOnline: () -> Unit,
    onLibrary: () -> Unit,
    onBack: () -> Unit,
    onJoined: (Game, String) -> Unit,
    joinCode: String? = null,
    onCodeTaken: () -> Unit = {},
) {
    val context = LocalContext.current
    val hosts = remember { mutableStateListOf<FoundHost>() }
    var key by remember { mutableStateOf("") }
    val internet = rememberInternet()
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
    // Friends: the list, requests and invites, asked with the rooms.
    var friends by remember { mutableStateOf(FriendsUi()) }
    var friendCode by remember { mutableStateOf("") }
    // Rooms online (the server's list), asked again every few seconds while this screen's up.
    val listed = remember { mutableStateListOf<ListedRoom>() }
    var listNote by remember { mutableStateOf<String?>(null) }
    var askedLocked by remember { mutableStateOf<ListedRoom?>(null) }
    androidx.compose.runtime.LaunchedEffect(internet) {
        if (!internet) {
            listed.clear()
            listNote = null
            return@LaunchedEffect
        }
        val rooms = Online.rooms(context)
        val server = Online.friends(context)
        var signedIn = false
        var presenceAt = 0L
        while (isActive) {
            val got = withContext(Dispatchers.IO) {
                runCatching {
                    if (!signedIn) Online.account(context).sync(Online.defaultName())
                    signedIn = true
                    rooms.list()
                }
            }
            // Friends too: who's on, their rooms, requests and invites; and that this phone's on (each minute).
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    if (System.currentTimeMillis() - presenceAt > PRESENCE_EVERY_MS) {
                        server.presence(null, null)
                        presenceAt = System.currentTimeMillis()
                    }
                    FriendsUi(Online.account(context).profile()?.friendCode, server.list(), server.invites())
                }
            }
            friends = f.getOrElse { friends.copy(note = (it as? OnlineError)?.let(::explainRoomTrouble) ?: "Couldn't load your friends.") }
            got.onSuccess {
                listNote = null
                if (it != listed.toList()) {
                    listed.clear()
                    listed += it
                }
            }.onFailure {
                listNote = (it as? OnlineError)?.let(::explainRoomTrouble) ?: "Couldn't load the rooms online."
            }
            kotlinx.coroutines.delay(if (got.isSuccess) LIST_EVERY_MS else LIST_RETRY_MS)
        }
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
                    // Over the host's Wi-Fi, even if Android prefers mobile data now (LanLink).
                    val link = LanLink(context, target = { found.address.hostAddress })
                    try {
                        link.await(500)
                        fun joinAt(address: java.net.InetAddress, port: Int) =
                            Room.join(address, port, Online.playerName(context), set, rom, bios, build = emulatorBuild(context), device = Prefs(context).deviceId)
                        // A phone of the room that isn't its host (the key's from before a
                        // takeover) says who is: straight there.
                        val joined = try {
                            joinAt(found.address, found.beacon.tcpPort)
                        } catch (e: Redirected) {
                            joinAt(InetAddress.getByName(e.at.host), e.at.port)
                        }
                        joined.also { room -> if (left.get()) room.close() }
                    } finally {
                        // The game screen ties the game to the Wi-Fi itself from here.
                        link.stop()
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
                if (isActive) say(explainConnectionTrouble(e) ?: "Couldn't join: ${e.message ?: e.javaClass.simpleName}")
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
                    try {
                        FoundHost(probe(ip, port).copy(tcpPort = port), ip)
                    } catch (e: Redirected) {
                        // The key's phone isn't the host any more: it says who is.
                        val at = InetAddress.getByName(e.at.host)
                        FoundHost(probe(at, e.at.port).copy(tcpPort = e.at.port), at)
                    }
                }
                busy = false
                connect(found)
            } catch (e: Exception) {
                busy = false
                if (isActive) say(explainConnectionTrouble(e) ?: "No room $key here. Check the key, and that both phones are on the same Wi-Fi or hotspot.")
            }
        }
    }

    // An internet room, by its code: looked up on the server, checked against
    // this phone's games, then joined over WebRTC.
    fun joinByCode(clean: String, nearby: (() -> Unit)?) {
        if (busy) return
        busy = true
        say("Looking for room ${OnlineRooms.pretty(clean)}…", bad = false)
        scope.launch {
            try {
                val rooms = Online.rooms(context)
                val info = withContext(Dispatchers.IO) {
                    Online.account(context).sync(Online.defaultName())
                    rooms.find(clean)
                }
                val game = gameForSet(info.set)
                val title = game?.fullTitle ?: info.set
                if (game == null || !roms.has("${info.set}.zip") || (game.neoGeo && !roms.has(BIOS))) {
                    busy = false
                    val what = if (game?.neoGeo == true && roms.has("${info.set}.zip")) "the Neo Geo BIOS" else "$title (${info.set})"
                    say("You need $what to join ${info.ownerName ?: "this"} room.", "Get it from the library", onAction = onLibrary)
                    return@launch
                }
                say("Joining ${info.ownerName ?: "the"} room…", bad = false)
                val online = withContext(NonCancellable + Dispatchers.IO) {
                    val rom = roms.fingerprint("${info.set}.zip") ?: error("Can't read ${info.set}.zip")
                    val bios = if (game.neoGeo) roms.fingerprint(BIOS) ?: error("Can't read the BIOS") else 0L
                    if (rom != info.rom || bios != info.bios) {
                        throw Refused("Your copy of $title differs from the host's. Download it from the library on every phone, so all play the same version.")
                    }
                    rooms.join(info, Online.playerName(context), info.set, rom, bios, emulatorBuild(context), Prefs(context).deviceId)
                        .also { if (left.get()) it.close() }
                }
                if (left.get() || !isActive) {
                    // Off the main thread: leaving waits for the goodbye and tells the server.
                    Thread { online.close() }.start()
                    return@launch
                }
                Netplay.room = online.room
                Netplay.online = online
                status = null
                onJoined(game, info.set)
            } catch (e: Refused) {
                val m = e.message.orEmpty()
                when {
                    "app builds" in m || "app versions" in m -> say(m, "Get the latest app") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_DOWNLOAD)))
                    }
                    "differs" in m -> say(m, "Open the library", onAction = onLibrary)
                    else -> say(m)
                }
            } catch (e: CantJoin) {
                // Not an online room: a nearby room's key, perhaps.
                if (nearby != null) {
                    busy = false
                    nearby()
                    return@launch
                }
                say(e.message.orEmpty())
            } catch (e: OnlineError) {
                say(explainRoomTrouble(e))
            } catch (e: Exception) {
                if (isActive) say("Couldn't join: ${e.message ?: e.javaClass.simpleName}")
            }
            busy = false
        }
    }

    // One field for both: an online room's code (6 characters), else (or if
    // there's no such room) a nearby room's key.
    fun joinAny() {
        val clean = key.uppercase().filter { it.isLetterOrDigit() }
        val nearby = if (parseRoomKey(key) != null) ::joinByKey else null
        when {
            internet && clean.length == 6 -> joinByCode(clean, nearby)
            nearby != null -> nearby()
            clean.length == 6 -> say("No internet: joining by code needs it. A nearby room's key works without (same Wi-Fi or hotspot).")
            else -> say("That's not a room code. It looks like K7Q-2MX: your friend sees it in their room.")
        }
    }

    // From a room link: its code, joined at once (not over a game still on, or a join under way).
    androidx.compose.runtime.LaunchedEffect(joinCode) {
        if (joinCode != null) {
            key = formatKeyInput(joinCode)
            onCodeTaken()
            when {
                Netplay.room != null -> say("You're still in a room. Leave that game first, then tap Join.")
                busy -> {}
                !internet -> say("No internet: joining by code needs it. Nearby rooms work without (same Wi-Fi or hotspot).")
                else -> joinByCode(joinCode, null)
            }
        }
    }

    // A room on the list: an open one joins at once; a locked one asks for its code.
    fun joinListed(r: ListedRoom) {
        val game = gameForSet(r.set)
        if (game == null || !roms.has("${r.set}.zip") || (game.neoGeo && !roms.has(BIOS))) {
            say("You need ${game?.fullTitle ?: r.set} on this phone first.", "Get it from the library", onAction = onLibrary)
            return
        }
        val code = r.code
        if (code != null) joinByCode(code, null) else askedLocked = r
    }

    // A friend action on the server (it returns what to tell the player, if anything), then the list again.
    fun friendCall(call: (com.kofarcade.core.online.FriendServer) -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val server = Online.friends(context)
                    val told = call(server)
                    told to FriendsUi(Online.account(context).profile()?.friendCode, server.list(), server.invites())
                }
            }
            busy = false
            r.onSuccess { (told, list) ->
                friends = list
                if (told != null) say(told, bad = false)
            }.onFailure {
                say((it as? OnlineError)?.let { e -> explainFriendTrouble(e) } ?: "Couldn't do that: ${it.message}")
            }
        }
    }

    fun addFriend() {
        val code = friendCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) {
            say("A friend code has 8 letters and numbers, like K7Q2-MX8P: it's on your friend's Online screen.")
            return
        }
        friendCode = ""
        friendCall {
            when (it.request(code)) {
                com.kofarcade.core.online.Asked.FRIENDS -> "You're friends now."
                com.kofarcade.core.online.Asked.ASKED -> "Asked. You're friends once they accept."
            }
        }
    }

    fun shareCode() {
        val code = friends.myCode ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Add me on 90s Kid Arcade: my friend code is ${com.kofarcade.core.online.prettyFriendCode(code)}")
        }
        context.startActivity(Intent.createChooser(send, "Share your friend code"))
    }

    askedLocked?.let { r ->
        LockedRoomDialog(
            hostName = r.ownerName ?: "The host",
            title = gameForSet(r.set)?.fullTitle ?: r.set,
            onJoin = { code ->
                askedLocked = null
                key = formatKeyInput(code)
                joinByCode(code.uppercase().filter { it.isLetterOrDigit() }, null)
            },
            onDismiss = { askedLocked = null },
        )
    }

    MultiplayerHub(
        online = listed.map {
            val game = gameForSet(it.set)
            OnlineChoice(
                game?.fullTitle ?: it.set, it.ownerName ?: "Player",
                game != null && roms.has("${it.set}.zip") && (!game.neoGeo || roms.has(BIOS)),
                it.members, it.maxPlayers, it.open, game, it.set,
            )
        },
        onJoinOnline = { i -> listed.getOrNull(i)?.let(::joinListed) },
        onlineNote = listNote,
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
        onJoinKey = ::joinAny,
        onJoinRoom = { i -> hosts.getOrNull(i)?.let(::connect) },
        onBack = onBack,
        internet = internet,
        onHostOnline = onHostOnline,
        friends = if (internet) friends else null,
        friendCode = friendCode,
        onFriendCode = { friendCode = formatFriendCode(it) },
        onAddFriend = ::addFriend,
        onShareCode = ::shareCode,
        onJoinInvite = { inv ->
            // Seen: off the list (in the background), and in.
            scope.launch(Dispatchers.IO) { runCatching { Online.friends(context).dismiss(inv.id) } }
            friends = friends.copy(invites = friends.invites - inv)
            key = formatKeyInput(inv.room)
            joinByCode(inv.room, null)
        },
        onDismissInvite = { inv -> friendCall { it.dismiss(inv.id); null } },
        onAnswerFriend = { f, yes ->
            friendCall {
                it.answer(f.id, yes)
                if (yes) "You and ${f.name} are friends" else null
            }
        },
        onJoinFriend = { f ->
            val code = f.room ?: return@MultiplayerHub
            key = formatKeyInput(code)
            joinByCode(code, null)
        },
        onRemoveFriend = { f -> friendCall { it.remove(f.id); null } },
        onBlockFriend = { f ->
            friendCall {
                it.block(f.id)
                "${f.name} is blocked: they can't find you or invite you"
            }
        },
    )
}

/** What's typed as a friend code: letters and numbers, "K7Q2-MX8P". */
fun formatFriendCode(text: String): String {
    val clean = text.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return if (clean.length > 4) clean.take(4) + "-" + clean.drop(4) else clean
}

/** A friend call that failed, in words. */
fun explainFriendTrouble(e: OnlineError): String = when {
    e is OnlineError.Refused && e.code == "P0002" -> "No player has that code. Check it with your friend."
    e is OnlineError.Refused && e.code == "22023" -> "That's your own code: give it to a friend instead."
    e is OnlineError.Refused && e.code == "53400" -> e.message ?: "Too many friends and requests."
    else -> explainRoomTrouble(e)
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
    /** This phone has internet: rooms are made online (friends anywhere); without, nearby only. */
    internet: Boolean = true,
    onHostOnline: () -> Unit = {},
    /** Rooms online (the server's list): open ones join with a tap, locked ones with the code. */
    online: List<OnlineChoice> = emptyList(),
    onJoinOnline: (Int) -> Unit = {},
    /** Why the list couldn't be had (no profile service, say), or null. */
    onlineNote: String? = null,
    /** Friends (with internet), or null. */
    friends: FriendsUi? = null,
    friendCode: String = "",
    onFriendCode: (String) -> Unit = {},
    onAddFriend: () -> Unit = {},
    onShareCode: () -> Unit = {},
    onJoinInvite: (com.kofarcade.core.online.Invite) -> Unit = {},
    onDismissInvite: (com.kofarcade.core.online.Invite) -> Unit = {},
    onAnswerFriend: (com.kofarcade.core.online.Friend, Boolean) -> Unit = { _, _ -> },
    onJoinFriend: (com.kofarcade.core.online.Friend) -> Unit = {},
    onRemoveFriend: (com.kofarcade.core.online.Friend) -> Unit = {},
    onBlockFriend: (com.kofarcade.core.online.Friend) -> Unit = {},
) {
    ArcadeScreen(
        title = "Online",
        kicker = if (internet) "Play together, anywhere" else "Nearby play",
        onBack = onBack,
        // Making a room is the page's main action: pinned at the bottom, in thumb reach.
        bottomBar = {
            GoldButton(if (internet) "Create a room" else "Create nearby room", if (internet) onHostOnline else onHost, Modifier.fillMaxWidth(), play = true)
        },
    ) {
        status?.let {
            StatusPanel(it, onStatusAction)
            Spacer(Modifier.height(8.dp))
        }
        friends?.let { InviteCards(it.invites, busy, onJoinInvite, onDismissInvite) }
        // No internet: not an error, a feature.
        if (!internet) {
            NearbyPlayCard()
            Spacer(Modifier.height(8.dp))
        }
        // Rooms nearby: first, they're the fastest.
        SectionLabel(if (internet) "Nearby · fastest" else "Rooms nearby")
        if (rooms.isEmpty()) {
            EmptyState(
                "Looking for rooms…",
                "On this Wi-Fi or hotspot. A friend's room nearby shows up here.",
                icon = { RadarIcon(Modifier.size(28.dp)) },
            )
        } else {
            CardGrid(rooms.size) { i -> NearbyRoomCard(rooms[i], enabled = !busy && rooms[i].have) { onJoinRoom(i) } }
        }
        // Rooms online: anyone's, open ones first.
        if (internet) {
            SectionLabel("Rooms online")
            when {
                online.isNotEmpty() -> CardGrid(online.size) { i -> OnlineRoomCard(online[i], enabled = !busy && online[i].have) { onJoinOnline(i) } }
                onlineNote != null -> Text(onlineNote, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                else -> EmptyState(
                    "No rooms online right now",
                    "Create one: an open room shows here for anyone to join; a locked one too, but only with its code.",
                    icon = { RadarIcon(Modifier.size(28.dp)) },
                )
            }
        }
        if (friends != null) {
            FriendsSection(friends, busy, friendCode, onFriendCode, onAddFriend, onShareCode, onAnswerFriend, onJoinFriend, onRemoveFriend, onBlockFriend)
        }
        // Join with a code: an online room's (friends anywhere) or a nearby room's key.
        SectionLabel("Join with a code")
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                ArcadeField(key, onKey, "Room code", Modifier.weight(1f), placeholder = "K7Q-2MX", keyboardType = KeyboardType.Ascii, onDone = { if (!busy && key.isNotBlank()) onJoinKey() })
                Spacer(Modifier.width(12.dp))
                GoldButton("Join", onJoinKey, enabled = !busy && key.isNotBlank())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (internet) "The code your friend sees in their room. Works from anywhere." else "The key your friend sees in their room (same Wi-Fi or hotspot).",
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }
        // With internet, a nearby room is still there: no data used.
        if (internet) {
            SectionLabel("Saving data?")
            Panel {
                Text("Create a nearby room: friends on the same Wi-Fi or hotspot only. Zero lag, no data used.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                GhostButton("Create nearby room", onHost, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        // How it works.
        SectionLabel("How it works")
        StepsStrip()
        Spacer(Modifier.height(16.dp))
        Text(
            "Each phone needs the game (and the Neo Geo BIOS); most games take 2 players, Cadillacs and Dinosaurs 3, " +
                "Mystic Warriors and Sunset Riders 4. Others watch, and take a seat when one opens.",
            color = Palette.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** No internet: nearby play, as a feature (gold), not an error. */
@Composable
private fun NearbyPlayCard() {
    Panel(highlight = true) {
        Kicker("Nearby play")
        Spacer(Modifier.height(4.dp))
        Text("No internet needed", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Spacer(Modifier.height(4.dp))
        Text("Play with friends on the same Wi-Fi or hotspot. Zero lag, no data used.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** An online room's trouble in plain words: no internet is about rooms here, not the profile. */
private fun explainRoomTrouble(e: OnlineError): String = when (e) {
    is OnlineError.Offline -> "No internet: online rooms need it. Nearby rooms work without (same Wi-Fi or hotspot)."
    else -> explainOnline(e)
}

/** Whether this phone has the internet now (a validated network), kept up to date. */
@Composable
private fun rememberInternet(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(android.net.ConnectivityManager::class.java) }
    fun now(): Boolean = cm?.getNetworkCapabilities(cm.activeNetwork)?.let {
        it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
    var internet by remember { mutableStateOf(now()) }
    DisposableEffect(cm) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                main.post { internet = now() }
            }

            override fun onLost(network: android.net.Network) {
                main.post { internet = now() }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return internet
}

/** Rings around a dot: looking for rooms on the network. */
@Composable
private fun RadarIcon(modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        drawCircle(Palette.gold, size.minDimension * 0.1f, c)
        drawCircle(Palette.gold.copy(alpha = 0.7f), size.minDimension * 0.28f, c, style = Stroke(size.minDimension * 0.07f))
        drawCircle(Palette.gold.copy(alpha = 0.35f), size.minDimension * 0.46f, c, style = Stroke(size.minDimension * 0.07f))
    }
}

/** Host: the games on this phone, as on the home screen. */
@Composable
fun PickGameScreen(
    ready: List<Pair<Game, String>>,
    onPick: (Pair<Game, String>) -> Unit,
    onLibrary: () -> Unit,
    onBack: () -> Unit,
    online: Boolean = false,
    /** The room is being made (online: a few seconds): said, so taps don't look dead. */
    opening: Boolean = false,
    /** Online: the room's made open (listed, anyone joins) or locked (only with its code). */
    open: Boolean = true,
    onOpen: (Boolean) -> Unit = {},
) {
    ArcadeScreen(title = if (online) "Create a room" else "Create nearby room", kicker = "Pick the game", onBack = onBack) {
        if (online) {
            SectionLabel("Who can join")
            // Both cards as tall as the taller one (one's text may take two lines).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                WhoCanJoin("Open", "Anyone: it's listed, one tap to join", open, Modifier.weight(1f), enabled = !opening) { onOpen(true) }
                WhoCanJoin("Locked", "Listed, but only with its code", !open, Modifier.weight(1f), locked = true, enabled = !opening) { onOpen(false) }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (opening) {
            StatusPanel(MpStatus(if (online) "Creating the room…" else "Opening the room…", bad = false)) {}
            Spacer(Modifier.height(12.dp))
        }
        if (ready.isEmpty()) {
            EmptyState(
                "No games to host yet",
                "Add a game to this phone first (Neo Geo games also need the BIOS). Friends need the same game to join.",
                action = "Open the library" to onLibrary,
            )
        } else {
            // Wide picture cards: one a row in portrait, two or three side by side in landscape.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth > 820.dp) 3 else if (maxWidth > 560.dp) 2 else 1
                val gap = 12.dp
                val cardWidth = (maxWidth - gap * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    ready.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { pick -> HostCard(pick.first, pick.second, Modifier.width(cardWidth)) { onPick(pick) } }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A game to host: its picture filling a wide card, how many can play, and a gold Host button. */
@Composable
private fun HostCard(game: Game, set: String, modifier: Modifier, onHost: () -> Unit) {
    WideGameCard(
        game,
        set,
        game.title,
        modifier.aspectRatio(16f / 9f),
        onClick = onHost,
        tag = { PictureTag("${game.players} players", Palette.green) },
        info = { Text(game.subtitle ?: game.series, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1) },
        action = { GoldButton("Host", onHost, play = true, compact = true) },
    )
}

/** One of the two ways a room can be: picked (gold) or not. */
@Composable
private fun WhoCanJoin(title: String, text: String, picked: Boolean, modifier: Modifier, locked: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (picked) Palette.gold.copy(alpha = 0.14f) else Palette.panel)
            .border(2.dp, if (picked) Palette.gold else Palette.line, shape)
            .selectable(selected = picked, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LockIcon(Modifier.size(16.dp), open = !locked, color = if (picked) Palette.gold else Palette.muted)
            Spacer(Modifier.width(6.dp))
            Text(title, color = if (picked) Palette.gold else Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        }
        Spacer(Modifier.height(4.dp))
        Text(text, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** A locked room from the list: its code, from whoever made it. */
@Composable
private fun LockedRoomDialog(hostName: String, title: String, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val ready = code.count { it.isLetterOrDigit() } == 6
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        SheetFrame(360) {
            Kicker("Locked room")
            Text("$hostName's $title", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            Spacer(Modifier.height(6.dp))
            Text("Type the code $hostName gives you: they see it in their room.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            ArcadeField(code, { code = formatKeyInput(it) }, "Room code", placeholder = "K7Q-2MX", keyboardType = KeyboardType.Ascii, onDone = { if (ready) onJoin(code) })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Cancel", onDismiss, Modifier.weight(1f))
                GoldButton("Join", { onJoin(code) }, Modifier.weight(1f), enabled = ready)
            }
        }
    }
}

/** How often the Online screen asks for the rooms online (and again after a failure). */
private const val LIST_EVERY_MS = 8_000L
// This phone says it's online this often while the Online screen is up (friends see it for 2 minutes).
private const val PRESENCE_EVERY_MS = 60_000L
private const val LIST_RETRY_MS = 20_000L

/** [count] cards, one a row on a phone, two or three side by side on a tablet or a phone held sideways. */
@Composable
private fun CardGrid(count: Int, card: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 1100.dp) 3 else if (maxWidth > 700.dp) 2 else 1
        val gap = 12.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0 until count).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { i -> Box(Modifier.weight(1f)) { card(i) } }
                    // A last row with fewer cards keeps them the same width.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
