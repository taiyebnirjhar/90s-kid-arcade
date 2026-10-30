package com.kofarcade

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// The app's own look, shared by every screen: dark panels, gold for what you
// can do, italic heavy titles like the home screen's logo.

val OnGold = Color(0xFF1D1400)

/**
 * A full-screen page over the home screen: back button, title, then [content].
 * [bottomBar] pins the page's main action to the bottom, in thumb reach.
 */
@Composable
fun ArcadeScreen(
    title: String,
    onBack: () -> Unit,
    kicker: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // imePadding: the screen moves up with the keyboard (edge to edge, nothing else does).
    Column(Modifier.fillMaxSize().background(Palette.bg).systemBarsPadding().imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Palette.panel)
                    .border(1.dp, Palette.line, CircleShape).clickable(onClick = onBack)
                    .semantics { contentDescription = "Back"; role = Role.Button },
            ) { BackIcon(Modifier.size(14.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (kicker != null) Kicker(kicker)
                Text(title, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    // Room under the last row to scroll it clear of the bar.
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (bottomBar != null) 96.dp else 8.dp),
                content = content,
            )
            if (bottomBar != null) {
                // The page fades out under the bar, so cards scroll behind it instead of being cut.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Palette.bg.copy(alpha = 0f), 0.35f to Palette.bg))
                        .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                ) { Box(Modifier.widthIn(max = 560.dp)) { bottomBar() } }
            }
        }
    }
}

/** Small gold capitals above a title: "CONTINUE", "STEP 1". */
@Composable
fun Kicker(text: String, color: Color = Palette.gold) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
}

/** A section heading, like the home screen's shelves. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Palette.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = modifier.padding(top = 24.dp, bottom = 12.dp),
    )
}

/** A rounded panel; gold-edged when [highlight], tappable with [onClick]. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) Palette.gold.copy(alpha = 0.08f) else Palette.panel)
            .border(1.dp, if (highlight) Palette.gold.copy(alpha = 0.7f) else Palette.line, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content,
    )
}

/** The main action: a gold pill. [compact] for one on a game card. */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    play: Boolean = false,
    compact: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .pressScale(source)
            .height(if (compact) 40.dp else 48.dp)
            .clip(RoundedCornerShape(50))
            // Disabled: a quiet grey pill, not a dimmed (muddy) gold one.
            .background(if (enabled) Palette.gold else Palette.line)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 22.dp),
    ) {
        val ink = if (enabled) OnGold else Palette.muted
        if (play) {
            PlayIcon(Modifier.size(if (compact) 10.dp else 12.dp), ink)
            Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        }
        Text(text.uppercase(), color = ink, fontWeight = FontWeight.Black, fontSize = if (compact) 12.sp else 14.sp, letterSpacing = 1.sp)
    }
}

/** A second choice beside a [GoldButton]: an outlined pill. [danger] for removing things. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, compact: Boolean = false) {
    val color = if (danger) Palette.red else Palette.text
    val source = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pressScale(source)
            .height(if (compact) 40.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (danger) Palette.red.copy(alpha = 0.6f) else Palette.line, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 20.dp),
    ) {
        Text(text.uppercase(), color = color, fontWeight = FontWeight.Black, fontSize = if (compact) 12.sp else 13.sp, letterSpacing = 1.sp)
    }
}

/** A small rounded tag: "ON PHONE", "2P". */
@Composable
fun Tag(text: String, color: Color = Palette.muted, filled: Boolean = false) {
    Text(
        text.uppercase(),
        color = if (filled) OnGold else color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** A tag on a game picture: a dark pill with a coloured dot, readable on any picture. */
@Composable
fun PictureTag(text: String, color: Color = Palette.gold, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Shrinks a little while [source] is pressed, like a console tile. Give the click the same source. */
fun Modifier.pressScale(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Whether [source] is pressed or focused (a gamepad or TV remote moves the focus): when a card lights up. */
@Composable
fun lit(source: InteractionSource): Boolean {
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    return pressed || focused
}

/**
 * An empty, first-run or error state: a round mark (the [tone]'s, or [icon]),
 * a title, a line of help, then what to do about it.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.INFO,
    icon: (@Composable () -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(tone.color.copy(alpha = 0.14f)),
        ) { if (icon != null) icon() else ToneIcon(tone, Modifier.size(24.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Palette.muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            GoldButton(action.first, action.second)
        }
    }
}

/** A grey stand-in block while something loads, gently pulsing (still in screenshots). */
@Composable
fun Skeleton(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        1f, 0.5f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeleton",
    )
    Box(modifier.alpha(pulse).clip(shape).background(Palette.panel))
}

enum class Tone(val color: Color) { INFO(Palette.gold), GOOD(Palette.green), BAD(Palette.red) }

/**
 * The app's dialog: a panel with a coloured mark for its [tone], a title,
 * a message, then up to two actions (gold first).
 */
@Composable
fun ArcadeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    tone: Tone = Tone.INFO,
    dangerSecondary: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TextScaleCap { DialogCard(title, message, primary, secondary, tone, dangerSecondary) }
    }
}

/** The card inside [ArcadeDialog], on its own for previews. */
@Composable
fun DialogCard(
    title: String,
    message: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    tone: Tone = Tone.INFO,
    dangerSecondary: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Palette.panel,
        border = BorderStroke(1.dp, Palette.line),
        modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(tone.color.copy(alpha = 0.16f)),
            ) { ToneIcon(tone, Modifier.size(18.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(title, color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Palette.muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GoldButton(primary.first, primary.second, Modifier.fillMaxWidth())
                if (secondary != null) GhostButton(secondary.first, secondary.second, Modifier.fillMaxWidth(), danger = dangerSecondary)
            }
        }
    }
}

/** Pick one of a few: pills in a row (wrapping), the chosen one gold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                color = if (on) OnGold else Palette.text,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Black else FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.gold else Palette.bg)
                    .border(1.dp, if (on) Palette.gold else Palette.line, RoundedCornerShape(50))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** A setting that's on or off, with an optional line of explanation. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Palette.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnGold,
                checkedTrackColor = Palette.gold,
                uncheckedThumbColor = Palette.muted,
                uncheckedTrackColor = Palette.bg,
                uncheckedBorderColor = Palette.line,
            ),
        )
    }
}

/** A slider with its label and current value on one line above it. */
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, color = Palette.gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Palette.gold,
                activeTrackColor = Palette.gold,
                inactiveTrackColor = Palette.line,
            ),
        )
    }
}

/** A number with − and + buttons. */
@Composable
fun StepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Palette.muted, fontSize = 12.sp)
        }
        RoundKey("−", enabled = value > range.first) { onChange(value - 1) }
        Text("$value", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundKey("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun RoundKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).background(Palette.bg)
            .border(1.dp, Palette.line, CircleShape).clickable(enabled = enabled, onClick = onClick),
    ) { Text(text, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

/** A text field in the app's style: label above, dark well, gold edge when focused-free simple. */
@Composable
fun ArcadeField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** The keyboard's Done key does this (Join, Unlock, Save…); null: it just closes the keyboard. */
    onDone: (() -> Unit)? = null,
) {
    // A password can be shown, to check what's typed.
    var shown by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(modifier.fillMaxWidth()) {
        Text(label, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.bg)
                .border(1.dp, Palette.line, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = if (password) 6.dp else 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = Palette.muted.copy(alpha = 0.6f), fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Palette.gold),
                    visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Go else androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onDone?.invoke() ?: focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (password) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).clickable { shown = !shown }
                        .semantics { contentDescription = if (shown) "Hide what's typed" else "Show what's typed"; role = Role.Button },
                ) {
                    EyeIcon(Modifier.size(20.dp), crossed = !shown)
                }
            }
        }
    }
}

/** A drawn eye: tap it to show what's typed; crossed out while it's hidden. */
@Composable
fun EyeIcon(modifier: Modifier = Modifier, crossed: Boolean = false, color: Color = Palette.muted) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        // The lids: two arcs meeting at the corners.
        drawPath(
            Path().apply {
                moveTo(w * 0.04f, h * 0.5f)
                quadraticBezierTo(w * 0.5f, h * 0.02f, w * 0.96f, h * 0.5f)
                quadraticBezierTo(w * 0.5f, h * 0.98f, w * 0.04f, h * 0.5f)
                close()
            },
            color,
            style = stroke,
        )
        drawCircle(color, radius = w * 0.14f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f))
        if (crossed) {
            drawLine(
                color,
                androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.1f),
                androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.9f),
                w * 0.09f,
                StrokeCap.Round,
            )
        }
    }
}

/** A thin progress bar in gold. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, thickness: Dp = 6.dp, track: Color = Palette.line) {
    Box(modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(thickness).clip(RoundedCornerShape(50)).background(Palette.gold))
    }
}

/** A dot that says "working on it" without a spinner's motion (still in screenshots). */
@Composable
fun StatusDot(color: Color = Palette.gold) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

// ----- Icons, drawn so they look the same on every phone -----

/**
 * A drawn play triangle. The "▶" character sits off-centre in most fonts.
 * This one leans slightly right of its box, so it looks centred in a circle.
 */
@Composable
fun PlayIcon(modifier: Modifier = Modifier, color: Color = OnGold) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.12f, 0f)
                lineTo(w * 1.02f, h / 2)
                lineTo(w * 0.12f, h)
                close()
            },
            color,
        )
    }
}

/** A drawn tick, for a step that's done. */
@Composable
fun CheckIcon(modifier: Modifier = Modifier, color: Color = Palette.green) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.12f, h * 0.55f)
                lineTo(w * 0.4f, h * 0.82f)
                lineTo(w * 0.9f, h * 0.2f)
            },
            color,
            style = Stroke(width = w * 0.16f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier, color: Color = Palette.text) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.72f, 0f)
                lineTo(w * 0.22f, h / 2)
                lineTo(w * 0.72f, h)
            },
            color,
            style = Stroke(width = w * 0.16f, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun ToneIcon(tone: Tone, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.14f, cap = StrokeCap.Round)
        when (tone) {
            Tone.GOOD -> drawPath(
                Path().apply {
                    moveTo(w * 0.12f, h * 0.55f)
                    lineTo(w * 0.4f, h * 0.82f)
                    lineTo(w * 0.9f, h * 0.2f)
                },
                tone.color,
                style = stroke,
            )
            Tone.BAD -> {
                drawLine(tone.color, androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.2f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.8f), w * 0.14f, StrokeCap.Round)
                drawLine(tone.color, androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.2f), androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.8f), w * 0.14f, StrokeCap.Round)
            }
            Tone.INFO -> {
                drawLine(tone.color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.6f), w * 0.14f, StrokeCap.Round)
                drawCircle(tone.color, w * 0.09f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.86f))
            }
        }
    }
}

/**
 * [text] on one line, made smaller (down to 12 sp) until it fits the width:
 * for codes and keys, which mustn't break over two lines on a narrow phone
 * or with big text.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val max = constraints.maxWidth
        val fitted = remember(text, style, max, measurer) {
            var s = style
            while (s.fontSize.value > 12f && measurer.measure(text, s, softWrap = false, maxLines = 1).size.width > max) {
                s = s.copy(fontSize = s.fontSize * 0.9f, letterSpacing = s.letterSpacing * 0.9f)
            }
            s
        }
        Text(text, style = fitted, maxLines = 1, softWrap = false)
    }
}
