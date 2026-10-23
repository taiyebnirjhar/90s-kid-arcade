package com.kofarcade.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a game's controls are laid out. */
enum class Layout {
    /** Stick, A B C D, power button, character move ring, team bar. */
    FIGHTER,

    /** Stick and big labelled buttons, rapid fire. */
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

    /** "Ura 108 Shiki: Orochinagi" -> "Orochinagi": the part people call it. */
    val shortName: String get() = name.substringAfterLast(": ")
}

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
) {
    /** Face buttons shown: shooters only show the buttons that have a job. */
    val faceButtons: List<Char>
        get() = if (layout == Layout.SHOOTER) BUTTONS.filter { it in labels }.toList() else BUTTONS.toList()
}

const val BIOS = "neogeo.zip"

private val KOF_SYSTEM = listOf(Move("Roll", "AB"), Move("Blowback", "CD"))

val GAMES: List<Game> = listOf(
    Game(
        id = "kof97", series = "The King of Fighters", title = "KOF '97", sets = listOf("kof97"),
        layout = Layout.FIGHTER, power = Move("MAX", "ABC"), system = KOF_SYSTEM, roster = "kof97", team = 3,
    ),
    Game(
        id = "kof2002", series = "The King of Fighters", title = "KOF 2002", sets = listOf("kof2002"),
        layout = Layout.FIGHTER, power = Move("MAX", "BC"), system = KOF_SYSTEM, roster = "kof2002", team = 3,
    ),
    // There is no official Neo Geo KOF 2005: "KOF 2005" is the 10th
    // Anniversary hacks of KOF 2002, which play like 2002.
    Game(
        id = "kof2005", series = "The King of Fighters", title = "KOF 2005", subtitle = "10th Anniversary",
        sets = listOf("kf2k5uni", "kof10th", "kf10thep"),
        layout = Layout.FIGHTER, power = Move("MAX", "BC"), system = KOF_SYSTEM, roster = "kof2002", team = 3,
    ),
) + listOf(
    "mslug" to "Metal Slug",
    "mslug2" to "Metal Slug 2",
    "mslugx" to "Metal Slug X",
    "mslug3" to "Metal Slug 3",
    "mslug4" to "Metal Slug 4",
    "mslug5" to "Metal Slug 5",
).map { (set, title) ->
    val hasSlugAttack = set == "mslug4" || set == "mslug5"
    Game(
        id = set, series = "Metal Slug", title = title, sets = listOf(set), layout = Layout.SHOOTER,
        // 4 and 5 add D: the "Metal Slug Attack" while in a vehicle.
        labels = mapOf('A' to "Shoot", 'B' to "Jump", 'C' to "Bomb") +
            (if (hasSlugAttack) mapOf('D' to "Slug Attack") else emptyMap()),
        rapid = Key.A,
        // Metal Slug 5's slide: down + jump.
        system = if (set == "mslug5") listOf(Move("Slide", "2+B")) else emptyList(),
    )
} + Game(
    id = "sengoku3", series = "Sengoku", title = "Sengoku 3", sets = listOf("sengoku3"), layout = Layout.BRAWLER,
    labels = mapOf('A' to "Weapon", 'B' to "Punch", 'C' to "Jump", 'D' to "Throw"),
    // Inputs every Sengoku 3 character shares.
    moves = listOf(Move("Special 1", "22+A"), Move("Special 2", "22+B"), Move("Super", "28+AB", type = "dm")),
)

fun gameById(id: String): Game? = GAMES.find { it.id == id }

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
