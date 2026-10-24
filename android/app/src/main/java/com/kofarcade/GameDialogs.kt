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
            Button(onClick = { game.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back to games") }
        }
    }
}

@Composable
private fun Sheet(
    onDismiss: () -> Unit,
    width: Int = 360,
    /** Docked at the right edge with the game undimmed, for settings you watch take effect. */
    side: Boolean = false,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (side) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(0f) }
        }
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = if (side) Alignment.CenterEnd else Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Palette.panel.copy(alpha = if (side) 0.94f else 1f),
                border = BorderStroke(1.dp, Palette.line),
                modifier = Modifier.widthIn(max = width.dp).padding(16.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) { content() }
            }
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
private fun Overlay(onDismiss: () -> Unit, dismissOnTap: Boolean = true, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
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
    Overlay(onDismiss = { game.close() }) {
        PauseContent(
            title = game.gameTitle,
            fighter = c?.name,
            netplay = game.isNetplay,
            onResume = { game.close() },
            onRematch = { game.rematch() },
            onSave = { game.saveState() },
            onLoad = { game.loadState() },
            onEditMoves = { c?.let { game.show(GameDialog.Editor(it)) } },
            onLayout = { game.startEditing() },
            onControls = { game.show(GameDialog.Controls) },
            onQuit = { game.finish() },
        )
    }
}

/** The pause menu's contents, from plain data (so it can be previewed). */
@Composable
fun PauseContent(
    title: String,
    fighter: String?,
    netplay: Boolean,
    onResume: () -> Unit,
    onRematch: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onEditMoves: () -> Unit,
    onLayout: () -> Unit,
    onControls: () -> Unit,
    onQuit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Kicker(if (netplay) "Paused for both players" else "Paused")
            Text(title, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            if (netplay) {
                Spacer(Modifier.size(8.dp))
                Tag("2 players", Palette.gold)
            }
            if (fighter != null) {
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Portrait(fighter, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("FIGHTING", color = Palette.muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Text(fighter, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(320.dp).verticalScroll(rememberScrollState()),
        ) {
            BigButton("Resume", primary = true, onClick = onResume)
            // Two players: restart together. (A saved state can't be loaded on one phone of two.)
            if (netplay) {
                BigButton("Rematch", onClick = onRematch)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Save state", Modifier.weight(1f), onClick = onSave)
                    BigButton("Load state", Modifier.weight(1f), onClick = onLoad)
                }
            }
            if (fighter != null) BigButton("Edit ${fighter.substringBefore(" ")}'s moves", onClick = onEditMoves)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Layout", Modifier.weight(1f), onClick = onLayout)
                BigButton("Controls", Modifier.weight(1f), onClick = onControls)
            }
            BigButton(if (netplay) "Leave the game" else "Quit to game list", danger = true, onClick = onQuit)
        }
    }
}

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) Palette.gold else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (primary) Palette.gold else Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            if (primary) text.uppercase() else text,
            color = when {
                primary -> Color(0xFF1D1400)
                danger -> Palette.red
                else -> Color.White
            },
            fontWeight = if (primary) FontWeight.Black else FontWeight.SemiBold,
            letterSpacing = if (primary) 1.5.sp else 0.sp,
            fontSize = 15.sp,
            maxLines = 1,
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

private class EditRow(name: String, cmd: String, val type: String) {
    var name by mutableStateOf(name)
    var cmd by mutableStateOf(cmd)
}

@Composable
private fun MoveEditor(game: GameActivity, character: Character) {
    val rows = remember(character.id) {
        mutableStateListOf<EditRow>().apply { addAll(game.movesFor(character).map { EditRow(it.name, it.cmd, it.type) }) }
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
                rows.addAll(game.defaultMoves(character).map { EditRow(it.name, it.cmd, it.type) })
            },
            onCancel = { game.close() },
            onSave = {
                val valid = rows
                    .filter { it.name.isNotBlank() && runCatching { parseCommand(it.cmd) }.isSuccess }
                    .map { Move(it.name.trim(), it.cmd, it.type) }
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

    Sheet(onDismiss = ::cancel, width = 420, side = true) {
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

/** The Controls panel's contents, from plain data (so it can be previewed). */
@Composable
fun ControlsPanel(
    look: Look,
    onLook: (Look) -> Unit,
    canDetect: Boolean,
    canRapid: Boolean = false,
    autoDetect: Boolean,
    onAutoDetect: (Boolean) -> Unit,
    detectInfo: Boolean,
    onDetectInfo: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Kicker("Changes show as you make them")
    Text("Controls", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)

    SectionLabel("Screen")
    ChoiceChips(ScreenMode.entries, look.screen, { it.short }) { onLook(look.copy(screen = it)) }
    Text(look.screen.label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

    SectionLabel("Stick")
    ChoiceChips(StickMode.entries, look.stick, { it.short }) { onLook(look.copy(stick = it)) }
    Text(look.stick.label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

    SectionLabel("Buttons")
    SliderRow("Size", look.size, 0.7f..1.4f, "${(look.size * 100).toInt()}%") { onLook(look.copy(size = it)) }
    SliderRow("Opacity", look.opacity, 0.3f..1f, "${(look.opacity * 100).toInt()}%") { onLook(look.copy(opacity = it)) }
    Text("Move names", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    ChoiceChips(LabelMode.entries, look.labelMode, { it.label }) { onLook(look.copy(labelMode = it)) }
    Spacer(Modifier.size(6.dp))
    SwitchRow("Buttons on the left", look.leftHanded, { onLook(look.copy(leftHanded = it)) }, sub = "For left-handed play")
    SwitchRow("Vibrate on press", look.haptics, { onLook(look.copy(haptics = it)) })

    if (canRapid) {
        SectionLabel("Shooting")
        SwitchRow("Rapid fire", look.rapidFire, { onLook(look.copy(rapidFire = it)) }, sub = "Hold Shoot or Fire to keep firing")
    }

    if (canDetect) {
        SectionLabel("Fighters")
        SwitchRow("Detect automatically", autoDetect, onAutoDetect, sub = "Team, fighter and facing from the game")
        SwitchRow("Show detection readout", detectInfo, onDetectInfo, sub = "For checking it works")
    }

    SectionLabel("Move timing")
    StepperRow("Frames per direction", look.stepFrames, 1..6, { onLook(look.copy(stepFrames = it)) }, sub = "Raise if specials don't come out")
    StepperRow("Frames button held", look.pressFrames, 1..12, { onLook(look.copy(pressFrames = it)) })

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        GhostButton("Cancel", onCancel, Modifier.weight(1f))
        GoldButton("Save", onSave, Modifier.weight(1f))
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, color = Palette.text, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (value > range.first) onChange(value - 1) }) { Text("−", fontSize = 18.sp) }
        Text("$value", color = Palette.text, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { if (value < range.last) onChange(value + 1) }) { Text("+", fontSize = 18.sp) }
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
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Palette.panel.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Palette.line),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                val id = game.editSelected
                Text(
                    id?.let(::controlName) ?: "Pick a control",
                    color = if (id != null) Palette.gold else Palette.muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.width(150.dp),
                )
                Text("Size", color = Palette.muted, fontSize = 12.sp)
                Slider(
                    value = game.editScale,
                    onValueChange = { game.changeEditScale(it) },
                    valueRange = 0.6f..1.8f,
                    enabled = id != null && id != "top" && id != "team",
                    modifier = Modifier.width(170.dp),
                )
                TextButton(onClick = { game.resetLayout() }) { Text("Reset all") }
                TextButton(onClick = { game.finishEditing(save = false) }) { Text("Cancel") }
                Button(onClick = { game.finishEditing(save = true) }) { Text("Save") }
            }
        }
    }
}
