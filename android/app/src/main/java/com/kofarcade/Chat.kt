package com.kofarcade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.net.Room

/** A line of room chat as the game screen shows it (filtered, if the filter is on). */
data class ChatShown(
    /** Counts up, so a line is told apart from the same words said again. */
    val key: Long,
    val from: Int,
    val name: String,
    val device: String,
    val text: String,
    val quick: Boolean,
    /** Said on this phone. */
    val mine: Boolean,
    /** When it arrived (System.currentTimeMillis on this phone). */
    val at: Long,
)

/** Quick chat: one tap, nothing typed. */
val QUICK_CHAT = listOf("GG", "Rematch?", "Nice!", "Well played", "One more?", "Wait a sec", "Ready", "Thanks")

/** How long a line stays over the game as a bubble. */
const val BUBBLE_MS = 5_000L

/** A drawn speech bubble. */
@Composable
fun ChatIcon(modifier: Modifier = Modifier, color: Color = Palette.text) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f)
        drawRoundRect(color, Offset(w * 0.06f, h * 0.1f), Size(w * 0.88f, h * 0.62f), CornerRadius(w * 0.2f), style = stroke)
        val tail = Path().apply {
            moveTo(w * 0.28f, h * 0.7f)
            lineTo(w * 0.22f, h * 0.94f)
            lineTo(w * 0.48f, h * 0.72f)
        }
        drawPath(tail, color, style = stroke)
    }
}

/** The chat button over the game, by the room pill; a red count of lines not read yet. */
@Composable
fun ChatButton(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "Chat"; role = Role.Button },
        ) {
            ChatIcon(Modifier.size(15.dp))
        }
        if (unread > 0) {
            Text(
                if (unread > 9) "9+" else "$unread",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 18.dp).clip(CircleShape).background(Palette.red)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * The last few lines over the game for [BUBBLE_MS] each: small, one line
 * each, at the top left under the pause button (where no control is), and
 * never in the way of a touch.
 */
@Composable
fun ChatBubbles(lines: List<ChatShown>, modifier: Modifier = Modifier, max: Int = 3, now: () -> Long = System::currentTimeMillis) {
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(lines.lastOrNull()?.key) {
        while (true) {
            clock = now()
            if (lines.none { clock - it.at < BUBBLE_MS }) break
            kotlinx.coroutines.delay(250)
        }
    }
    val shown = lines.takeLast(max)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier.widthIn(max = 260.dp)) {
        for (l in shown) {
            AnimatedVisibility(clock - l.at < BUBBLE_MS, enter = fadeIn(), exit = fadeOut()) {
                Bubble(l)
            }
        }
    }
}

@Composable
private fun Bubble(l: ChatShown) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            if (l.mine) "You" else l.name,
            color = if (l.mine) Palette.muted else nameColor(l.name),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 90.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            l.text,
            color = if (l.quick) Palette.gold else Color.White,
            fontSize = 12.sp,
            fontWeight = if (l.quick) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A name's own colour, the same on every phone. */
fun nameColor(name: String): Color = Color.hsv(nameHue(name), 0.45f, 1f)

/**
 * The chat panel down the right side (the game goes on behind): what's been
 * said, quick chat, and a line to type. Tap someone's line to mute or
 * report them.
 */
@Composable
fun ChatPanel(
    lines: List<ChatShown>,
    muted: Set<String>,
    /** An internet room: players can be reported. */
    canReport: Boolean,
    onSay: (String, Boolean) -> Unit,
    onMute: (ChatShown, Boolean) -> Unit,
    onReport: (ChatShown, com.kofarcade.core.online.ReportReason) -> Unit,
    onClose: () -> Unit,
    /** Internet rooms: ask them to be friends, or block them. */
    onAddFriend: ((ChatShown) -> Unit)? = null,
    onBlock: ((ChatShown) -> Unit)? = null,
) = TextScaleCap {
    var typed by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    fun send() {
        val text = Room.cleanChat(typed) ?: return
        onSay(text, false)
        typed = ""
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(if (maxWidth < 760.dp) 360.dp else 400.dp)
                .slantPanel(left = 40.dp, right = 0.dp, alpha = 0.94f)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .imePadding()
                .padding(start = 48.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chat", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose)
                        .semantics { contentDescription = "Close chat"; role = Role.Button },
                ) {
                    Text("✕", color = Palette.muted, fontSize = 18.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    Text(
                        "Nothing said yet. Tap a quick line below, or type one.",
                        color = Palette.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                    )
                }
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                    items(lines, key = { it.key }) { l ->
                        val isMuted = l.device.isNotEmpty() && l.device in muted
                        ChatRow(l, open = picked == l.key, isMuted = isMuted, onTap = { picked = if (picked == l.key || l.mine) null else l.key })
                        if (picked == l.key && !l.mine) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).horizontalScroll(rememberScrollState()),
                            ) {
                                GhostButton(if (isMuted) "Unmute" else "Mute", { onMute(l, !isMuted); picked = null }, compact = true)
                                if (onAddFriend != null) GhostButton("Add friend", { onAddFriend(l); picked = null }, compact = true)
                                if (canReport) {
                                    // Why: picked from the server's reasons.
                                    androidx.compose.foundation.layout.Box {
                                        var why by remember { mutableStateOf(false) }
                                        GhostButton("Report", { why = true }, compact = true, danger = true)
                                        androidx.compose.material3.DropdownMenu(expanded = why, onDismissRequest = { why = false }) {
                                            for (r in com.kofarcade.core.online.ReportReason.entries) {
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text(r.label) },
                                                    onClick = {
                                                        why = false
                                                        picked = null
                                                        onReport(l, r)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (onBlock != null) GhostButton("Block", { onBlock(l); picked = null }, compact = true, danger = true)
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            ) {
                for (q in QUICK_CHAT) {
                    Text(
                        q,
                        color = Palette.gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, Palette.gold.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .clickable { onSay(q, true) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.bg)
                        .border(1.dp, Palette.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    if (typed.isEmpty()) Text("Say something…", color = Palette.muted.copy(alpha = 0.6f), fontSize = 15.sp)
                    BasicTextField(
                        value = typed,
                        onValueChange = { typed = it.take(Room.CHAT_MAX) },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Palette.gold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                GoldButton("Send", ::send, compact = true, enabled = typed.isNotBlank())
            }
        }
    }
}

@Composable
private fun ChatRow(l: ChatShown, open: Boolean, isMuted: Boolean, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (open) Palette.line.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(enabled = !l.mine, onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (isMuted) 0.45f else 1f },
    ) {
        Text(
            if (l.mine) "You" else l.name,
            color = if (l.mine) Palette.muted else nameColor(l.name),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (isMuted) "Muted" else l.text,
            color = when {
                isMuted -> Palette.muted
                l.quick -> Palette.gold
                else -> Palette.text
            },
            fontSize = 14.sp,
            fontWeight = if (l.quick) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isMuted) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

/** A drawn microphone, crossed out while it's off. */
@Composable
fun MicIcon(modifier: Modifier = Modifier, on: Boolean, color: Color = Palette.text) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.1f)
        drawRoundRect(color, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.52f), CornerRadius(w * 0.16f))
        val cup = Path().apply {
            moveTo(w * 0.18f, h * 0.42f)
            cubicTo(w * 0.18f, h * 0.82f, w * 0.82f, h * 0.82f, w * 0.82f, h * 0.42f)
        }
        drawPath(cup, color, style = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.76f), Offset(w * 0.5f, h * 0.94f), w * 0.1f)
        if (!on) drawLine(Palette.red, Offset(w * 0.12f, h * 0.1f), Offset(w * 0.88f, h * 0.9f), w * 0.12f)
    }
}

/**
 * The mic button by the chat button: off (crossed) when you join; a tap
 * turns it on. In push-to-talk, once on, hold it to talk.
 */
@Composable
fun MicButton(on: Boolean, talking: Boolean, pushToTalk: Boolean, onToggle: () -> Unit, onTalk: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val live = on && (!pushToTalk || talking)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (live) Palette.green.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f))
            .border(1.dp, if (on) Palette.green else Color.White.copy(alpha = 0.12f), CircleShape)
            .then(
                if (on && pushToTalk) {
                    // Hold to talk; a quick tap (no hold) turns the mic off.
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val start = System.currentTimeMillis()
                                onTalk(true)
                                tryAwaitRelease()
                                onTalk(false)
                                if (System.currentTimeMillis() - start < 200) onToggle()
                            },
                        )
                    }
                } else {
                    Modifier.clickable(onClick = onToggle)
                },
            )
            .semantics { contentDescription = if (on) "Mic on" else "Mic off"; role = Role.Button },
    ) {
        MicIcon(Modifier.size(15.dp), on = on, color = if (live) Color.White else Palette.text)
    }
}

/** Who's speaking now: small chips with a mic, under the chat bubbles' corner. */
@Composable
fun SpeakingChips(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        for (n in names.take(3)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.green.copy(alpha = 0.75f)).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                MicIcon(Modifier.size(11.dp), on = true, color = Color.White)
                Spacer(Modifier.width(4.dp))
                Text(n, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
    }
}
