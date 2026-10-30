package com.kofarcade.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RetroPad button ids (0 B, 1 Y, 8 A, 9 X, 10 L, 11 R) for A-F, as FBNeo's
 * libretro core lays out each arcade board. Checked by pressing each one in
 * the games (tools/emu.py).
 */
enum class PadMap(private val ids: IntArray) {
    /** Neo Geo A B C D (and the CPS-1 games, whose two buttons are A and B). */
    NEO_GEO(intArrayOf(0, 8, 1, 9, -1, -1)),

    /** Capcom six buttons: LP MP HP on Y X L, LK MK HK on B A R (Street Fighter III 3rd Strike). */
    CAPCOM_6(intArrayOf(1, 9, 10, 0, 8, 11));

    /** The RetroPad id for button [index] (0 = A ... 5 = F), or -1 if the game has none. */
    fun id(index: Int): Int = ids[index]
}

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

    /** Street Fighter III: which Super Art (1-3) this is, from its note ("Super Art II"), or null. */
    val superArt: Int?
        get() = SUPER_ART.find(note)?.groupValues?.get(1)?.length

    /** "Ura 108 Shiki: Orochinagi" -> "Orochinagi": the part people call it. */
    val shortName: String get() = name.substringAfterLast(": ")
}

private val AIR = Regex("""\bin (the )?air\b""", RegexOption.IGNORE_CASE)
private val SUPER_ART = Regex("""^Super Art (I{1,3})\b""")

/**
 * Where a game keeps whether each player is in: one status byte per seat, at
 * these offsets into the emulator's system RAM (as libretro hands it out),
 * and the values that mean the seat is free: the player never started, or
 * their game is over and its continue countdown ran out. A watcher's COIN or
 * START takes such a seat, as on the cabinet. Found by watching the bytes in
 * FBNeo (tools/emu.py) through start, continue and game over.
 */
class SeatStatus(
    val offsets: List<Int>,
    private val free: Set<Int>,
    /**
     * For a game whose byte only says "in the fight" (it drops when the
     * continue countdown starts, not when it ends): how long the game's own
     * countdown lasts, measured in the game. The seat is free once the byte
     * has said "out" that long. 0: the byte itself says game over.
     */
    val outForMs: Long = 0,
    /**
     * For a game whose attract demo sets the players' bytes too (Snow Bros.,
     * Sunset Riders): a byte that says whether a real game is on, which it is
     * while the byte reads one of [inGame]. Otherwise every seat counts as out.
     */
    val game: Int? = null,
    private val inGame: Set<Int> = emptySet(),
) {
    fun isFree(value: Int): Boolean = value in free

    /** Whether [seat]'s player is out now, reading the game's bytes (by offset) with [read]; null if it can't say. */
    fun isOut(seat: Int, read: (Int) -> Int?): Boolean? {
        val offset = offsets.getOrNull(seat) ?: return null
        if (game != null) {
            val on = read(game) ?: return null
            if (on !in inGame) return true
        }
        return read(offset)?.let(::isFree)
    }
}

/**
 * Follows a game's seat bytes over time ([sample] them every half second or
 * so): for a game with [SeatStatus.outForMs], a seat is free once its byte
 * has said "out" for that long; otherwise as soon as it says so.
 */
class SeatWatch(private val status: SeatStatus) {
    private val outSince = LongArray(status.offsets.size) { -1 }

    /** [out]: the game says [seat]'s player is out now ([SeatStatus.isOut]); returns whether the seat is free. */
    @Synchronized
    fun sample(seat: Int, out: Boolean, nowMs: Long): Boolean {
        if (seat !in outSince.indices) return false
        if (!out) {
            outSince[seat] = -1
            return false
        }
        // (Game time starts again at each new game: a time before the last one starts over.)
        if (outSince[seat] < 0 || nowMs < outSince[seat]) outSince[seat] = nowMs
        return nowMs - outSince[seat] >= status.outForMs
    }
}

/**
 * Every Neo Geo game: the BIOS's player status (BIOS_PLAYER_MOD1 and 2 at
 * 0x10FDB6, 0x10FDB7; 0 never played, 1 playing, 2 continue, 3 game over),
 * which the system requires games to keep. Checked in KOF '97 (1, then 2 for
 * the countdown, then 3 when it ran out) and Metal Slug X. The RAM's bytes
 * are swapped in pairs, so P1's is at offset 0xFDB7.
 */
val NEO_GEO_SEATS = SeatStatus(listOf(0xFDB7, 0xFDB6), setOf(0, 3))

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
    /** How many can play at once, as on the arcade cabinet: one phone each. */
    val players: Int = 2,
    /** Which emulator button each of A-F is (FBNeo's layout for the board). */
    val pad: PadMap = PadMap.NEO_GEO,
    /** Where the game says each player is in or out, for seats in a room; null: not known. */
    val seats: SeatStatus? = if (neoGeo) NEO_GEO_SEATS else null,
    /**
     * The cabinet's settings (DIP switches) the game needs, by FBNeo's name:
     * "Number_of_Players" to "4". Every phone sets the same, for every version.
     */
    val dips: Map<String, String> = emptyMap(),
    /** A cabinet with no START button (players join with a fire button): the button START presses. */
    val startWith: Char? = null,
) {
    /** The emulator's options for version [set]: its DIP switches ([dips]). */
    fun coreOptions(set: String): Map<String, String> = dips.entries.associate { (name, value) -> "fbneo-dipswitch-$set-$name" to value }

    /** "KOF 2002 Magic Plus": the title with its subtitle, where one name is needed. */
    val fullTitle: String get() = listOfNotNull(title, subtitle).joinToString(" ")

    /** Face buttons shown: only the labelled ones, unless it's a fighter (A B C D all have jobs). */
    val faceButtons: List<Char>
        get() = if (labels.isNotEmpty() && (layout != Layout.FIGHTER || pad == PadMap.CAPCOM_6)) {
            BUTTONS.filter { it in labels }.toList()
        } else {
            BUTTONS.take(4).toList()
        }
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
    labels = mapOf('A' to "Weapon", 'B' to "Punch", 'C' to "Jump", 'D' to "Shuriken"),
    // Every character uses the same inputs (the English AES manual; SNK's
    // Virtual Console page): Ninja Arts 1 and 2, Ninja Art 3 while holding
    // an enemy (Kagetsura's Shrike Drop carries them into the sky, Kongoh
    // slams them; the others have none), the Ultimate with the gauge full,
    // and A+B+C, which swaps you for a log to escape at the cost of some
    // life. Checked in FBNeo: 22+A, 22+B, 28+AB and A+B+C (the log).
    moves = listOf(
        Move("Ninja Art 1", "22+A"),
        Move("Ninja Art 2", "22+B"),
        Move("Ninja Art 3", "28+B", note = "while holding an enemy: Kagetsura and Kongoh"),
        Move("Ultimate", "28+AB", type = "dm", note = "gauge full"),
        Move("Log escape", "ABC", note = "costs some life"),
    ),
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
    players = 3,
    // Each player's state, 0x180 apart (68000 RAM 0xFFB316 on): 0 out, 4
    // choosing a fighter, 6 playing, 8 continue countdown, back to 0 when it ran out.
    seats = SeatStatus(listOf(0xB317, 0xB497, 0xB617), setOf(0)),
) + Game(
    // Neo Geo, 2000: a graveyard platformer for two. Checked in FBNeo
    // (tools/emu.py): A throws the lantern's fire, B jumps, C fires too, D
    // does nothing. A held button fires once; quick presses keep the flames
    // coming, so Fire has rapid fire like Metal Slug's Shoot.
    id = "nitd", series = "Nightmare in the Dark", title = "Nightmare in the Dark", sets = listOf("nitd"),
    layout = Layout.SHOOTER, art = "Nightmare in the Dark",
    labels = mapOf('A' to "Fire", 'B' to "Jump"),
    rapid = Key.A,
) + Game(
    // Subsino, 2000, on Seta 2 hardware: no BIOS. Penguins clear each round
    // with bombs, for two. Checked in FBNeo (tools/emu.py): A drops a bomb,
    // B jumps up to the floor above. Only one bomb is out at a time, held or
    // tapped, so Bomb has no rapid fire. Hong Tian Lei (ablast) is the same
    // game with its own title screen.
    id = "penbros", series = "Penguin Brothers", title = "Penguin Brothers", sets = listOf("penbros", "ablast"),
    layout = Layout.SHOOTER, art = "Penguin Brothers (Japan)",
    labels = mapOf('A' to "Bomb", 'B' to "Jump"),
    neoGeo = false,
    // Each player's state: 0 out, 2 playing, 4 continue countdown, 6 game over.
    seats = SeatStatus(listOf(0x35B2, 0x362E), setOf(0, 6)),
) + Game(
    // Toaplan, 1990 (Kaneko Pandora board): no BIOS. Checked in FBNeo
    // (tools/emu.py): A throws snow, B jumps, C does nothing; a held A throws
    // once, so it has rapid fire. Runs at 57.5 frames a second.
    id = "snowbros", series = "Snow Bros.", title = "Snow Bros.", subtitle = "Nick & Tom",
    // The bootlegs on other boards (The Winter Bobble) keep their RAM elsewhere: not listed.
    sets = listOf("snowbros", "snowbrosa", "snowbrosb", "snowbrosc", "snowbrosd", "snowbrosj"),
    layout = Layout.SHOOTER, art = "Snow Bros. - Nick & Tom (set 1)",
    labels = mapOf('A' to "Snow", 'B' to "Jump"),
    rapid = Key.A,
    neoGeo = false,
    // Each player's "in the game" byte (68000 RAM 0x40 and 0x4A, bytes
    // swapped in pairs): non-zero while they play, 0 once out. A player who's
    // out can join again any time while the other plays; with both out the
    // game runs one "continue?" countdown, about 14 s from the last one out
    // to the title. The attract demo's players set these bytes too, so 0x2D
    // says whether a real game is on (128) or not (0).
    seats = SeatStatus(listOf(0x40, 0x4A), setOf(0), outForMs = 17_000, game = 0x2D, inGame = setOf(128)),
) + Game(
    // Athena, 1993 (Seta board): no BIOS. Checked in FBNeo: A attacks (a held
    // A attacks once, so it has rapid fire), B jumps, C does nothing.
    id = "jjsquawk", series = "J.J. Squawkers", title = "J.J. Squawkers",
    // The bootlegs run on other boards: not listed.
    sets = listOf("jjsquawk", "jjsquawko"),
    layout = Layout.SHOOTER, art = "J. J. Squawkers",
    labels = mapOf('A' to "Attack", 'B' to "Jump"),
    rapid = Key.A,
    neoGeo = false,
    // Each player's status (raw 0x561D, 0x561C): 127 out (126 for a moment
    // between attract screens), 0 or 1 playing, 255 during the "continue?"
    // countdown, back to 127 when it ran out.
    seats = SeatStatus(listOf(0x561D, 0x561C), setOf(126, 127)),
) + Game(
    // Konami, 1993: four ninjas at once. No BIOS. FBNeo runs it as the
    // 2-player cabinet unless told otherwise, and with shared coins only the
    // first two slots take one: four players, a coin slot each. Checked in
    // FBNeo: A attacks (holding it keeps throwing), B jumps, C does nothing.
    id = "mystwarr", series = "Mystic Warriors", title = "Mystic Warriors",
    sets = listOf("mystwarr", "mystwarru", "mystwarrj", "mystwarra", "mystwarraa"),
    layout = Layout.BRAWLER, art = "Mystic Warriors (ver EAA)",
    labels = mapOf('A' to "Attack", 'B' to "Jump"),
    neoGeo = false,
    players = 4,
    dips = mapOf("Number_of_Players" to "4", "Coin_Mechanism" to "Independent"),
    // Each player's state, 0x40 apart (raw 0x20D on): 0 out, 2 choosing a
    // ninja, 4 playing and through the "continue?" countdown, back to 0 at
    // game over. The attract demo leaves them alone.
    seats = SeatStatus(listOf(0x20D, 0x24D, 0x28D, 0x2CD), setOf(0)),
) + Game(
    // Konami, 1991: the 4-player cabinet, a cowboy per slot. No BIOS. It has
    // no START: a player joins with a fire button, so START presses A. Checked
    // in FBNeo: A shoots (holding it keeps shooting), B jumps. The 2-player
    // versions aren't listed: they'd leave two of the four seats empty.
    id = "ssriders", series = "Sunset Riders", title = "Sunset Riders",
    sets = listOf("ssriders", "ssridersadd", "ssriderseaa", "ssridersjac", "ssridersjad", "ssridersuab", "ssridersuac", "ssridersuda"),
    layout = Layout.SHOOTER, art = "Sunset Riders (4 Players ver EAC)",
    labels = mapOf('A' to "Shoot", 'B' to "Jump"),
    neoGeo = false,
    players = 4,
    startWith = 'A',
    // Each player's state, 0x40 apart (raw 0x211 on): 129 playing, 1 during
    // the "continue?" countdown, 0 out. The attract demo's cowboys set them
    // too; raw 0x0 is 2 only while a real game is on (1 in attract).
    seats = SeatStatus(listOf(0x211, 0x251, 0x291, 0x2D1), setOf(0), game = 0x0, inGame = setOf(2)),
) + Game(
    // Konami, 1992: four cowboys at once. No BIOS. FBNeo runs it as the
    // 3-player cabinet with shared coins unless told otherwise: four players,
    // a coin slot each. Checked in FBNeo: A shoots (holding it keeps
    // shooting), B jumps; 60 frames a second.
    id = "moomesa", series = "Moo Mesa", title = "C.O.W.-Boys of Moo Mesa", subtitle = "Wild West",
    sets = listOf("moomesa", "moomesaaab", "moomesauab", "moomesauac"),
    layout = Layout.SHOOTER, art = "Wild West C.O.W.-Boys of Moo Mesa (ver EAB)",
    labels = mapOf('A' to "Shoot", 'B' to "Jump"),
    neoGeo = false,
    players = 4,
    dips = mapOf("Number_of_Players" to "4", "Coin_Mechanism" to "Independent"),
    // Each player's state, 0x80 apart (raw 0x905 on): 255 from START through
    // the "continue?" countdown, 0 once GAME OVER shows. The attract demo
    // leaves them alone.
    seats = SeatStatus(listOf(0x905, 0x985, 0xA05, 0xA85), setOf(0)),
) + Game(
    // Neo Geo, 1995: one fighter each. A-D are one attack at four strengths;
    // a full gauge (CHARGE) turns a special into its Charge Move with two
    // buttons. Moves checked in FBNeo (tools/movetest.py).
    id = "doubledr", series = "Double Dragon", title = "Double Dragon", sets = listOf("doubledr"),
    art = "Double Dragon (Neo-Geo)",
    layout = Layout.FIGHTER, roster = "doubledr", team = 1, block = true,
) + Game(
    // Capcom CPS-3, 1999: no BIOS. Six buttons, LP MP HP over LK MK HK; the
    // older dumps' chip files are converted on import (RomRepack.kt).
    id = "sfiii3", series = "Street Fighter", title = "Street Fighter III", subtitle = "3rd Strike",
    sets = listOf("sfiii3n", "sfiii3nr1", "sfiii3", "sfiii3r1"),
    art = "Street Fighter III 3rd Strike: Fight for the Future (Japan 990608, NO CD)",
    layout = Layout.FIGHTER, roster = "sfiii3", team = 1, block = true,
    labels = mapOf('A' to "LP", 'B' to "MP", 'C' to "HP", 'D' to "LK", 'E' to "MK", 'F' to "HK"),
    system = listOf(Move("Throw", "AD"), Move("Overhead", "BE")),
    neoGeo = false,
    pad = PadMap.CAPCOM_6,
    // Each player's "in the fight" flag (1 in, 0 out; P1's at raw 0x156E1).
    // It drops when the loser's continue countdown starts; the game keeps no
    // byte for the countdown itself that could be found. Measured: GAME OVER
    // 17 s after the drop alone, "PRESS 1P START" back 16-19 s after it in versus.
    seats = SeatStatus(listOf(0x156E1, 0x156E0), setOf(0), outForMs = 18_000),
) + Game(
    // Capcom CPS-2, 1998: no BIOS, but each version's zip carries its .key.
    // Two-character teams: HP+HK tags, MP+MK calls the Special Partner.
    // Six buttons as in Street Fighter III (checked in mvsc, tools/emu.py).
    id = "mvsc", series = "Marvel vs Capcom", title = "Marvel vs Capcom",
    sets = listOf("mvsc", "mvscu", "mvscur1", "mvscj", "mvscjr1", "mvsca", "mvscar1", "mvscb", "mvsch", "mvscr1"),
    art = "Marvel Vs. Capcom: Clash of Super Heroes (Europe 980123)",
    layout = Layout.FIGHTER, roster = "mvsc", team = 2, block = true,
    labels = mapOf('A' to "LP", 'B' to "MP", 'C' to "HP", 'D' to "LK", 'E' to "MK", 'F' to "HK"),
    system = listOf(Move("Tag", "CF"), Move("Partner", "BE")),
    neoGeo = false,
    pad = PadMap.CAPCOM_6,
    // Each player's state, in their first fighter block (68000 RAM 0xFF32B0,
    // 0xFF36B0): 0 out, 6 playing, 8 continue countdown, then 10 and 12 when
    // it ran out (game over), back to 0. Checked in versus: the loser went
    // 6, 8, 12, 0 while the winner stayed at 6.
    seats = SeatStatus(listOf(0x32B1, 0x36B1), setOf(0, 10, 12)),
) + Game(
    // SNK, 1999 (Neo Geo): one fighter each. A and C punch, B and D kick
    // (light, heavy). Moves, detection and the system moves checked in FBNeo
    // (tools/garou_roster.py): all 137 moves of the 14 fighters, Grant and
    // Kain included (the main set's MVS codes pick them).
    id = "garou", series = "Fatal Fury", title = "Garou", subtitle = "Mark of the Wolves",
    sets = listOf("garou", "garouh", "garouha", "garoup", "garoubl", "garoubs", "garoujq"),
    art = "Garou - Mark of the Wolves (NGM-2530)",
    layout = Layout.FIGHTER, roster = "garou", team = 1, block = true,
    labels = mapOf('A' to "LP", 'B' to "LK", 'C' to "HP", 'D' to "HK"),
    // A+B is the evasion (also the Break); C+D the T.O.P. attack, only while
    // life is in the T.O.P. area (else a heavy punch); 2+A+C a feint.
    system = listOf(Move("Evade", "AB"), Move("T.O.P.", "CD"), Move("Feint", "2+AC")),
)

fun gameById(id: String): Game? = GAMES.find { it.id == id }

/** Kinds of picture in libretro's thumbnail collection. */
enum class Art(val folder: String) { TITLE("Named_Titles"), SNAP("Named_Snaps"), BOXART("Named_Boxarts") }

/**
 * A game's picture, as it ships inside the app (assets/art/<this>): from
 * libretro's FBNeo thumbnail collection (the set RetroArch and Lemuroid
 * use), fetched when the app is built (app/art.txt lists them all), never
 * over the network while it runs. Characters libretro can't use in file
 * names become "_".
 */
fun artFile(game: Game, kind: Art, set: String? = null): String? {
    // A version listed in the game's sets has its own picture, under the
    // name FBNeo gives it (the thumbnails are named after FBNeo's list).
    val name = set?.takeIf { it in game.sets }?.let { romSet(it)?.title } ?: game.art ?: return null
    val file = name.map { if (it in "&*/:`<>?\\|\"") '_' else it }.joinToString("")
    return "${kind.folder}/$file.png"
}

/** Where [artFile] comes from: libretro's thumbnail collection (for building the app, and tests). */
fun artSource(file: String): String {
    val (folder, name) = file.split('/', limit = 2)
    val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    return "https://raw.githubusercontent.com/libretro-thumbnails/FBNeo_-_Arcade_Games/master/$folder/$encoded"
}

/** Every picture the app can show: each game's title screen and play screen, for each of its versions. */
fun allArt(): List<String> = GAMES.flatMap { game ->
    listOf(Art.TITLE, Art.SNAP).flatMap { kind -> (listOf<String?>(null) + game.sets).mapNotNull { artFile(game, kind, it) } }
}.distinct().sorted()

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
