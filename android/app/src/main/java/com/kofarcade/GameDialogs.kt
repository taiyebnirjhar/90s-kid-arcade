package com.kofarcade

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
            Title("Can't start the game")
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
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Palette.panel,
            border = BorderStroke(1.dp, Palette.line),
            modifier = Modifier.widthIn(max = width.dp).padding(16.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) { content() }
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

@Composable
private fun MenuButton(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(text, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            border = BorderStroke(1.dp, Palette.line),
        ) { Text(text, color = if (danger) Palette.red else Palette.text) }
    }
}

// ----- Pause menu -----

@Composable
private fun PauseMenu(game: GameActivity) {
    val c = game.activeCharacter()
    Sheet(onDismiss = { game.close() }, width = 340) {
        Title(game.gameTitle)
        MenuButton("Resume", primary = true) { game.close() }
        MenuButton("Save state") { game.saveState() }
        MenuButton("Load state") { game.loadState() }
        if (c != null) MenuButton("Edit ${c.name}'s moves") { game.show(GameDialog.Editor(c)) }
        MenuButton("Customize layout") { game.startEditing() }
        MenuButton("Controls") { game.show(GameDialog.Controls) }
        MenuButton("Quit to game list", danger = true) { game.finish() }
    }
}

// ----- Fighter picker -----

@Composable
fun Portrait(name: String?, size: Int = 38) {
    val hue = name?.let(::nameHue) ?: 0f
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (name == null) {
                    Brush.linearGradient(listOf(Palette.bg, Palette.bg))
                } else {
                    Brush.linearGradient(
                        listOf(Color.hsv(hue, 0.7f, 0.8f), Color.hsv((hue + 30) % 360, 0.65f, 0.35f)),
                    )
                },
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (name == null) "+" else initials(name),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = (size * 0.37f).sp,
        )
    }
}

@Composable
private fun RosterPicker(game: GameActivity, slot: Int) {
    val roster = game.rosterOrNull ?: return
    Dialog(onDismissRequest = { game.close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Palette.panel,
            border = BorderStroke(1.dp, Palette.line),
            modifier = Modifier.widthIn(max = 720.dp).padding(16.dp),
        ) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Title("Pick fighter", "slot ${slot + 1}")
                    }
                    TextButton(onClick = { game.close() }) { Text("✕", color = Palette.muted, fontSize = 18.sp) }
                }
                Text("Tap a team name to fill all ${game.teamSize} slots.", color = Palette.muted, fontSize = 13.sp)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 600.dp).padding(bottom = 14.dp),
                ) {
                    for ((team, members) in roster.teams) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                team.uppercase(),
                                color = Palette.gold,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        game.pickTeam(members.map { it.id })
                                        game.close()
                                    }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                            )
                        }
                        items(members, key = { it.id }) { c ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Palette.bg)
                                    .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
                                    .clickable {
                                        game.pickCharacter(slot, c.id)
                                        game.close()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Portrait(c.name)
                                Spacer(Modifier.size(4.dp))
                                Text(c.name, fontSize = 11.sp, color = Palette.text, textAlign = TextAlign.Center, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----- Move editor -----

private class EditRow(name: String, cmd: String, val type: String) {
    var name by mutableStateOf(name)
    var cmd by mutableStateOf(cmd)
}

@Composable
private fun MoveEditor(game: GameActivity, character: Character) {
    val rows = remember(character.id) {
        mutableStateListOf<EditRow>().apply { addAll(game.movesFor(character).map { EditRow(it.name, it.cmd, it.type) }) }
    }
    Sheet(onDismiss = { game.close() }, width = 640) {
        Title("Moves", character.name)
        Text(
            "Numpad notation, written facing right: 2 down, 4 back, 6 forward, 8 up. " +
                "236 = ↓↘→, 63214 = →↘↓↙←, [4]6 = charge back, forward. Buttons go after +. " +
                "Up to six moves sit on the ring.",
            color = Palette.muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(10.dp))
        rows.forEachIndexed { i, row ->
            val error = runCatching { parseCommand(row.cmd) }.exceptionOrNull()?.message
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                OutlinedTextField(
                    value = row.name,
                    onValueChange = { row.name = it.take(32) },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = row.cmd,
                    onValueChange = { row.cmd = it.uppercase().take(24) },
                    singleLine = true,
                    label = { Text("Command") },
                    isError = error != null,
                    supportingText = { Text(error ?: toArrows(row.cmd), color = if (error != null) Palette.red else Palette.gold) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (error == null) game.testMove(row.cmd) }) { Text("▶") }
                TextButton(onClick = { rows.removeAt(i) }) { Text("✕", color = Palette.muted) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { rows += EditRow("Super", "236236+A", "dm") }, enabled = rows.size < 6) { Text("＋ Add move") }
            OutlinedButton(onClick = {
                rows.clear()
                rows.addAll(game.defaultMoves(character).map { EditRow(it.name, it.cmd, it.type) })
            }) { Text("Reset") }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            TextButton(onClick = { game.close() }) { Text("Cancel") }
            Button(onClick = {
                val valid = rows
                    .filter { it.name.isNotBlank() && runCatching { parseCommand(it.cmd) }.isSuccess }
                    .map { Move(it.name.trim(), it.cmd, it.type) }
                game.saveMoves(character, valid)
                game.close()
            }) { Text("Save") }
        }
    }
}

// ----- Controls -----

@Composable
private fun ControlsSettings(game: GameActivity) {
    val p = game.settings
    var stick by remember { mutableStateOf(p.stick) }
    var size by remember { mutableFloatStateOf(p.size) }
    var opacity by remember { mutableFloatStateOf(p.opacity) }
    var labels by remember { mutableStateOf(p.labels) }
    var haptics by remember { mutableStateOf(p.haptics) }
    var lefty by remember { mutableStateOf(p.leftHanded) }
    var step by remember { mutableIntStateOf(p.stepFrames) }
    var screen by remember { mutableStateOf(p.screen) }
    var press by remember { mutableIntStateOf(p.pressFrames) }

    Sheet(onDismiss = { game.close() }, width = 480) {
        Title("Controls")
        Text("Screen", color = Palette.text, fontWeight = FontWeight.SemiBold)
        ScreenMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { screen = mode },
            ) {
                RadioButton(selected = screen == mode, onClick = { screen = mode })
                Text(mode.label, color = Palette.text, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.size(6.dp))
        Text("Stick", color = Palette.text, fontWeight = FontWeight.SemiBold)
        StickMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { stick = mode },
            ) {
                RadioButton(selected = stick == mode, onClick = { stick = mode })
                Text(mode.label, color = Palette.text, fontSize = 14.sp)
            }
        }
        Labeled("Control size") { Slider(value = size, onValueChange = { size = it }, valueRange = 0.7f..1.4f) }
        Labeled("Opacity") { Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.3f..1f) }
        Toggle("Show move names", labels) { labels = it }
        Toggle("Vibrate on press", haptics) { haptics = it }
        Toggle("Buttons on the left", lefty) { lefty = it }
        Spacer(Modifier.size(8.dp))
        Text("Move timing", color = Palette.text, fontWeight = FontWeight.SemiBold)
        Stepper("Frames per direction", step, 1..6) { step = it }
        Stepper("Frames button held", press, 1..12) { press = it }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            TextButton(onClick = { game.close() }) { Text("Cancel") }
            Button(
                onClick = {
                    p.screen = screen
                    p.stick = stick
                    p.size = size
                    p.opacity = opacity
                    p.labels = labels
                    p.haptics = haptics
                    p.leftHanded = lefty
                    p.stepFrames = step
                    p.pressFrames = press
                    game.applyPrefs()
                    game.close()
                },
                colors = ButtonDefaults.buttonColors(),
            ) { Text("Save") }
        }
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
