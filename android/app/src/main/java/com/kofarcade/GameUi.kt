package com.kofarcade

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The in-game look, like a fighting game's own menus: slanted black panels
// over the game, big italic words, gold for what's picked.

/** Black, a touch see-through, for panels over the game. */
private val Ink = Color(0xFF07070C)

/**
 * A four-sided shape with slanted sides: the top-left corner pulled in by
 * [left], the bottom-right by [right]. Both: a parallelogram; one at 0: that
 * side sits flush (against the screen's edge).
 */
class Slant(private val left: Dp, private val right: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = with(density) { left.toPx() }
        val r = with(density) { right.toPx() }
        return Outline.Generic(slantPath(size, l, r))
    }
}

private fun slantPath(size: Size, l: Float, r: Float) = Path().apply {
    moveTo(l, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - r, size.height)
    lineTo(0f, size.height)
    close()
}

/** A slanted black panel with a thin gold line down its slanted side(s). */
fun Modifier.slantPanel(left: Dp, right: Dp, alpha: Float = 0.9f): Modifier = drawWithCache {
    val l = left.toPx()
    val r = right.toPx()
    val path = slantPath(size, l, r)
    val edge = Stroke(2.dp.toPx())
    onDrawBehind {
        drawPath(path, Ink.copy(alpha = alpha))
        if (r > 0) drawLine(Palette.gold, Offset(size.width, 0f), Offset(size.width - r, size.height), edge.width)
        if (l > 0) drawLine(Palette.gold, Offset(l, 0f), Offset(0f, size.height), edge.width)
    }
}

// ----- Pause menu -----

/** The pause menu's contents, from plain data (so it can be previewed). */
@Composable
fun PauseContent(
    title: String,
    fighter: String?,
    netplay: Boolean,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onEditMoves: () -> Unit,
    onLayout: () -> Unit,
    onControls: () -> Unit,
    onQuit: () -> Unit,
    /** Multiplayer: the room's key and who's in. */
    roomInfo: String? = null,
    /** Multiplayer: how the connection is doing (small print). */
    roomStatus: String? = null,
    /** Alone: how smoothly the game runs (small print), and its report. */
    playback: String? = null,
    /** Multiplayer: this phone watches (its menu stops nobody). */
    watching: Boolean = false,
    /** Multiplayer: the room sheet (key, seats, who watches). */
    onRoom: () -> Unit = {},
    /** Share what happened (multiplayer: to the connection; alone: how it runs), to report a problem. */
    onReport: () -> Unit = {},
    /** The game's sound, and its quick switch (the menu stays open). */
    soundOn: Boolean = true,
    onSound: () -> Unit = {},
) {
    // The item under a finger lights up; with none pressed, Resume does.
    var pressed by remember { mutableStateOf<String?>(null) }
    val shown = slideIn()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Dark behind the menu, clear over the right, so the game still shows.
            .graphicsLayer { alpha = shown.value }
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.55f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)),
    ) {
        // Small phones (640 dp wide) get a narrower panel and tag, so the two never meet.
        val narrow = maxWidth < 760.dp
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxHeight()
                .width(if (narrow) 330.dp else 400.dp)
                .graphicsLayer { translationX = -(1 - shown.value) * size.width }
                .slantPanel(left = 0.dp, right = 80.dp)
                // Taps on the panel itself don't close the menu.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 96.dp, top = 6.dp, bottom = 6.dp)) {
                @Composable
                fun item(text: String, danger: Boolean = false, onClick: () -> Unit) =
                    MenuItem(text, lit = (pressed ?: "Resume") == text, danger = danger, onPress = { pressed = if (it) text else pressed.takeIf { p -> p != text } }, onClick = onClick)

                item("Resume", onClick = onResume)
                if (netplay) item("Room & players", onClick = onRoom)
                // Save states. In a room, a player's save is loaded for everyone (the host checks it first).
                item("Save state", onClick = onSave)
                if (!netplay) item("Load state", onClick = onLoad) else if (!watching) item("Load state for everyone", onClick = onLoad)
                if (fighter != null) item("Edit ${fighter.substringBefore(" ")}'s moves", onClick = onEditMoves)
                item(if (soundOn) "Sound: on" else "Sound: off", onClick = onSound)
                item("Settings", onClick = onControls)
                item("Button layout", onClick = onLayout)
                // Rarely needed: KOF's challenger mode and continues are the rematch.
                if (netplay && !watching) item("Restart for everyone", onClick = onRestart)
                // Alone the game goes on where you left it; this starts it afresh.
                if (!netplay) item("Start over", onClick = onRestart)
                item(if (netplay) "Leave the game" else "Quit to game list", danger = true, onClick = onQuit)
            }
        }
        // Top right, like the game's own pause screen: the big tag, then what's being played.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp)
                .graphicsLayer { translationX = (1 - shown.value) * size.width },
        ) {
            Box(
                Modifier
                    .slantPanel(left = 24.dp, right = 0.dp, alpha = 0.92f)
                    .padding(start = 40.dp, end = 30.dp),
            ) {
                Text(
                    if (watching) "WATCHING" else "PAUSED",
                    color = Color.White,
                    fontSize = if (narrow) 34.sp else 44.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 1.sp,
                )
            }
            if (netplay) {
                Text(
                    (if (watching) "The game goes on" else "For everyone").uppercase(),
                    color = Ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 6.dp).clip(Slant(8.dp, 0.dp)).background(Palette.gold)
                        .padding(start = 16.dp, end = 30.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .widthIn(max = if (narrow) 270.dp else 360.dp)
                    .slantPanel(left = 14.dp, right = 0.dp, alpha = 0.75f)
                    .padding(start = 26.dp, end = 30.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    title.uppercase(),
                    color = Palette.gold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fighter != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fighter, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Portrait(fighter, size = 26)
                    }
                }
                if (netplay) {
                    Spacer(Modifier.height(4.dp))
                    Text(roomInfo ?: "Multiplayer", color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, textAlign = TextAlign.End)
                    // The technical line, for when something's wrong.
                    if (roomStatus != null) {
                        var details by remember { mutableStateOf(false) }
                        Text(
                            if (details) roomStatus else "Connection details ▸",
                            color = Palette.muted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.clickable { details = !details }.padding(vertical = 6.dp),
                        )
                        if (details) {
                            Text(
                                "Send connection report ▸",
                                color = Palette.gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.clickable(onClick = onReport).padding(vertical = 6.dp),
                            )
                        }
                    }
                } else if (playback != null) {
                    // How smoothly it runs, for when it doesn't.
                    var details by remember { mutableStateOf(false) }
                    Text(
                        if (details) playback else "Performance ▸",
                        color = Palette.muted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.clickable { details = !details }.padding(vertical = 6.dp),
                    )
                    if (details) {
                        Text(
                            "Send report ▸",
                            color = Palette.gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.clickable(onClick = onReport).padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** True where only one still frame is drawn (screenshot tests): panels start in place. */
val LocalStill = staticCompositionLocalOf { false }

/** 0 to 1 as a panel slides in, once, when it opens. */
@Composable
private fun slideIn(): Animatable<Float, AnimationVector1D> {
    val preview = LocalInspectionMode.current || LocalStill.current
    val shown = remember { Animatable(if (preview) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
    return shown
}

/** One line of the pause menu: big italic words, a gold slanted bar when [lit]. */
@Composable
private fun MenuItem(text: String, lit: Boolean, danger: Boolean, onPress: (Boolean) -> Unit, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    LaunchedEffect(down) { onPress(down) }
    // Quick, so a press feels instant, but it still slides rather than blinks.
    val bar by animateColorAsState(if (!lit) Color.Transparent else if (danger) Palette.red else Palette.gold, tween(90), label = "bar")
    val ink by animateColorAsState(
        when {
            lit && danger -> Color.White
            lit -> OnGold
            danger -> Palette.red
            else -> Palette.text
        },
        tween(90),
        label = "ink",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(Slant(12.dp, 12.dp))
            .background(bar)
            .clickable(source, indication = null, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
    ) {
        Text(
            text,
            color = ink,
            fontSize = 21.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (lit) Text("▸", color = if (danger) Color.White else OnGold, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

// ----- Controls settings -----

/** The settings panel's tabs. */
enum class SettingsTab(val title: String) { SCREEN("Screen"), CONTROLS("Controls"), MOVES("Moves"), SOUND("Sound") }

/**
 * The Controls settings, from plain data (so they can be previewed): a
 * slanted panel down the right side, the game left in view, with tabs along
 * the top and a "label … ◂ value ▸" row per setting.
 */
@Composable
fun ControlsPanel(
    look: Look,
    onLook: (Look) -> Unit,
    canDetect: Boolean,
    canRapid: Boolean = false,
    detectInfo: Boolean,
    onDetectInfo: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    startTab: SettingsTab = SettingsTab.SCREEN,
) {
    var tab by remember { mutableStateOf(startTab) }
    val shown = slideIn()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(if (maxWidth < 760.dp) 440.dp else 490.dp)
                .graphicsLayer { translationX = (1 - shown.value) * size.width }
                .slantPanel(left = 50.dp, right = 0.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 62.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Settings", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Spacer(Modifier.width(12.dp))
                Text("Changes show as you make them", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            // Scrolls sideways if the tabs don't all fit (small phones).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (t in SettingsTab.entries) {
                    val on = t == tab
                    // A 48 dp target round a smaller chip.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(48.dp).clickable(remember { MutableInteractionSource() }, indication = null) { tab = t }) {
                    Text(
                        t.title.uppercase(),
                        color = if (on) OnGold else Palette.muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        letterSpacing = 1.sp,
                        modifier = Modifier
                            .clip(Slant(8.dp, 8.dp))
                            .background(if (on) Palette.gold else Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (tab) {
                    SettingsTab.SCREEN -> {
                        PickRow("Picture", ScreenMode.entries, look.screen, { it.short }, sub = look.screen.label) { onLook(look.copy(screen = it)) }
                        if (canDetect) ToggleRow("Detection readout", detectInfo, onDetectInfo, sub = "For checking fighter detection works")
                    }
                    SettingsTab.CONTROLS -> {
                        PickRow("Stick", StickMode.entries, look.stick, { it.short }, sub = look.stick.label) { onLook(look.copy(stick = it)) }
                        StepRow("Button size", look.size, 0.7f..1.4f, 0.05f) { onLook(look.copy(size = it)) }
                        StepRow("Opacity", look.opacity, 0.3f..1f, 0.05f, sub = "How solid the controls are") { onLook(look.copy(opacity = it)) }
                        ToggleRow("Buttons on the left", look.leftHanded, { onLook(look.copy(leftHanded = it)) }, sub = "For left-handed play")
                        ToggleRow("Vibrate on press", look.haptics, { onLook(look.copy(haptics = it)) })
                        if (canRapid) ToggleRow("Rapid fire", look.rapidFire, { onLook(look.copy(rapidFire = it)) }, sub = "Hold Shoot or Fire to keep firing")
                    }
                    SettingsTab.SOUND -> {
                        ToggleRow("Sound", look.sound, { onLook(look.copy(sound = it)) }, sub = "The game's music and effects")
                        StepRow("Volume", look.volume, 0.1f..1f, 0.1f, sub = if (look.sound) "The phone's volume buttons work too" else "Sound is off") {
                            onLook(look.copy(volume = it, sound = true))
                        }
                    }
                    SettingsTab.MOVES -> {
                        PickRow("Move names", LabelMode.entries, look.labelMode, { it.label }, sub = "Names under the move buttons") { onLook(look.copy(labelMode = it)) }
                        CountRow("Frames per direction", look.stepFrames, 1..6, sub = "Raise if specials don't come out") { onLook(look.copy(stepFrames = it)) }
                        CountRow("Frames button held", look.pressFrames, 1..12) { onLook(look.copy(pressFrames = it)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                GhostButton("Cancel", onCancel, Modifier.weight(1f))
                GoldButton("Save", onSave, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One setting: its name (and a line about it) on the left, "◂ value ▸" on
 * the right. The arrows step back and forward; tapping the row steps forward.
 * [fraction]: a thin bar under the value, for amounts.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    sub: String? = null,
    canBack: Boolean = true,
    canNext: Boolean = true,
    fraction: Float? = null,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canNext, onClick = onNext)
            .drawWithCache {
                val y = size.height - 0.5.dp.toPx()
                onDrawBehind { drawLine(Palette.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            }
            .padding(vertical = 7.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Arrow("◂", canBack, onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 92.dp)) {
            Text(
                value.uppercase(),
                color = Palette.gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (fraction != null) {
                Box(Modifier.padding(top = 4.dp).width(72.dp).height(3.dp).clip(Slant(1.dp, 1.dp)).background(Palette.line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Palette.gold))
                }
            }
        }
        Arrow("▸", canNext, onNext)
    }
}

@Composable
private fun Arrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(glyph, color = if (enabled) Palette.gold else Palette.line, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

/** Pick one of [options]: the arrows go round. */
@Composable
private fun <T> PickRow(label: String, options: List<T>, selected: T, name: (T) -> String, sub: String? = null, onPick: (T) -> Unit) {
    val i = options.indexOf(selected)
    SettingRow(
        label, name(selected), sub,
        onBack = { onPick(options[(i - 1 + options.size) % options.size]) },
        onNext = { onPick(options[(i + 1) % options.size]) },
    )
}

/** An amount in [range], shown in percent, in steps of [step]. */
@Composable
private fun StepRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, sub: String? = null, onChange: (Float) -> Unit) {
    // Snap to the steps, so a saved odd value (46%) lands on the grid on the first press.
    fun stepped(dir: Int) = (((value / step).roundToInt() + dir) * step).coerceIn(range.start, range.endInclusive)
    SettingRow(
        label, "${(value * 100).roundToInt()}%", sub,
        canBack = value > range.start + 0.001f,
        canNext = value < range.endInclusive - 0.001f,
        fraction = (value - range.start) / (range.endInclusive - range.start),
        onBack = { onChange(stepped(-1)) },
        onNext = { onChange(stepped(1)) },
    )
}

@Composable
private fun CountRow(label: String, value: Int, range: IntRange, sub: String? = null, onChange: (Int) -> Unit) {
    SettingRow(
        label, "$value", sub,
        canBack = value > range.first,
        canNext = value < range.last,
        onBack = { onChange(value - 1) },
        onNext = { onChange(value + 1) },
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    SettingRow(label, if (value) "On" else "Off", sub, onBack = { onChange(!value) }, onNext = { onChange(!value) })
}

// ----- Layout editor bar -----

/**
 * The layout editor's bar along the top: an "Edit controls" chip, the picked
 * control and its size, and Reset all / Cancel / Save.
 */
@Composable
fun EditBarContent(
    picked: String?,
    scale: Float,
    canResize: Boolean,
    onScale: (Float) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    /** The gear: opens the settings for every control (stick, side, size, opacity). */
    onAll: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        // Small phones: a shorter name box and slider, so the bar fits in 640 dp.
        val narrow = maxWidth < 760.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .slantPanel(left = 18.dp, right = 18.dp, alpha = 0.88f)
                .padding(start = 24.dp, end = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(Slant(8.dp, 8.dp))
                    .background(Palette.gold)
                    // The gear opens the settings for every control (a 48 dp target round the chip).
                    .then(if (onAll != null) Modifier.clickable(onClick = onAll) else Modifier)
                    .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            ) {
                GearIcon(Modifier.size(if (onAll != null) 18.dp else 14.dp), OnGold, hole = Palette.gold)
                if (!narrow) {
                    Spacer(Modifier.width(6.dp))
                    Text("EDIT CONTROLS", color = OnGold, fontSize = 11.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                picked ?: "Pick a control",
                color = if (picked != null) Palette.text else Palette.muted,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (narrow) 104.dp else 140.dp),
            )
            Text("SIZE", color = if (canResize) Palette.muted else Palette.line, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            GoldSlider(scale, onScale, 0.6f..1.8f, canResize, Modifier.width(if (narrow) 110.dp else 150.dp).padding(horizontal = 10.dp))
            BarAction(if (narrow) "Reset" else "Reset all", Palette.muted, onReset)
            BarAction("Cancel", Palette.text, onCancel)
            Spacer(Modifier.width(4.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(48.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSave),
            ) {
                Text(
                    "SAVE",
                    color = OnGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clip(Slant(8.dp, 8.dp)).background(Palette.gold).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
        // Under the bar: how to edit (the gear has the rest).
        Text(
            if (onAll != null) "Drag a control to move it · tap one to resize it · ⚙ for the stick, side, size and opacity" else "Drag any control to move it · tap one to resize it",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 60.dp, start = 24.dp, end = 24.dp),
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
        )
    }
}

/**
 * The layout editor's settings for every control at once, sliding in from
 * the side away from the buttons ([fromLeft]), so they show each change:
 * the stick, which side the buttons are on, their size and opacity.
 */
@Composable
fun EditAllSheet(look: Look, onLook: (Look) -> Unit, fromLeft: Boolean, onClose: () -> Unit) {
    val shown = slideIn()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap beside the panel closes it.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = if (fromLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(if (maxWidth < 760.dp) 400.dp else 460.dp)
                .graphicsLayer { translationX = (1 - shown.value) * size.width * (if (fromLeft) -1 else 1) }
                .slantPanel(left = if (fromLeft) 0.dp else 50.dp, right = if (fromLeft) 50.dp else 0.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = if (fromLeft) 24.dp else 62.dp, end = if (fromLeft) 62.dp else 24.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Text("All controls", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            Text("Changes show as you make them", color = Palette.muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                PickRow("Stick", StickMode.entries, look.stick, { it.short }, sub = look.stick.label) { onLook(look.copy(stick = it)) }
                ToggleRow("Buttons on the left", look.leftHanded, { onLook(look.copy(leftHanded = it)) }, sub = "For left-handed play")
                StepRow("Button size", look.size, 0.7f..1.4f, 0.05f) { onLook(look.copy(size = it)) }
                StepRow("Opacity", look.opacity, 0.3f..1f, 0.05f, sub = "How solid the controls are") { onLook(look.copy(opacity = it)) }
            }
            GoldButton("Done", onClose, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** A thin gold track with a round thumb; drag or tap anywhere on it (48 dp tall to touch). */
@Composable
private fun GoldSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    fun at(x: Float, width: Int) = range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    Canvas(
        modifier
            .height(48.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onChange(at(it.x, size.width)) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x, size.width)) }
            },
    ) {
        val y = size.height / 2
        val x = size.width * fraction
        val track = 4.dp.toPx()
        drawLine(Palette.line, Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
        if (enabled) drawLine(Palette.gold, Offset(0f, y), Offset(x, y), track, StrokeCap.Round)
        drawCircle(if (enabled) Palette.gold else Palette.line, 9.dp.toPx(), Offset(x, y))
    }
}

@Composable
private fun BarAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(48.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
    ) {
        Text(text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

/** A cog: eight teeth round a ring. */
@Composable
fun GearIcon(modifier: Modifier = Modifier, color: Color = Palette.text, hole: Color = Palette.panel) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        val tooth = Size(r * 0.42f, r * 0.5f)
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawRect(color, Offset(c.x - tooth.width / 2, c.y - r), tooth)
            }
        }
        drawCircle(color, r * 0.74f, c)
        drawCircle(hole, r * 0.3f, c)
    }
}
