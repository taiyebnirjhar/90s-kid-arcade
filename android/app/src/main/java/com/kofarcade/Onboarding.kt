package com.kofarcade

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofarcade.core.Art
import com.kofarcade.core.BIOS
import com.kofarcade.core.GAMES
import com.kofarcade.core.Game
import com.kofarcade.core.gameForSet
import com.kofarcade.core.setName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who sent the app to the player (the welcome says so). */
const val INVITED_BY = "Taiyeb Nirjhor"

/** The first-run welcome's steps, in order. */
enum class OnboardStep { WELCOME, KEY, BIOS, RECORD, GAME }

/** The BIOS step: what's happening with neogeo.zip. */
sealed interface BiosState {
    /** In the library, not got yet ([mb] its size). */
    data class Ready(val mb: Float) : BiosState
    data class Getting(val fraction: Float) : BiosState
    data object Done : BiosState
    data class Failed(val why: String) : BiosState
}

/** A game in the library to pick on the last step. */
data class OnboardGame(val file: String, val game: Game, val set: String, val mb: Float)

/** Everything the welcome shows (plain data, so each step can be previewed). */
data class OnboardUi(
    val step: OnboardStep,
    val key: String = "",
    val keyError: String? = null,
    val checking: Boolean = false,
    val bios: BiosState = BiosState.Ready(1.9f),
    val games: List<OnboardGame> = emptyList(),
    val picked: Set<String> = emptySet(),
)

/** Whether this phone should see the welcome: never seen, and nothing on it yet (an old player skips it). */
fun needsOnboarding(prefs: Prefs, roms: RomStore): Boolean {
    if (prefs.onboarded) return false
    val fresh = !roms.has(BIOS) && GAMES.none { roms.setFor(it) != null } && prefs.libraryKey == null
    if (!fresh) prefs.onboarded = true
    return fresh
}

/**
 * The first run: a welcome from [INVITED_BY], the library key he gave,
 * the Neo Geo BIOS first (every Neo Geo game needs it), then the player's
 * favourite games, downloading in the background ([LibraryDownloads]) as
 * they start looking around. Every step can be skipped; it's seen once.
 */
@Composable
fun OnboardingFlow(prefs: Prefs, roms: RomStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = remember { LibraryDownloads.get(context) }
    val downloads by queue.state.collectAsState()
    val start = rememberDownloadStarter(queue)
    var step by rememberSaveable { mutableStateOf(OnboardStep.WELCOME) }
    var key by rememberSaveable { mutableStateOf(prefs.libraryKey.orEmpty()) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<Library.Entry>>(emptyList()) }
    var picked by rememberSaveable { mutableStateOf(emptySet<String>()) }

    fun finish() {
        prefs.onboarded = true
        onDone()
    }

    val bios = entries.filter { setName(it.name).startsWith("neogeo") }.minByOrNull { it.name.length }
    val games = entries.mapNotNull { e ->
        val set = setName(e.name).replace(Regex(""" \(\d+\)$"""), "")
        gameForSet(set)?.let { OnboardGame(e.name, it, set, e.size / 1_048_576f) }
    }.distinctBy { it.game.id }.sortedBy { GAMES.indexOf(it.game) }

    // The library's list, with the key: on to the BIOS (or past it, if it's here already or not in the library).
    fun unlock() {
        if (key.isBlank() || checking) return
        checking = true
        keyError = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Library(prefs.libraryUrl, key.trim()).list() }
                prefs.libraryKey = key.trim()
                entries = list
                val noBios = list.none { setName(it.name).startsWith("neogeo") }
                step = if (roms.has(BIOS) || noBios) OnboardStep.RECORD else OnboardStep.BIOS
            } catch (e: Library.WrongKey) {
                keyError = "That key didn't open the library. Check it with $INVITED_BY."
            } catch (e: Exception) {
                keyError = "Couldn't reach the library: check the internet and try again."
            } finally {
                checking = false
            }
        }
    }

    // Turned (or come back to) past the key step: the library's list again.
    LaunchedEffect(step) {
        if (step.ordinal > OnboardStep.KEY.ordinal && entries.isEmpty() && prefs.libraryKey != null) {
            withContext(Dispatchers.IO) { runCatching { Library(prefs.libraryUrl, prefs.libraryKey!!).list() } }.getOrNull()?.let { entries = it }
        }
    }

    // Back: a step back, never out of the app by mistake.
    BackHandler(enabled = step != OnboardStep.WELCOME) {
        step = OnboardStep.entries[step.ordinal - 1]
    }

    val biosState = when {
        bios == null -> BiosState.Done
        roms.has(BIOS) && downloads[bios.name]?.result == null -> BiosState.Done
        else -> downloads[bios.name].let { d ->
            when {
                d == null -> BiosState.Ready(bios.size / 1_048_576f)
                d.queued -> BiosState.Getting(0f)
                d.result == null -> BiosState.Getting(if (bios.size > 0) d.done.toFloat() / bios.size else 0f)
                d.ok -> BiosState.Done
                else -> BiosState.Failed(d.result)
            }
        }
    }
    OnboardingScreen(
        OnboardUi(step, key, keyError, checking, biosState, games.filter { roms.setFor(it.game) == null }, picked),
        onNext = { step = OnboardStep.entries[step.ordinal + 1] },
        onKey = {
            key = it
            keyError = null
        },
        onUnlock = ::unlock,
        onGetBios = { bios?.let { start(listOf(it)) } },
        onPick = { file -> picked = if (file in picked) picked - file else picked + file },
        onFinish = {
            start(entries.filter { it.name in picked })
            finish()
        },
        onSkip = ::finish,
        onSaveMatches = { on ->
            prefs.saveMatches = on
            step = OnboardStep.GAME
        },
    )
}

/** The welcome itself, from plain data (so it can be previewed). */
@Composable
fun OnboardingScreen(
    ui: OnboardUi,
    onNext: () -> Unit,
    onKey: (String) -> Unit,
    onUnlock: () -> Unit,
    onGetBios: () -> Unit,
    onPick: (String) -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    onSaveMatches: (Boolean) -> Unit = {},
) {
    val still = LocalInspectionMode.current || LocalStill.current
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.bg)
            .background(Brush.radialGradient(listOf(Palette.gold.copy(alpha = 0.12f), Color.Transparent))),
    ) {
        val short = maxHeight < 560.dp
        // The arcade's calls, scrolling by behind the welcome, as on the GAME ON screen.
        if (ui.step == OnboardStep.WELCOME) {
            val rowSize = minOf(maxWidth.value / 6f, maxHeight.value / 7f).coerceIn(36f, 96f).sp
            Column(Modifier.align(Alignment.Center).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarqueeRow("INSERT COIN · PLAY MORE · ", rowSize, still, 16_000, Color.White.copy(alpha = 0.05f))
                MarqueeRow("GAME ON · PRESS START · ", rowSize, still, 19_000, Palette.gold.copy(alpha = 0.09f), backwards = true)
                MarqueeRow("HERE COMES A NEW CHALLENGER · ", rowSize, still, 22_000, Color.White.copy(alpha = 0.05f))
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // Where you are: four dots, and Skip (the app works without any of it).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    for (s in OnboardStep.entries) {
                        Box(
                            Modifier.height(6.dp).width(if (s == ui.step) 22.dp else 6.dp).clip(CircleShape)
                                .background(if (s.ordinal <= ui.step.ordinal) Palette.gold else Palette.line),
                        )
                    }
                }
                if (ui.step != OnboardStep.WELCOME) {
                    Text(
                        "Skip", color = Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSkip).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            AnimatedContent(
                ui.step,
                transitionSpec = {
                    (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(200)))
                },
                label = "onboarding",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { step ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = if (short) 4.dp else 12.dp),
                        verticalArrangement = if (step == OnboardStep.GAME) Arrangement.Top else Arrangement.Center,
                    ) {
                        when (step) {
                            OnboardStep.WELCOME -> Welcome(short)
                            OnboardStep.KEY -> KeyStep(ui, onKey, onUnlock)
                            OnboardStep.BIOS -> BiosStep(ui.bios)
                            OnboardStep.RECORD -> RecordStep()
                            OnboardStep.GAME -> GameStep(ui, onPick)
                        }
                    }
                }
            }
            // The step's one main action, in thumb reach.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    when (ui.step) {
                        OnboardStep.WELCOME -> GoldButton("Let's go", onNext, Modifier.fillMaxWidth(), play = true)
                        OnboardStep.KEY -> GoldButton(if (ui.checking) "Checking…" else "Unlock my library", onUnlock, Modifier.fillMaxWidth(), enabled = ui.key.isNotBlank() && !ui.checking)
                        OnboardStep.BIOS -> when (val b = ui.bios) {
                            is BiosState.Ready -> GoldButton("Get the BIOS", onGetBios, Modifier.fillMaxWidth())
                            is BiosState.Getting -> GoldButton("Next", onNext, Modifier.fillMaxWidth())
                            BiosState.Done -> GoldButton("Next", onNext, Modifier.fillMaxWidth(), play = true)
                            is BiosState.Failed -> GoldButton("Try again", onGetBios, Modifier.fillMaxWidth())
                        }
                        OnboardStep.RECORD -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GoldButton("Yes, save my matches", { onSaveMatches(true) }, Modifier.fillMaxWidth())
                            GhostButton("Not now", { onSaveMatches(false) }, Modifier.fillMaxWidth())
                        }
                        OnboardStep.GAME -> GoldButton(
                            when (ui.picked.size) {
                                0 -> "Start playing"
                                1 -> "Get it and start"
                                else -> "Get ${ui.picked.size} and start"
                            },
                            onFinish, Modifier.fillMaxWidth(), play = true,
                        )
                    }
                }
                val hint = when (ui.step) {
                    OnboardStep.KEY -> "No key? Skip, and add your own game zips later."
                    OnboardStep.RECORD -> "You can change this any time in Settings."
                    OnboardStep.BIOS -> if (ui.bios is BiosState.Getting) "It keeps downloading while you go on." else null
                    OnboardStep.GAME -> if (ui.picked.isEmpty()) "You can get games any time from My Library." else "They download while you look around."
                    else -> null
                }
                hint?.let { Text(it, color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

// Step 1: who sent you, and what this is.
@Composable
private fun Welcome(short: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Taiyeb's photo in a gold ring (the welcome comes from a person), and the
        // arcade's pixel shades dropping onto his glasses.
        Box(Modifier.size(if (short) 68.dp else 88.dp).clip(CircleShape).background(Palette.panel).border(3.dp, Palette.gold, CircleShape)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.invited_by),
                contentDescription = INVITED_BY,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            PixelShades(Modifier.matchParentSize())
        }
        Spacer(Modifier.height(12.dp))
        Text("$INVITED_BY invited you", color = Palette.gold, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(if (short) 6.dp else 14.dp))
        Row {
            Text("90S KID ", color = Palette.text, fontFamily = ArcadeFont, fontSize = 26.sp)
            Text("ARCADE", color = Palette.gold, fontFamily = ArcadeFont, fontSize = 26.sp)
        }
        Text(
            "GAME ON",
            style = TextStyle(
                fontFamily = ArcadeFont, fontSize = if (short) 72.sp else 96.sp, textAlign = TextAlign.Center,
                brush = Brush.verticalGradient(listOf(Color(0xFFFFD45A), Palette.gold, Color(0xFFB8860B))),
            ),
        )
        Spacer(Modifier.height(if (short) 6.dp else 14.dp))
        Text(
            "The arcade classics from when you were a kid, on your phone: KOF, Metal Slug, Capcom's beat 'em ups.",
            color = Palette.text, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Perk("Specials in one tap, made for each game")
            Perk("Play friends online, or on the same Wi-Fi")
            Perk("Chat and voice, like the arcade next door")
        }
    }
}

@Composable
private fun Perk(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckIcon(Modifier.size(14.dp), color = Palette.gold)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.muted, fontSize = 14.sp)
    }
}

// Step 2: the library key he gave.
@Composable
private fun KeyStep(ui: OnboardUi, onKey: (String) -> Unit, onUnlock: () -> Unit) {
    StepHead("Step 2 of 5", "Your library key", "$INVITED_BY gave you a key to his game library. Type it here: it stays on this phone.") {
        LockIcon(Modifier.size(26.dp))
    }
    Spacer(Modifier.height(20.dp))
    ArcadeField(ui.key, onKey, "Library key", password = true, placeholder = "••••••••", onDone = { if (ui.key.isNotBlank()) onUnlock() })
    ui.keyError?.let {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Palette.red)
            Spacer(Modifier.width(8.dp))
            Text(it, color = Palette.red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Step 3: the Neo Geo BIOS first.
@Composable
private fun BiosStep(bios: BiosState) {
    StepHead(
        "Step 3 of 5", "First: the Neo Geo BIOS",
        "Every Neo Geo game (KOF, Metal Slug, Samurai Shodown, Garou…) needs this one small file to start. Get it first, and they all work.",
    ) { ChipIcon(Modifier.size(30.dp)) }
    Spacer(Modifier.height(20.dp))
    Panel(highlight = bios is BiosState.Getting || bios == BiosState.Done) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("neogeo.zip", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (bios) {
                        is BiosState.Ready -> "%.1f MB · not on this phone yet".format(bios.mb)
                        is BiosState.Getting -> "Downloading ${(bios.fraction * 100).toInt()}%"
                        BiosState.Done -> "On this phone: Neo Geo games are ready"
                        is BiosState.Failed -> bios.why
                    },
                    color = when (bios) {
                        BiosState.Done -> Palette.green
                        is BiosState.Failed -> Palette.red
                        is BiosState.Getting -> Palette.gold
                        else -> Palette.muted
                    },
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (bios == BiosState.Done) CheckIcon(Modifier.size(20.dp))
        }
        if (bios is BiosState.Getting) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(bios.fraction, thickness = 5.dp)
        }
    }
}

// Step 4: whether to save their matches (off unless they say so).
@Composable
private fun RecordStep() {
    StepHead(
        "Step 4 of 5", "Save your matches?",
        "The app can keep your last 20 matches on this phone. Watch the best ones again, and turn them into videos to share.",
    ) { RecordIcon(Modifier.size(26.dp)) }
    Spacer(Modifier.height(20.dp))
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Perk("Only your button presses are saved: almost no space")
            Perk("Never slows the game: the video is made later")
            Perk("Stays on your phone; you choose what to share")
        }
    }
}

// Step 5: their favourite games, from the library.
@Composable
private fun GameStep(ui: OnboardUi, onPick: (String) -> Unit) {
    StepHead("Step 5 of 5", "Your favourite game?", "Pick one or more. They download while you look around.", center = false)
    Spacer(Modifier.height(16.dp))
    if (ui.games.isEmpty()) {
        Text("Nothing more to get: you're all set.", color = Palette.muted, fontSize = 14.sp)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 420.dp) 3 else 2
        val gap = 12.dp
        val w = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ui.games.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { g -> PickCard(g, g.file in ui.picked, Modifier.width(w)) { onPick(g.file) } }
                }
            }
        }
    }
}

@Composable
private fun PickCard(g: OnboardGame, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape).background(Palette.panel)
                .border(if (on) 3.dp else 1.dp, if (on) Palette.gold else Color.White.copy(alpha = 0.08f), shape),
        ) {
            ArtImage(g.game, g.set, Art.TITLE, Modifier.matchParentSize(), contentScale = ContentScale.Crop, grey = !on)
            if (on) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Palette.gold),
                ) { CheckIcon(Modifier.size(12.dp), color = OnGold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(g.game.title, color = if (on) Palette.gold else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%.0f MB".format(g.mb.coerceAtLeast(1f)), color = Palette.muted, fontSize = 11.sp)
    }
}

// A step's heading: an icon, a kicker, a title, a line under it.
@Composable
private fun StepHead(kicker: String, title: String, body: String, center: Boolean = true, icon: (@Composable () -> Unit)? = null) {
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        if (icon != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp).clip(CircleShape).background(Palette.gold.copy(alpha = 0.14f))) { icon() }
            Spacer(Modifier.height(16.dp))
        }
        Kicker(kicker)
        Text(title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, textAlign = if (center) TextAlign.Center else TextAlign.Start)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Palette.muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start)
    }
}


// The pixel "deal with it" shades, as drawn on the 21 x 6 grid below ('#' black, 'o' a glint).
private val SHADES = listOf(
    "#####################",
    "..#oo#####.#oo#####..",
    "..#o######.#o######..",
    "..########.########..",
    "...######...######...",
    "....####.....####....",
)

/**
 * Pixel shades over the photo's glasses (where they are in invited_by.jpg:
 * 28-72% across, 32.5-45% down), dropping into place once, as in the meme.
 */
@Composable
private fun PixelShades(modifier: Modifier) {
    val still = LocalInspectionMode.current || LocalStill.current
    val drop = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) {
            kotlinx.coroutines.delay(500)
            drop.animateTo(1f, androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val cols = SHADES[0].length
        val cell = size.width * 0.44f / cols
        val left = size.width * 0.5f - cell * cols / 2f
        val top = size.height * 0.325f
        // From above the picture, down onto the eyes.
        val y0 = top - (1f - drop.value) * (top + cell * SHADES.size)
        for ((r, line) in SHADES.withIndex()) {
            for ((c, ch) in line.withIndex()) {
                val color = when (ch) {
                    '#' -> Color.Black
                    'o' -> Color.White
                    else -> continue
                }
                // A hair over a cell, so no seams show between the pixels.
                drawRect(color, androidx.compose.ui.geometry.Offset(left + c * cell, y0 + r * cell), androidx.compose.ui.geometry.Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}
