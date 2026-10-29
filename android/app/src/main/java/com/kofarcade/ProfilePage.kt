package com.kofarcade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.Game
import com.kofarcade.core.gameById
import com.kofarcade.core.online.NAME_MAX
import com.kofarcade.core.online.OnlineError
import com.kofarcade.core.online.PROFILE_ICONS
import com.kofarcade.core.online.Profile
import com.kofarcade.core.online.cleanName
import com.kofarcade.core.online.explainOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the profile stands with the server. */
sealed interface SyncState {
    data object Syncing : SyncState
    data object Synced : SyncState
    /** No internet: the phone's copy shows, and goes up later. */
    data class Offline(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

/** Everything the profile page shows. */
data class ProfileUi(
    /** The phone's copy (null: not made yet). */
    val profile: Profile?,
    val state: SyncState,
    /** Games played, most first. */
    val plays: List<Pair<Game, Int>>,
)

/**
 * Your profile (phase 1.3): signs in as a guest the first time it opens,
 * then shows the phone's copy at once and syncs it in the background.
 * Edits are kept on the phone until they're saved on the server.
 */
@Composable
fun ProfileDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val account = remember { Online.account(context) }
    var profile by remember { mutableStateOf(account.profile()) }
    var state by remember { mutableStateOf<SyncState>(SyncState.Syncing) }
    val plays = remember { account.plays() }
    val scope = rememberCoroutineScope()

    fun sync() {
        state = SyncState.Syncing
        scope.launch {
            state = try {
                profile = withContext(Dispatchers.IO) { account.sync(Online.defaultName()) }
                SyncState.Synced
            } catch (e: OnlineError.Offline) {
                SyncState.Offline(explainOnline(e))
            } catch (e: Exception) {
                SyncState.Failed(explainOnline(e))
            }
            profile = account.profile()
        }
    }

    LaunchedEffect(Unit) { sync() }
    BackHandler(onBack = onClose)

    ProfileScreen(
        ProfileUi(profile, state, plays.mapNotNull { (id, n) -> gameById(id)?.let { it to n } }.sortedByDescending { it.second }),
        onBack = onClose,
        onRetry = ::sync,
        onSave = { name, icon ->
            scope.launch {
                withContext(Dispatchers.IO) { account.edit(name, icon) }
                profile = account.profile()
                sync()
            }
        },
        onCopyCode = { code ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Friend code", code))
            Toast.makeText(context, "Friend code copied", Toast.LENGTH_SHORT).show()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    ui: ProfileUi,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSave: (name: String, icon: Int) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val p = ui.profile
    // The fields start from the profile, and again once a first sign-in makes it.
    var name by remember(p?.name) { mutableStateOf(p?.name.orEmpty()) }
    var icon by remember(p?.icon) { mutableIntStateOf(p?.icon ?: 0) }
    val clean = cleanName(name)
    val changed = clean.isNotEmpty() && (p == null || clean != p.name || icon != p.icon)

    ArcadeScreen(
        title = "Profile",
        kicker = "Online",
        onBack = onBack,
        bottomBar = if (changed) ({ GoldButton("Save", { onSave(clean, icon) }, Modifier.fillMaxWidth()) }) else null,
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            if (p == null && ui.state == SyncState.Syncing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Skeleton(Modifier.size(72.dp), CircleShape)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Skeleton(Modifier.width(160.dp).height(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Skeleton(Modifier.width(110.dp).height(14.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Signing in as a guest…", color = Palette.muted, fontSize = 14.sp)
                return@Column
            }
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileIcon(p.icon, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        val code = p.friendCode
                        if (code != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopyCode(code) },
                            ) {
                                Text("Friend code ", color = Palette.muted, fontSize = 13.sp)
                                Text(code.chunked(4).joinToString(" "), color = Palette.gold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            }
                        } else {
                            Text("Friend code: once it's saved online", color = Palette.muted, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            StatusLine(ui.state, p, onRetry)

            SectionLabel("Name")
            ArcadeField(name, { if (it.length <= NAME_MAX + 8) name = it }, label = "What others see in a room", placeholder = "Your name")
            if (name.isNotEmpty() && clean.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("A name, please", color = Palette.red, fontSize = 12.sp)
            }

            SectionLabel("Icon")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in 0 until PROFILE_ICONS) {
                    ProfileIcon(
                        i,
                        52.dp,
                        Modifier.clip(CircleShape).clickable { icon = i }
                            .semantics { contentDescription = "Icon ${i + 1}"; selected = i == icon; role = Role.RadioButton },
                        ring = if (i == icon) Palette.text else null,
                    )
                }
            }

            SectionLabel("Games played")
            if (ui.plays.isEmpty()) {
                Text("None yet: every game you start counts here.", color = Palette.muted, fontSize = 14.sp)
            } else {
                Panel {
                    ui.plays.forEachIndexed { k, (game, n) ->
                        if (k > 0) Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(game.fullTitle, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text(if (n == 1) "1 game" else "$n games", color = Palette.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "A guest profile, kept on this phone. Wins show here once public play comes.",
                color = Palette.muted.copy(alpha = 0.8f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun StatusLine(state: SyncState, profile: Profile?, onRetry: () -> Unit) {
    val (color, text) = when (state) {
        SyncState.Syncing -> Palette.muted to "Syncing…"
        SyncState.Synced -> Palette.green to "Saved online"
        is SyncState.Offline -> Palette.gold to if (profile == null) {
            "No internet. Pick a name and icon now: your profile is made online the next time you're connected."
        } else {
            state.message
        }
        is SyncState.Failed -> Palette.red to state.message
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(color)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (state == SyncState.Synced) Palette.muted else color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        if (state is SyncState.Offline || state is SyncState.Failed) {
            Spacer(Modifier.width(8.dp))
            GhostButton("Retry", onRetry, compact = true)
        }
    }
}
