package com.kofarcade.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a game's controls are laid out. */
enum class Layout {
    /** Stick, A B C D, power button, character move ring, team bar. */
    FIGHTER,

    /** Stick and big labelled buttons; holding Shoot keeps firing. */
    SHOOTER,

    /** Stick and four labelled buttons, shared special moves. */
    BRAWLER,
}

@Serializable
data class Move(
    val name: String,
    val cmd: String,
    val type: String = "special",
    val note: String = "",
) {
    /** Desperation moves (DM, SDM, HSDM) get a hotter look than plain specials. */
    val isSuper: Boolean get() = type != "special"

    /**
     * Only comes out while jumping (its note says "in air"; "also in air"
     * moves work on the ground too): the button jumps first if needed.
     */
    val airOnly: Boolean
        get() = note.split(';').any { part ->
            AIR.containsMatchIn(part) && "also" !in part && "ground" !in part
        }

    /** "Ura 108 Shiki: Orochinagi" -> "Orochinagi": the part people call it. */
    val shortName: String get() = name.substringAfterLast(": ")
}

private val AIR = Regex("""\bin (the )?air\b""", RegexOption.IGNORE_CASE)

/**
 * A supported game.
 *
 * [sets] are the FBNeo ROM set names (zip names) accepted for it; the first is
 * the name a differently named zip is stored as.
 */
data class Game(
    val id: String,
    val series: String,
    val title: String,
    val sets: List<String>,
    val layout: Layout,
    val subtitle: String? = null,
    val labels: Map<Char, String> = emptyMap(),
    val power: Move? = null,
    val system: List<Move> = emptyList(),
    val moves: List<Move> = emptyList(),
    val roster: String? = null,
    val team: Int = 0,
    val rapid: Key? = null,
    /** The game's name in libretro's thumbnail collection (FBNeo arcade DAT description). */
    val art: String? = null,
    /** Shows a Block button (hold = back, slide down = low block). */
    val block: Boolean = false,
    /** The game runs on a double-tap forward: shows a Run button and runs when the stick is pushed to the edge. */
    val run: Boolean = false,
    /** A Neo Geo game: needs the BIOS (neogeo.zip). Capcom's CPS-1 games don't. */
    val neoGeo: Boolean = true,
) {
    /** "KOF 2002 Magic Plus": the title with its subtitle, where one name is needed. */
    val fullTitle: String get() = listOfNotNull(title, subtitle).joinToString(" ")

    /** Face buttons shown: only the labelled ones, unless it's a fighter (A B C D all have jobs). */
    val faceButtons: List<Char>
        get() = if (layout != Layout.FIGHTER && labels.isNotEmpty()) BUTTONS.filter { it in labels }.toList() else BUTTONS.toList()
}

const val BIOS = "neogeo.zip"

private val KOF_SYSTEM = listOf(Move("Roll", "AB"), Move("Blowback", "CD"))

val GAMES: List<Game> = listOf(
    Game(
        id = "kof97", series = "The King of Fighters", title = "KOF '97", sets = listOf("kof97"),
        art = "The King of Fighters '97 (NGM-2320)",
        layout = Layout.FIGHTER, power = Move("MAX", "ABC"), system = KOF_SYSTEM, roster = "kof97", team = 3, block = true,
    ),
    Game(
        id = "kof2002", series = "The King of Fighters", title = "KOF 2002", sets = listOf("kof2002"),
        art = "The King of Fighters 2002 (NGM-2650 ~ NGH-2650)",
        layout = Layout.FIGHTER, power = Move("MAX", "BC"), system = KOF_SYSTEM, roster = "kof2002", team = 3, block = true,
    ),
    // Hacks with their own cards: the same fighters, some moves changed
    // (their move lists were checked in the real games like the originals').
    Game(
        id = "kof97plus", series = "The King of Fighters", title = "KOF '97 Plus", sets = listOf("kof97pls"),
        art = "The King of Fighters '97 Plus (bootleg)",
        layout = Layout.FIGHTER, power = Move("MAX", "ABC"), system = KOF_SYSTEM, roster = "kof97plus", team = 3, block = true,
    ),
    Game(
        id = "kof2002mp", series = "The King of Fighters", title = "KOF 2002", subtitle = "Magic Plus",
        art = "The King of Fighters 2002 Magic Plus (bootleg, set 1)",
        sets = listOf("kf2k2mp", "kf2k2mpl", "kf2k2mp2"),
        layout = Layout.FIGHTER, power = Move("MAX", "BC"), system = KOF_SYSTEM, roster = "kof2002mp", team = 3, block = true,
    ),
    // There is no official Neo Geo KOF 2005: "KOF 2005" is the 10th
    // Anniversary hacks of KOF 2002. Its move list is 2002's with the inputs
    // 2005 Unique changed (simpler hidden supers, a few moved specials).
    Game(
        id = "kof2005", series = "The King of Fighters", title = "KOF 2005", subtitle = "10th Anniversary",
        // Each hack has its own title screen; the card shows the one you
        // have (artUrl), this one until then.
        art = "The King of Fighters 10th Anniversary 2005 Unique (bootleg of The King of Fighters 2002)",
        sets = listOf("kf2k5uni", "kof10th", "kf10thep"),
        layout = Layout.FIGHTER, power = Move("MAX", "BC"), system = KOF_SYSTEM, roster = "kof2005", team = 3, block = true,
    ),
) + listOf(
    Triple("mslug", "Metal Slug", "Metal Slug - Super Vehicle-001"),
    Triple("mslug2", "Metal Slug 2", "Metal Slug 2 - Super Vehicle-001/II (NGM-2410 ~ NGH-2410)"),
    Triple("mslugx", "Metal Slug X", "Metal Slug X - Super Vehicle-001 (NGM-2500 ~ NGH-2500)"),
    Triple("mslug3", "Metal Slug 3", "Metal Slug 3 (NGM-2560)"),
    Triple("mslug4", "Metal Slug 4", "Metal Slug 4 (NGM-2630)"),
    Triple("mslug5", "Metal Slug 5", "Metal Slug 5 (NGM-2680)"),
).map { (set, title, art) ->
    val hasSlugAttack = set == "mslug4" || set == "mslug5"
    Game(
        id = set, series = "Metal Slug", title = title, sets = listOf(set), layout = Layout.SHOOTER, art = art,
        // 4 and 5 add D: the "Metal Slug Attack" while in a vehicle.
        labels = mapOf('A' to "Shoot", 'B' to "Jump", 'C' to "Bomb") +
            (if (hasSlugAttack) mapOf('D' to "Slug Attack") else emptyMap()),
        // Holding Shoot toggles it every 2 frames: in Metal Slug X that fires
        // 20 shots in 2 seconds, where a held button fires once and toggling
        // every frame fires none (tools/emu.py).
        rapid = Key.A,
        // Metal Slug 5's slide: down + jump.
        system = if (set == "mslug5") listOf(Move("Slide", "2+B")) else emptyList(),
    )
} + Game(
    id = "sengoku3", series = "Sengoku", title = "Sengoku 3", sets = listOf("sengoku3"), layout = Layout.BRAWLER,
    art = "Sengoku 3 / Sengoku Densho 2001 (set 1)",
    labels = mapOf('A' to "Weapon", 'B' to "Punch", 'C' to "Jump", 'D' to "Throw"),
    // Inputs every Sengoku 3 character shares.
    moves = listOf(Move("Special 1", "22+A"), Move("Special 2", "22+B"), Move("Super", "28+AB", type = "dm")),
    run = true,
) + Game(
    // Called "Mostofa" in Bangladesh, after its fighter Mustapha Cairo. Capcom
    // CPS-1: no BIOS. Checked in FBNeo (tools/emu.py): A attacks, B jumps,
    // A+B is the spinning special, a double-tap forward runs.
    id = "dino", series = "Capcom", title = "Cadillacs and Dinosaurs", sets = listOf("dino"),
    layout = Layout.BRAWLER, art = "Cadillacs and Dinosaurs (World 930201)",
    labels = mapOf('A' to "Attack", 'B' to "Jump"),
    moves = listOf(Move("Special", "AB", type = "dm")),
    run = true,
    neoGeo = false,
)

fun gameById(id: String): Game? = GAMES.find { it.id == id }

/** Kinds of picture in libretro's thumbnail collection. */
enum class Art(val folder: String) { TITLE("Named_Titles"), SNAP("Named_Snaps"), BOXART("Named_Boxarts") }

/**
 * Where a game's picture lives in libretro's FBNeo thumbnail collection (the
 * set RetroArch and Lemuroid use). Characters libretro can't use in file
 * names become "_"; the rest is URL-encoded.
 */
fun artUrl(game: Game, kind: Art, set: String? = null): String? {
    // A version listed in the game's sets has its own picture, under the
    // name FBNeo gives it (the thumbnails are named after FBNeo's list).
    val name = set?.takeIf { it in game.sets }?.let { romSet(it)?.title } ?: game.art ?: return null
    val file = name.map { if (it in "&*/:`<>?\\|\"") '_' else it }.joinToString("")
    val encoded = java.net.URLEncoder.encode("$file.png", "UTF-8").replace("+", "%20")
    return "https://raw.githubusercontent.com/libretro-thumbnails/FBNeo_-_Arcade_Games/master/${kind.folder}/$encoded"
}

/** "KOF97.zip" -> "kof97". */
fun setName(fileName: String): String = fileName.lowercase().removeSuffix(".zip")

/** The game a ROM zip belongs to, by file name. */
fun gameForFile(fileName: String): Game? = setName(fileName).let { set -> GAMES.find { set in it.sets } }

// ----- Rosters -----

@Serializable
data class Character(
    val id: String,
    val name: String,
    val team: String = "Other",
    val moves: List<Move> = emptyList(),
)

@Serializable
data class Roster(val game: String, val characters: List<Character>) {
    fun character(id: String?): Character? = characters.find { it.id == id }

    /** Teams in roster order, each with its members. */
    val teams: List<Pair<String, List<Character>>>
        get() = characters.groupBy { it.team }.toList()
}

private val json = Json { ignoreUnknownKeys = true }

private val rosterCache = mutableMapOf<String, Roster?>()

/** Loads a character list bundled with the app (resources/rosters/<name>.json). */
fun loadRoster(name: String): Roster? = rosterCache.getOrPut(name) {
    Game::class.java.getResourceAsStream("/rosters/$name.json")
        ?.bufferedReader()
        ?.use { json.decodeFromString(Roster.serializer(), it.readText()) }
}
