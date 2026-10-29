package com.kofarcade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.Art
import com.kofarcade.core.Game
import kotlinx.coroutines.delay

// The multiplayer look, shared by the Multiplayer screen and the game
// screen: who's in a room and where, its key, and what's happening, in the
// app's own style rather than Android's grey toasts.

/**
 * A phone in a room, as the screens show it. [seat] -1: watching. [away]:
 * it dropped out and its seat is kept for it until [holdEnds] (wall clock, ms).
 */
data class RoomPerson(
    val name: String,
    val seat: Int,
    val you: Boolean = false,
    val host: Boolean = false,
    val away: Boolean = false,
    val holdEnds: Long = 0L,
)

/** What the game screen shows of its room. */
data class RoomUi(
    val key: String,
    val maxPlayers: Int,
    val people: List<RoomPerson>,
    /** This phone watches. */
    val watching: Boolean,
    /** This phone runs the room: it can free a seat held for someone who dropped out. */
    val canFree: Boolean = false,
    /** An internet room: [key] is its code, for friends anywhere. */
    val online: Boolean = false,
) {
    val players: List<RoomPerson> get() = people.filter { it.seat >= 0 }.sortedBy { it.seat }
    val watchers: List<RoomPerson> get() = people.filter { it.seat < 0 }
    val freeSeats: Int get() = maxPlayers - players.size
}

/** Something to announce over the game: "Rafi joined", or big: "You're player 2!". */
data class Announcement(val text: String, val big: Boolean = false, val id: Long = System.nanoTime())

/**
 * A room key as it's typed: capitals, a dash every three ("K7Q-2M"); an
 * address ("192.168.43.1") stays as it is.
 */
fun formatKeyInput(raw: String): String {
    if (raw.any { it == '.' }) return raw.filter { it.isDigit() || it == '.' }.take(15)
    val chars = raw.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return chars.chunked(3).joinToString("-")
}

// ----- In the game -----

/**
 * The room at a glance, top right of the game: its key, a dot per seat
 * (gold: taken), and how many watch. WATCHING in front while this phone
 * watches. Tap for the room sheet.
 */
@Composable
fun RoomPill(ui: RoomUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(start = if (ui.watching) 4.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (ui.watching) {
                Text(
                    "WATCHING",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.red).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (ui.key.isNotEmpty()) {
                Text(ui.key, color = Palette.gold, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Spacer(Modifier.width(8.dp))
            }
            SeatDots(ui.maxPlayers, ui.players.filter { !it.away }.map { it.seat }.toSet(), held = ui.players.filter { it.away }.map { it.seat }.toSet())
            if (ui.watchers.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                EyeIcon(Modifier.size(12.dp), color = Palette.muted)
                Spacer(Modifier.width(3.dp))
                Text("${ui.watchers.size}", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Watching with a seat free: say how to take it, so nobody misses it.
        if (ui.watching && ui.freeSeats > 0) {
            Spacer(Modifier.height(6.dp))
            val pulse by rememberInfiniteTransition(label = "seat").animateFloat(
                0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "seat",
            )
            Text(
                "SEAT FREE · PRESS COIN",
                color = OnGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.alpha(pulse).clip(RoundedCornerShape(50)).background(Palette.gold)
                    .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** A dot per seat: gold when someone sits there, gold ring when it's [held] for someone coming back. */
@Composable
fun SeatDots(seats: Int, taken: Set<Int>, size: Int = 9, held: Set<Int> = emptySet()) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (s in 0 until seats) {
            Box(
                Modifier.size(size.dp).clip(CircleShape)
                    .background(
                        when (s) {
                            in taken -> Palette.gold
                            in held -> Palette.gold.copy(alpha = 0.25f)
                            else -> Color.Transparent
                        },
                    )
                    .border(1.5.dp, if (s in taken || s in held) Palette.gold else Palette.muted, CircleShape),
            )
        }
    }
}

/** News over the game: slides in at the top, goes by itself. Big for what's about you. */
@Composable
fun AnnouncementBanner(announcement: Announcement?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = announcement != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val a = announcement ?: return@AnimatedVisibility
        if (a.big) {
            Text(
                a.text.uppercase(),
                color = OnGold,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.gold).padding(horizontal = 26.dp, vertical = 12.dp),
            )
        } else {
            Text(
                a.text,
                color = Palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(50))
                    .background(Palette.panel.copy(alpha = 0.95f)).border(1.dp, Palette.line, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The room: its key to share, who sits where (and the free seats), who
 * watches, and what you can do. [first]: just opened, by this phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomSheetContent(
    ui: RoomUi,
    first: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSeat: () -> Unit,
    onWatch: () -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
    onFree: (Int) -> Unit = {},
) {
    // Wide (landscape): key and buttons on the left, who's in on the right, so it fits without scrolling.
    BoxWithConstraints {
        if (maxWidth > 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    RoomHeader(ui, first, onCopy, onShare)
                    RoomActions(ui, first, onSeat, onWatch, onLeave, onClose)
                }
                Column(Modifier.weight(1f)) { RoomPeople(ui, onFree) }
            }
        } else {
            Column {
                RoomHeader(ui, first, onCopy, onShare)
                RoomPeople(ui, onFree)
                RoomActions(ui, first, onSeat, onWatch, onLeave, onClose)
            }
        }
    }
}

@Composable
private fun RoomHeader(ui: RoomUi, first: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    Kicker(if (first) "Room open" else "Room")
    Text(
        if (first) "Invite your friends" else "Who's playing",
        color = Palette.text,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
    )
    Spacer(Modifier.height(12.dp))
    if (ui.key.isEmpty()) {
        Panel {
            Text("This phone isn't on Wi-Fi or a hotspot", color = Palette.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Turn on its hotspot (or join Wi-Fi), then open the room again so friends can reach it.", color = Palette.muted, fontSize = 13.sp)
        }
    } else {
        Panel(highlight = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker(if (ui.online) "Room code" else "Room key", Palette.muted)
                    Text(ui.key, color = Palette.gold, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text(
                        if (ui.online) "Friends anywhere: Multiplayer → Play over the internet → type this code, or scan it." else "Friends: Multiplayer → type this key. Same Wi-Fi or hotspot.",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }
                if (ui.online) {
                    Spacer(Modifier.width(12.dp))
                    // Scanned with a phone's camera: opens the room in the app (or the website's page to get it).
                    QrCode(joinLink(ui.key), Modifier.size(96.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Copy", onCopy, Modifier.weight(1f))
                GoldButton("Share", onShare, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomPeople(ui: RoomUi, onFree: (Int) -> Unit) {
    SectionLabel("Players")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seat in 0 until ui.maxPlayers) {
            SeatRow(seat, ui.players.find { it.seat == seat }, ui.canFree, onFree)
        }
    }
    SectionLabel("Watching")
    if (ui.watchers.isEmpty()) {
        Text("Nobody yet. Anyone who joins watches first.", color = Palette.muted, fontSize = 13.sp)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in ui.watchers) Tag(w.name + if (w.you) " (you)" else "", if (w.you) Palette.gold else Palette.muted)
        }
    }
}

@Composable
private fun RoomActions(ui: RoomUi, first: Boolean, onSeat: () -> Unit, onWatch: () -> Unit, onLeave: () -> Unit, onClose: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.watching) {
            GoldButton("Take a seat", onSeat, Modifier.fillMaxWidth(), play = true)
        } else {
            GoldButton(if (first) "Start playing" else "Back to the game", onClose, Modifier.fillMaxWidth(), play = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.watching) {
                GhostButton("Back to watching", onClose, Modifier.weight(1f))
            } else {
                GhostButton("Watch instead", onWatch, Modifier.weight(1f))
            }
            GhostButton("Leave", onLeave, Modifier.weight(1f), danger = true)
        }
    }
}

@Composable
private fun SeatRow(seat: Int, who: RoomPerson?, canFree: Boolean, onFree: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (who?.you == true) Palette.gold.copy(alpha = 0.1f) else Palette.bg)
            .border(1.dp, if (who?.you == true) Palette.gold.copy(alpha = 0.6f) else Palette.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (who != null) Palette.gold else Color.Transparent)
                .border(1.dp, if (who != null) Palette.gold else Palette.line, RoundedCornerShape(10.dp)),
        ) {
            Text("P${seat + 1}", color = if (who != null) OnGold else Palette.muted, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (who == null) {
            Column(Modifier.weight(1f)) {
                Text("Free seat", color = Palette.muted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("A watcher takes it with COIN or START", color = Palette.muted, fontSize = 11.sp)
            }
        } else if (who.away) {
            // Dropped out: the seat waits for them, counting down.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(who.holdEnds) {
                while (now < who.holdEnds) {
                    delay(1_000)
                    now = System.currentTimeMillis()
                }
            }
            val left = ((who.holdEnds - now + 999) / 1000).coerceAtLeast(0)
            Column(Modifier.weight(1f)) {
                Text(who.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Reconnecting… seat kept %d:%02d".format(left / 60, left % 60), color = Palette.gold, fontSize = 11.sp)
            }
            if (canFree) {
                Spacer(Modifier.width(8.dp))
                GhostButton("Free seat", { onFree(seat) })
            }
        } else {
            Text(who.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (who.host) {
                Spacer(Modifier.width(6.dp))
                Tag("Host")
            }
            if (who.you) {
                Spacer(Modifier.width(6.dp))
                Tag("You", Palette.gold, filled = true)
            }
        }
    }
}

// ----- The Multiplayer screen -----

/** How a room works, in three steps with numbers, instead of a paragraph. */
@Composable
fun StepsStrip() {
    BoxWithConstraints {
        val steps = listOf(
            "Open a room" to "Pick the game. You get a key.",
            "Friends join" to "They type the key and watch.",
            "COIN + START" to "Takes a free seat. Play!",
        )
        val row = maxWidth > 420.dp
        val items: @Composable (Modifier) -> Unit = { m ->
            steps.forEachIndexed { i, (title, sub) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = m) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Palette.gold),
                    ) { Text("${i + 1}", color = OnGold, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(sub, color = Palette.muted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (row) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(Modifier.weight(1f)) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(Modifier) }
        }
    }
}

/**
 * A wide card on the Multiplayer screens: the game's gameplay picture filling it
 * (grey if this phone hasn't the game), the [title] and [info] over a shade at the bottom, [action]
 * (a gold pill) beside them and an optional [tag] at the top.
 */
@Composable
fun WideGameCard(
    game: Game?,
    set: String?,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Greyed out: this phone hasn't the game. */
    grey: Boolean = false,
    onClick: () -> Unit,
    tag: (@Composable () -> Unit)? = null,
    info: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    val outlined = lit(source)
    Box(
        modifier.pressScale(source).fillMaxWidth().clip(shape).background(Palette.panel)
            .border(if (outlined) 2.dp else 1.dp, if (outlined) Palette.gold else Color.White.copy(alpha = 0.08f), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
    ) {
        if (game != null) {
            // Gameplay, not the title screen: the card names the game itself.
            ArtImage(game, set, Art.SNAP, Modifier.matchParentSize(), contentScale = ContentScale.Crop, label = false, grey = grey)
        } else {
            // A game this app doesn't know: its name on a tile, like a picture still loading.
            Box(Modifier.matchParentSize().background(Palette.bg).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(title, color = Palette.muted, fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.65f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f))))
        if (tag != null) Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { tag() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                info()
            }
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

/** A room found nearby: the game's picture, its key and host, the seats, and what tapping does. */
@Composable
fun NearbyRoomCard(room: HostChoice, enabled: Boolean, onClick: () -> Unit) {
    val full = room.players >= room.maxPlayers
    WideGameCard(
        room.game,
        room.set,
        room.title,
        Modifier.height(150.dp),
        enabled = enabled,
        grey = !room.have,
        onClick = onClick,
        tag = { if (!room.have) PictureTag("Need the game", Palette.red) else PictureTag(if (full) "Full: watch" else "Open seat", if (full) Palette.gold else Palette.green) },
        info = {
            Text(room.hostName + if (room.key.isNotEmpty()) " · ${room.key}" else "", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeatDots(room.maxPlayers, (0 until room.players.coerceAtMost(room.maxPlayers)).toSet(), size = 8)
                if (room.watchers > 0) {
                    Spacer(Modifier.width(8.dp))
                    EyeIcon(Modifier.size(11.dp), color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.width(3.dp))
                    Text("${room.watchers}", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                }
            }
        },
        action = { if (room.have) GoldButton(if (full) "Watch" else "Join", onClick, enabled = enabled, compact = true) },
    )
}

/** A message on the Multiplayer screen, with what to do about it. */
data class MpStatus(val text: String, val action: String? = null, val busy: Boolean = false, val bad: Boolean = false)

@Composable
fun StatusPanel(status: MpStatus, onAction: () -> Unit) {
    Panel(highlight = !status.bad) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (status.bad) Palette.red else Palette.gold)
            Spacer(Modifier.width(10.dp))
            Text(status.text, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (status.action != null) {
            Spacer(Modifier.height(12.dp))
            GoldButton(status.action, onAction)
        }
    }
}
