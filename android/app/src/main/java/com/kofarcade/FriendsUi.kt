package com.kofarcade

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

/** "just now", "5 min ago", "3 h ago", "2 days ago": how long since [ms] (wall clock). */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        min < 2 * 24 * 60 -> "yesterday"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** What a friend's doing, in a few words. */
fun friendStatus(f: Friend, now: Long = System.currentTimeMillis()): String = when {
    f.status == Friend.Status.INCOMING -> "Wants to be friends"
    f.status == Friend.Status.OUTGOING -> "Asked: waiting for them"
    !f.online -> f.lastSeenMs?.let { "Last seen ${ago(it, now)}" } ?: "Offline"
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
    onChat: (Friend) -> Unit = {},
    onChallenge: (Friend) -> Unit = {},
) {
    val online = ui.friends.count { it.status == Friend.Status.FRIEND && it.online }
    SectionLabel(if (online > 0) "Friends · $online online" else "Friends")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in ui.friends) FriendRow(f, busy, onAnswer, onJoin, onRemove, onBlock, onChat, onChallenge)
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
                        Text(if (inv.challenge) "${inv.name} challenges you" else "${inv.name} invites you", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onChat: (Friend) -> Unit,
    onChallenge: (Friend) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val friend = f.status == Friend.Status.FRIEND
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
            .border(1.dp, if (f.unread > 0) Palette.gold.copy(alpha = 0.6f) else Palette.line, RoundedCornerShape(14.dp))
            // A friend's row opens your chat with them.
            .then(if (friend) Modifier.clickable { onChat(f) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (f.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    CountBadge(f.unread)
                }
            }
            // New messages first; else what they're doing.
            val news = f.unread > 0 && f.lastMessage != null
            Text(
                if (news) f.lastMessage!! else friendStatus(f),
                color = if (news || (f.online && f.playing != null)) Palette.gold else Palette.muted,
                fontWeight = if (news) FontWeight.Bold else FontWeight.Normal,
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
                // In a room: join them. Online and free: challenge them (a room made for them, invited at once).
                if (f.room != null) {
                    GoldButton(if (f.roomSpace) "Join" else "Full", { onJoin(f) }, compact = true, enabled = !busy && f.roomSpace)
                } else if (f.online) {
                    GoldButton("Challenge", { onChallenge(f) }, compact = true, enabled = !busy)
                }
                androidx.compose.foundation.layout.Box {
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }
                            .semantics { contentDescription = "More for ${f.name}"; role = Role.Button },
                    ) {
                        Text("⋮", color = Palette.muted, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Chat") }, onClick = { menu = false; onChat(f) })
                        DropdownMenuItem(text = { Text("Challenge") }, onClick = { menu = false; onChallenge(f) })
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


/** A small round gold count: new messages, things waiting. */
@Composable
fun CountBadge(n: Int, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Palette.gold) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(18.dp).clip(CircleShape).background(color),
    ) {
        Text(
            if (n > 9) "9+" else "$n",
            color = if (color == Palette.gold) OnGold else androidx.compose.ui.graphics.Color.White,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
        )
    }
}

/** Quick lines for a friend's chat: a tap sends one. */
val FRIEND_QUICK = listOf("Play now?", "Rematch?", "GG", "On my way", "5 min", "Later")

/**
 * The chat with a friend (from data, so it can be previewed): their name and
 * what they're doing at the top with Challenge (or Join, in a room), the
 * messages (yours on the right, gold), quick lines, and a line to type.
 */
@Composable
fun FriendChatScreen(
    friend: Friend,
    messages: List<com.kofarcade.core.online.FriendMessage>,
    typed: String,
    onType: (String) -> Unit,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    onChallenge: () -> Unit,
    onJoin: () -> Unit,
    busy: Boolean = false,
    note: String? = null,
    now: Long = System.currentTimeMillis(),
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Palette.bg).systemBarsPadding().imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Palette.panel)
                    .border(1.dp, Palette.line, CircleShape).clickable(onClick = onBack)
                    .semantics { contentDescription = "Back"; role = Role.Button },
            ) { BackIcon(Modifier.size(14.dp)) }
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Box {
                ProfileIcon(friend.icon, 38.dp)
                if (friend.online) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(Palette.green).border(2.dp, Palette.bg, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.name, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (friend.online) friendStatus(friend, now) else friendStatus(friend, now),
                    color = if (friend.online) Palette.green else Palette.muted,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (friend.room != null) {
                GoldButton(if (friend.roomSpace) "Join" else "Full", onJoin, compact = true, enabled = !busy && friend.roomSpace)
            } else {
                GoldButton("Challenge", onChallenge, compact = true, enabled = !busy, play = true)
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                ) {
                    Text("Say hi to ${friend.name}", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Messages stay 30 days. Tap Challenge to make a room for you two: ${friend.name} gets the invite at once.",
                        color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(messages.size, key = { messages[it].id }) { i ->
                    val m = messages[i]
                    val lastMine = m.mine && messages.subList(i + 1, messages.size).none { it.mine }
                    MessageBubble(m, showSeen = lastMine)
                }
            }
        }
        note?.let {
            Text(it, color = Palette.red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            for (q in FRIEND_QUICK) {
                Text(
                    q, color = Palette.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.gold.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .clickable(enabled = !busy) { onSend(q) }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            ArcadeField(
                typed, onType, null, Modifier.weight(1f),
                placeholder = "Message ${friend.name}…",
                onDone = { if (typed.isNotBlank() && !busy) onSend(typed) },
            )
            Spacer(Modifier.width(10.dp))
            GoldButton("Send", { onSend(typed) }, enabled = typed.isNotBlank() && !busy)
        }
    }
}

@Composable
private fun MessageBubble(m: com.kofarcade.core.online.FriendMessage, showSeen: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        Text(
            m.body,
            color = if (m.mine) OnGold else Palette.text,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth(0.8f).wrapContentWidth(if (m.mine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (m.mine) 16.dp else 4.dp, bottomEnd = if (m.mine) 4.dp else 16.dp))
                .background(if (m.mine) Palette.gold else Palette.panel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (showSeen) {
            Text(if (m.seen) "Seen" else "Sent", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
        }
    }
}

/**
 * Something new from a friend, over any page: an invite or challenge (Join),
 * else a message (Reply). [onOpen] joins or opens the chat; [onDismiss] hides it.
 */
@Composable
fun FriendNewsBanner(news: com.kofarcade.core.online.FriendNews, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val inv = news.invite
    val msg = news.message
    if (inv == null && msg == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.panel)
            .border(1.dp, Palette.gold.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen).padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        ProfileIcon(inv?.icon ?: msg!!.icon, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (inv != null) {
                Text(if (inv.challenge) "${inv.name} challenges you" else "${inv.name} invites you", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(gameForSet(inv.set)?.fullTitle ?: inv.set, color = Palette.gold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(msg!!.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(msg.body, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        GoldButton(if (inv != null) "Join" else "Reply", onOpen, compact = true)
        CloseX(onDismiss, "Hide")
    }
}
