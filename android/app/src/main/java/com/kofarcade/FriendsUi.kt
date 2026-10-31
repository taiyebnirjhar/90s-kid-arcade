package com.kofarcade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.gameForSet
import com.kofarcade.core.online.Friend
import com.kofarcade.core.online.Invite
import com.kofarcade.core.online.prettyFriendCode

/** What the Online screen shows of friends (null fields: not loaded yet). */
data class FriendsUi(
    /** Your friend code, to give to friends. */
    val myCode: String? = null,
    val friends: List<Friend> = emptyList(),
    val invites: List<Invite> = emptyList(),
    /** Why the list couldn't be had, or null. */
    val note: String? = null,
)

/** What a friend's doing, in a few words. */
fun friendStatus(f: Friend): String = when {
    f.status == Friend.Status.INCOMING -> "Wants to be friends"
    f.status == Friend.Status.OUTGOING -> "Asked: waiting for them"
    !f.online -> "Offline"
    f.playing != null -> {
        val title = gameForSet(f.playing!!)?.fullTitle ?: f.playing!!
        if (f.room != null) "Playing $title online" else "Playing $title"
    }
    else -> "Online"
}

/**
 * Friends on the Online screen: requests, then friends (online first) with
 * Join where they're in a room, and adding one by their code.
 */
@Composable
fun FriendsSection(
    ui: FriendsUi,
    busy: Boolean,
    friendCode: String,
    onFriendCode: (String) -> Unit,
    onAdd: () -> Unit,
    onShareCode: () -> Unit,
    onAnswer: (Friend, Boolean) -> Unit,
    onJoin: (Friend) -> Unit,
    onRemove: (Friend) -> Unit,
    onBlock: (Friend) -> Unit,
) {
    SectionLabel("Friends")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in ui.friends) FriendRow(f, busy, onAnswer, onJoin, onRemove, onBlock)
        if (ui.friends.isEmpty() && ui.note == null) {
            Text("No friends yet. Give a friend your code, or add theirs.", color = Palette.muted, fontSize = 13.sp)
        }
        ui.note?.let { Text(it, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp) }
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                ArcadeField(friendCode, onFriendCode, "Add a friend", Modifier.weight(1f), placeholder = "Their friend code", keyboardType = KeyboardType.Ascii, onDone = { if (!busy && friendCode.isNotBlank()) onAdd() })
                Spacer(Modifier.width(12.dp))
                GoldButton("Add", onAdd, enabled = !busy && friendCode.isNotBlank())
            }
            if (ui.myCode != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your code", color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(prettyFriendCode(ui.myCode), color = Palette.gold, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                    GhostButton("Share", onShareCode, compact = true)
                }
            }
        }
    }
}

/** Invites to friends' rooms: at the top of the Online screen, they don't wait. */
@Composable
fun InviteCards(invites: List<Invite>, busy: Boolean, onJoin: (Invite) -> Unit, onDismiss: (Invite) -> Unit) {
    if (invites.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        for (inv in invites) {
            Panel(highlight = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileIcon(inv.icon, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${inv.name} invites you", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(gameForSet(inv.set)?.fullTitle ?: inv.set, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    CloseX({ onDismiss(inv) }, "Turn down")
                    Spacer(Modifier.width(4.dp))
                    GoldButton("Join", { onJoin(inv) }, enabled = !busy, compact = true)
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    f: Friend,
    busy: Boolean,
    onAnswer: (Friend, Boolean) -> Unit,
    onJoin: (Friend) -> Unit,
    onRemove: (Friend) -> Unit,
    onBlock: (Friend) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        androidx.compose.foundation.layout.Box {
            ProfileIcon(f.icon, 36.dp)
            if (f.online) {
                androidx.compose.foundation.layout.Box(
                    Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(Palette.green).border(2.dp, Palette.panel, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                friendStatus(f),
                color = if (f.online && f.playing != null) Palette.gold else Palette.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (f.status) {
            Friend.Status.INCOMING -> {
                GhostButton("Decline", { onAnswer(f, false) }, compact = true, enabled = !busy)
                Spacer(Modifier.width(6.dp))
                GoldButton("Accept", { onAnswer(f, true) }, compact = true, enabled = !busy)
            }
            Friend.Status.OUTGOING -> GhostButton("Cancel", { onRemove(f) }, compact = true, enabled = !busy)
            Friend.Status.FRIEND -> {
                if (f.room != null) GoldButton(if (f.roomSpace) "Join" else "Full", { onJoin(f) }, compact = true, enabled = !busy && f.roomSpace)
                androidx.compose.foundation.layout.Box {
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }
                            .semantics { contentDescription = "More for ${f.name}"; role = Role.Button },
                    ) {
                        Text("⋮", color = Palette.muted, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove friend") }, onClick = { menu = false; onRemove(f) })
                        DropdownMenuItem(text = { Text("Block", color = Palette.red) }, onClick = { menu = false; onBlock(f) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseX(onClick: () -> Unit, label: String) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
    ) {
        Text("✕", color = Palette.muted, fontSize = 16.sp)
    }
}
