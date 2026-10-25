package com.kofarcade

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.DialogProperties
import com.kofarcade.core.Character
import com.kofarcade.core.Move
import com.kofarcade.core.parseCommand
import com.kofarcade.core.toArrows

@Composable
fun GameDialogs(game: GameActivity) {
    if (game.editing) EditBar(game)
    RoomLayer(game)
    when (val d = game.dialog) {
        null -> Unit
        GameDialog.Pause -> PauseMenu(game)
        is GameDialog.Roster -> RosterPicker(game, d.slot)
        is GameDialog.Editor -> MoveEditor(game, d.character)
        GameDialog.Controls -> ControlsSettings(game)
        is GameDialog.Error -> Sheet(onDismiss = { game.finish() }) {
            Title(d.title)
            Text(d.message, color = Palette.muted)
            Spacer(Modifier.size(16.dp))
            GoldButton("Back to games", { game.finish() }, Modifier.fillMaxWidth())
        }
        is GameDialog.Room -> Sheet(onDismiss = { game.close() }, width = if (LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp) 880 else 460) {
            val ui = game.roomUi
            if (ui != null) {
                RoomSheetContent(
                    ui,
                    first = d.first,
                    onCopy = { game.copyRoomKey() },
                    onShare = { game.shareRoomKey() },
                    onSeat = { game.takeSeat() },
                    onWatch = { game.watchInstead() },
                    onLeave = { game.finish() },
                    onClose = { game.close() },
                    onFree = { game.freeHeldSeat(it) },
                )
            }
        }
    }
}

// Over the game in a room: the room pill (top right) and news sliding in at the top.
@Composable
private fun RoomLayer(game: GameActivity) {
    val ui = game.roomUi ?: return
    val news = game.announcement
    LaunchedEffect(news) {
        if (news != null) {
            kotlinx.coroutines.delay(if (news.big) 2_600 else 3_200)
            game.clearAnnouncement(news)
        }
    }
    if (game.dialog != null || game.editing) return
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, end = 14.dp)) {
        RoomPill(ui, onClick = { game.openRoom() }, Modifier.align(Alignment.TopEnd))
        AnnouncementBanner(news, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
    }
}

/** A dialog over the game: a slanted black panel, like the pause menu's. */
@Composable
private fun Sheet(
    onDismiss: () -> Unit,
    width: Int = 360,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SheetFrame(width, content)
    }
}

/** The [Sheet]'s panel, centred (so it can be previewed). */
@Composable
fun SheetFrame(width: Int = 360, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = width.dp).padding(horizontal = 16.dp, vertical = 8.dp).slantPanel(left = 22.dp, right = 22.dp, alpha = 0.96f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 14.dp)) { content() }
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 12.dp)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        if (sub != null) {
            Text("  $sub", fontSize = 13.sp, color = Palette.muted)
        }
    }
}

// ----- Full-screen overlays (pause menu, fighter select) -----

/**
 * A full-screen layer over the dimmed game, like a console game's menus,
 * instead of a small dialog box. [dismissOnTap] closes it when you tap the
 * empty space around the content.
 */
@Composable
private fun Overlay(
    onDismiss: () -> Unit,
    dismissOnTap: Boolean = true,
    /** Darken the game behind; off when [content] shades it itself. */
    scrim: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (!scrim) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(0f) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (scrim) {
                        Modifier.background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.88f),
                                1f to Color.Black.copy(alpha = 0.6f),
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (dismissOnTap) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) { content() }
    }
}

// ----- Pause menu -----

@Composable
private fun PauseMenu(game: GameActivity) {
    val c = game.activeCharacter()
    Overlay(onDismiss = { game.close() }, scrim = false) {
        PauseContent(
            title = game.gameTitle,
            fighter = c?.name,
            netplay = game.isNetplay,
            roomInfo = game.roomInfo,
            roomStatus = game.roomStatus,
            watching = game.isWatching,
            onRoom = { game.openRoom() },
            onResume = { game.close() },
            onRestart = { game.restartGame() },
            onSave = { game.saveState() },
            onLoad = { game.loadState() },
            onEditMoves = { c?.let { game.show(GameDialog.Editor(it)) } },
            onLayout = { game.startEditing() },
            onControls = { game.show(GameDialog.Controls) },
            onQuit = { game.finish() },
        )
    }
}

// ----- Fighter select -----

@Composable
fun Portrait(name: String?, size: Int = 38, selected: Boolean = false) {
    val hue = name?.let(::nameHue) ?: 0f
    val shape = RoundedCornerShape((size * 0.24f).dp)
    Box(
        Modifier
            .size(size.dp)
            .clip(shape)
            .background(
                if (name == null) {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f)))
                } else {
                    Brush.linearGradient(
                        listOf(Color.hsv(hue, 0.7f, 0.82f), Color.hsv((hue + 30) % 360, 0.65f, 0.32f)),
                    )
                },
            )
            .border(
                if (selected) 2.5.dp else 1.dp,
                if (selected) Palette.gold else Color.White.copy(alpha = 0.25f),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (name == null) "+" else initials(name),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = (size * 0.36f).sp,
        )
    }
}

/**
 * The arcade-style select screen: your team's slots along the top (tap one to
 * pick for it), then every fighter by team. Tap a team's name to take the
 * whole team.
 */
@Composable
private fun RosterPicker(game: GameActivity, slot: Int) {
    val roster = game.rosterOrNull ?: return
    var picking by remember { mutableIntStateOf(slot) }
    Overlay(onDismiss = { game.close() }, dismissOnTap = false) {
        RosterContent(
            teams = roster.teams,
            team = game.team().map { roster.character(it) },
            picking = picking,
            onPickSlot = { picking = it },
            onPickTeam = { members ->
                game.pickTeam(members.map { it.id })
                game.close()
            },
            onPick = { c ->
                game.pickCharacter(picking, c.id)
                game.close()
            },
            onClose = { game.close() },
        )
    }
}

/** The fighter picker's contents, from plain data (so it can be previewed). */
@Composable
fun RosterContent(
    teams: List<Pair<String, List<Character>>>,
    team: List<Character?>,
    picking: Int,
    onPickSlot: (Int) -> Unit,
    onPickTeam: (List<Character>) -> Unit,
    onPick: (Character) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("Select fighter")
                Text("Slot ${picking + 1}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text("Tap a fighter, or Take team for all ${team.size}", color = Palette.muted, fontSize = 12.sp)
            }
            // Your team: the slot you're picking for is outlined in gold.
            team.forEachIndexed { i, c ->
                val on = i == picking
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Palette.gold.copy(alpha = 0.12f) else Palette.panel)
                        .border(1.dp, if (on) Palette.gold else Palette.line, RoundedCornerShape(14.dp))
                        .clickable { onPickSlot(i) }
                        .padding(6.dp),
                ) {
                    Portrait(c?.name, size = 38, selected = on)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.width(92.dp)) {
                        Text("SLOT ${i + 1}", color = if (on) Palette.gold else Palette.muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text(
                            c?.name ?: "Empty",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Palette.panel)
                    .border(1.dp, Palette.line, CircleShape).clickable(onClick = onClose),
            ) { Text("✕", color = Color.White, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(4.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            for ((teamName, members) in teams) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text(teamName.uppercase(), color = Palette.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "TAKE TEAM",
                            color = Palette.gold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.gold.copy(alpha = 0.12f))
                                .clickable { onPickTeam(members) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                items(members, key = { it.id }) { c ->
                    val chosen = team.getOrNull(picking)?.id == c.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (chosen) Palette.gold.copy(alpha = 0.12f) else Palette.panel)
                            .border(1.dp, if (chosen) Palette.gold else Palette.line, RoundedCornerShape(16.dp))
                            .clickable { onPick(c) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Portrait(c.name, size = 54, selected = chosen)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            c.name,
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 13.sp,
                            maxLines = 2,
                            minLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private class EditRow(name: String, cmd: String, val type: String, val note: String = "") {
    var name by mutableStateOf(name)
    var cmd by mutableStateOf(cmd)
}

@Composable
private fun MoveEditor(game: GameActivity, character: Character) {
    val rows = remember(character.id) {
        mutableStateListOf<EditRow>().apply { addAll(game.movesFor(character).map { EditRow(it.name, it.cmd, it.type, it.note) }) }
    }
    Sheet(onDismiss = { game.close() }, width = 680) {
        MoveEditorContent(
            fighter = character.name,
            rows = rows.map { MoveRowUi(it.name, it.cmd) },
            onName = { i, v -> rows[i].name = v.take(32) },
            onCmd = { i, v -> rows[i].cmd = v.uppercase().take(24) },
            onTest = { i -> game.testMove(rows[i].cmd) },
            onRemove = { i -> rows.removeAt(i) },
            onAdd = { rows += EditRow("Super", "236236+A", "dm") },
            onReset = {
                rows.clear()
                rows.addAll(game.defaultMoves(character).map { EditRow(it.name, it.cmd, it.type, it.note) })
            },
            onCancel = { game.close() },
            onSave = {
                val valid = rows
                    .filter { it.name.isNotBlank() && runCatching { parseCommand(it.cmd) }.isSuccess }
                    // The note goes along: it says "in air" (jump first) and which Super Art.
                    .map { Move(it.name.trim(), it.cmd, it.type, it.note) }
                game.saveMoves(character, valid)
                game.close()
            },
        )
    }
}

/** One move being edited. */
data class MoveRowUi(val name: String, val cmd: String)

/** The move editor's contents, from plain data (so it can be previewed). */
@Composable
fun MoveEditorContent(
    fighter: String,
    rows: List<MoveRowUi>,
    onName: (Int, String) -> Unit,
    onCmd: (Int, String) -> Unit,
    onTest: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Portrait(fighter, size = 44)
        Spacer(Modifier.width(12.dp))
        Column {
            Kicker("Moves")
            Text(fighter, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Written facing right: 2 down, 4 back, 6 forward, 8 up. 236 = ↓↘→, [4]6 = charge back then forward, " +
            "buttons after +. Up to six moves sit on the ring.",
        color = Palette.muted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { i, row ->
            val error = runCatching { parseCommand(row.cmd) }.exceptionOrNull()?.message
            Panel(highlight = false) {
                Row(verticalAlignment = Alignment.Bottom) {
                    ArcadeField(row.name, { onName(i, it) }, "Name", Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    ArcadeField(row.cmd, { onCmd(i, it) }, "Input", Modifier.weight(1f), keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii)
                    Spacer(Modifier.width(10.dp))
                    GoldButton("Test", { onTest(i) }, enabled = error == null)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(46.dp).clip(CircleShape).border(1.dp, Palette.line, CircleShape)
                            .clickable { onRemove(i) },
                    ) { Text("✕", color = Palette.muted, fontSize = 15.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: toArrows(row.cmd),
                    color = if (error != null) Palette.red else Palette.gold,
                    fontSize = if (error != null) 12.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
        GhostButton("+ Add move", onAdd, enabled = rows.size < 6)
        GhostButton("Reset", onReset)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        GhostButton("Cancel", onCancel, Modifier.weight(1f))
        GoldButton("Save", onSave, Modifier.weight(1f))
    }
}

@Composable
private fun ControlsSettings(game: GameActivity) {
    val p = game.settings
    var look by remember { mutableStateOf(p.look()) }
    var auto by remember { mutableStateOf(p.autoDetect) }
    var info by remember { mutableStateOf(p.detectInfo) }

    // Every change shows on the game right away; Save keeps it, Cancel puts
    // back what was saved.
    LaunchedEffect(look) { game.preview(look) }
    fun cancel() {
        game.applyPrefs()
        game.close()
    }

    Overlay(onDismiss = ::cancel, scrim = false) {
        ControlsPanel(
            look = look,
            onLook = { look = it },
            canDetect = game.canDetect,
            canRapid = game.hasRapidFire,
            autoDetect = auto,
            onAutoDetect = { auto = it },
            detectInfo = info,
            onDetectInfo = { info = it },
            onCancel = ::cancel,
            onSave = {
                p.screen = look.screen
                p.stick = look.stick
                p.size = look.size
                p.opacity = look.opacity
                p.labelMode = look.labelMode
                p.haptics = look.haptics
                p.leftHanded = look.leftHanded
                p.stepFrames = look.stepFrames
                p.pressFrames = look.pressFrames
                p.rapidFire = look.rapidFire
                p.autoDetect = auto
                p.detectInfo = info
                game.applyPrefs()
                game.close()
            },
        )
    }
}

// ----- Layout editor bar -----

/** Friendly names for the editor: "face:A" -> "A button", "move:0" -> "Move 1". */
private fun controlName(id: String): String = when {
    id == "stick" -> "Stick"
    id == "pause" -> "Pause button"
    id == "top" -> "Coin / Facing / Start"
    id == "team" -> "Team bar"
    id == "block" -> "Block button"
    id.startsWith("face:") -> "${id.removePrefix("face:")} button"
    id.startsWith("move:") -> "Move ${id.removePrefix("move:").toInt() + 1}"
    id.startsWith("sys:") -> "Quick button ${id.removePrefix("sys:").toInt() + 1}"
    else -> id
}

@Composable
private fun EditBar(game: GameActivity) {
    val id = game.editSelected
    EditBarContent(
        picked = id?.let(::controlName),
        scale = game.editScale,
        canResize = id != null && id != "top" && id != "team",
        onScale = { game.changeEditScale(it) },
        onReset = { game.resetLayout() },
        onCancel = { game.finishEditing(save = false) },
        onSave = { game.finishEditing(save = true) },
    )
}
