package com.kofarcade.core

// Automatic fighter detection: reads the game's RAM to find your team (or
// fighter), which member is fighting and which way they face. KOF first;
// Double Dragon, Street Fighter III and Marvel vs Capcom at the bottom.
//
// Addresses are 68000 addresses in Neo Geo work RAM (0x100000-0x10FFFF), from
// the hitbox viewers, training-mode scripts and cheat files the fighting game
// community keeps (sources in docs/kof-ram.md). KOF '97 and 2002 share one
// player layout: P1 at 0x108100, P2 0x200 later, X at +0x18, facing in bit 0
// of +0x31, character ID in the byte at +0x71. The 2005 hacks are KOF 2002
// underneath and use its addresses and IDs. Checked by running kof97 and
// kf2k5uni in FBNeo (tools/emu.py; results at the top of docs/kof-ram.md).

/**
 * Windows of a game's RAM as FBNeo stores them: the CPU's big-endian words in
 * host (little endian) order. The Neo Geo's 68000 has 16-bit words, so the
 * byte at address A sits at index (A - base) xor 1 ([swap] 1); CPS-3's SH-2
 * has 32-bit ones (xor 3).
 */
class Ram(private val windows: List<Pair<Int, ByteArray>>, private val swap: Int = 1) {
    constructor(base: Int, buf: ByteArray, swap: Int = 1) : this(listOf(base to buf), swap)

    init {
        require(windows.all { (base, _) -> base and swap == 0 }) { "windows must start on a word" }
    }

    fun byte(addr: Int): Int {
        for ((base, buf) in windows) {
            val i = addr - base
            if (i >= 0 && i < buf.size) return buf[i xor swap].toInt() and 0xFF
        }
        throw IllegalArgumentException("0x${addr.toString(16)} is outside the RAM read")
    }

    /** A big-endian 16-bit word at an even address. */
    fun word(addr: Int): Int = (byte(addr) shl 8) or byte(addr + 1)

    companion object {
        /** The part of KOF's RAM detection reads: 0x108000-0x10AFFF. */
        const val START = 0x108000
        const val LENGTH = 0x3000
    }
}

/** Where one game keeps what detection needs. */
class DetectSpec(
    /** Where the emulator's RAM starts in the CPU's map, and its [Ram] byte order. */
    val memoryBase: Int = 0x100000,
    val swap: Int = 1,
    /** The parts of RAM read (start, length): what everything below lives in. */
    val windows: List<Pair<Int, Int>> = listOf(Ram.START to Ram.LENGTH),
    /** P1's fighter block; P2's is [playerStride] later. */
    val player: Int = 0x108100,
    val playerStride: Int = 0x200,
    /** Offsets in a fighter block: character ID, X, and a word that is 0 while on the floor (KOF: height). */
    val character: Int = 0x71,
    val x: Int = 0x18,
    val grounded: Int = 0x20,
    /** Facing left, from a fighter block (null: face the other fighter, by X). */
    val facing: ((Ram, Int) -> Boolean)? = { ram, base -> ram.byte(base + 0x31) and 1 == 0 },
    /** Team members in pick order, P1. */
    val team: List<Int>,
    /** Offset from P1's team bytes to P2's. */
    val teamStride: Int,
    /** Mask for the team bytes (KOF 2002 flags roulette picks in bit 7). */
    val teamMask: Int = 0xFF,
    /** True while a round is being fought. */
    val inRound: (Ram) -> Boolean,
    /** Game ID to roster character id; null entries aren't playable. */
    val ids: List<String?>,
    /** Alternate versions ('94 Kyo, Orochi Yashiro...) by base character, with the per-slot flag byte. */
    val alternates: Map<String, String> = emptyMap(),
    val alternateFlags: Int? = null,
    /** Where the alternate version is told by the fighter on screen instead (its block): true for it. */
    val activeAlternate: ((Ram, Int) -> Boolean)? = null,
    /** Street Fighter III: the Super Art picked (0-2), P1; P2's is the next byte. */
    val superArt: Int? = null,
    /** Tag teams: the block of the fighter on screen, from the player's first block (null: that one). */
    val pointBase: ((Ram, Int) -> Int)? = null,
)

/** The block of [player]'s fighter on screen. */
fun fighterBase(spec: DetectSpec, ram: Ram, player: Int): Int {
    val base = spec.player + spec.playerStride * player
    return spec.pointBase?.invoke(ram, base) ?: base
}

private fun kof97(ids: List<String?>) = DetectSpec(
    team = listOf(0x10A84B, 0x10A84C, 0x10A84D),
    teamStride = 0x11,
    inRound = { (it.byte(0x10A83E) and 0x80) == 0 && it.byte(0x10A840) > 0 && (it.byte(0x10A788) and 2) == 0 },
    ids = ids,
    alternates = mapOf("kyo" to "kyo94", "yashiro" to "oyashiro", "shermie" to "oshermie", "chris" to "ochris"),
    alternateFlags = 0x10A857,
)

private val KOF97_IDS = listOf(
    "kyo", "benimaru", "daimon", "terry", "andy", "joe", "ryo", "robert",
    "yuri", "leona", "ralf", "clark", "athena", "kensou", "chin", "chizuru",
    "mai", "king", "kim", "chang", "choi", "yashiro", "shermie", "chris",
    "yamazaki", "mary", "billy", "iori", "oiori", "oleona", null /* Orochi */, "shingo",
)

private val KOF97 = kof97(KOF97_IDS)

// '97 Plus makes Orochi playable (checked in kof97pls: his ID is 0x1E).
private val KOF97_PLUS = kof97(KOF97_IDS.map { it ?: "orochi" })

private fun kof2002(alternate: ((Ram, Int) -> Boolean)? = null) = DetectSpec(
    team = listOf(0x10A804, 0x10A805, 0x10A806),
    teamStride = 0x26,
    teamMask = 0x7F,
    inRound = { (it.byte(0x10A7D6) and 0x80) == 0 && it.byte(0x10A7D8) > 0 && (it.byte(0x10A788) and 2) == 0 },
    ids = listOf(
        "kyo", "benimaru", "daimon", "terry", "andy", "joe", "kim", "chang",
        "choi", "athena", "kensou", "chin", "leona", "ralf", "clark", "ryo",
        "robert", "takuma", "mai", "yuri", "maylee", "iori", "mature", "vice",
        "yamazaki", "mary", "billy", "yashiro", "shermie", "chris", "kdash", "maxima",
        "whip", "vanessa", "seth", "ramon", "kula", "k9999", "angel", "rugal",
        "kusanagi",
    ),
    alternates = if (alternate != null) mapOf("yashiro" to "oyashiro", "shermie" to "oshermie", "chris" to "ochris") else emptyMap(),
    activeAlternate = alternate,
)

private val KOF2002 = kof2002()

// 2005 Unique's Orochi team (Yashiro, Shermie, Chris from the Orochi box of
// the select screen) shares the regular fighters' IDs; byte +0x08 of the
// fighter's block is 1 for them, 0 for the regular ones (checked picking
// each from the select screen in kf2k5uni: their Orochi moves come out).
private val KOF2005 = kof2002 { ram, base -> ram.byte(base + 0x08) == 1 }

// Double Dragon (Neo Geo): one fighter each, P1's block at 0x100400 and P2's
// 0x100 later; the character ID at +0x45 (the FBNeo cheat file's list; the
// odd IDs are Billy and Jimmy transformed), X at +0x30 (found walking in
// tools/emu.py), and +0x46 is 0 on the floor (5 in a jump). Fighters always face each other, so facing comes from X.
private val DOUBLE_DRAGON_IDS = listOf(
    "billy", "billy", "jimmy", "jimmy", "marian", "abobo", "amon", "eddie",
    "rebecca", "dulton", "chengfu", "burnov", "duke", "shuko",
)

private val DOUBLE_DRAGON = DetectSpec(
    windows = listOf(0x100400 to 0x200),
    player = 0x100400,
    playerStride = 0x100,
    character = 0x45,
    x = 0x30,
    grounded = 0x46,
    facing = null,
    team = listOf(0x100445),
    teamStride = 0x100,
    // No round flag is known: a fighter is read whenever both IDs are real
    // ones (on the select screen too, where it shows the fighter picked).
    inRound = { ram -> ram.byte(0x100445) < DOUBLE_DRAGON_IDS.size && ram.byte(0x100545) < DOUBLE_DRAGON_IDS.size },
    ids = DOUBLE_DRAGON_IDS,
)

// Street Fighter III 3rd Strike (CPS-3, SH-2 addresses): P1's fighter block at
// 0x02068C6C, P2's 0x498 later, with the character in the word at +0x3C0, X
// at +0x64, Y at +0x68 and facing at +0x0A (0 = left). The pick is also kept at
// 0x02011387 (P2 0x02011388), the Super Art picked at 0x0201138B (0-2), and
// 0x020154A7 is 2 while a round is fought.
// From the 3rd Strike training scripts (Grouflon's and effie3rd's
// 3rd_training_lua) and FBNeo's cheat file; checked in sfiii3n
// (tools/emu.py): Alex against Ryu reads 1 and 2, facing 1 and 0.
private val SF3_IDS = listOf(
    "gill", "alex", "ryu", "yun", "dudley", "necro", "hugo", "ibuki", "elena", "oro", "yang",
    "ken", "sean", "urien", "akuma", null /* Shin Akuma */, "chunli", "makoto", "q", "twelve", "remy",
)

private val SF3 = DetectSpec(
    memoryBase = 0x02000000,
    swap = 3,
    windows = listOf(0x02011380 to 0x10, 0x020154A0 to 0x10, 0x02068C60 to 0x8A0),
    player = 0x02068C6C,
    playerStride = 0x498,
    character = 0x3C1,
    x = 0x64,
    grounded = 0x68,
    facing = { ram, base -> ram.byte(base + 0x0A) == 0 },
    team = listOf(0x02011387),
    teamStride = 1,
    inRound = { it.byte(0x020154A7) == 2 },
    ids = SF3_IDS,
    superArt = 0x0201138B,
)

// Marvel vs Capcom (CPS-2, 68000 RAM from 0xFF0000): four fighter blocks
// 0x400 apart, P1's first at 0xFF3000, P2's first 0xFF3400, their partners
// 0x800 later. The character is the byte at +0x53, facing bit 0 of +0x4B (1 =
// right), X at +0x0C; +0x00 is 1 for whichever of a player's two is on screen
// and +0xB4 is 0 on the floor. From the FBNeo cheat file and the CPS-2
// hitbox scripts; checked in mvsc (tools/emu.py), tag included.
private val MVSC_IDS = List(0x2E) { id ->
    mapOf(
        0x02 to "warmachine", 0x04 to "captainamerica", 0x06 to "hulk", 0x08 to "wolverine",
        0x0A to "gambit", 0x0C to "venom", 0x0E to "spiderman", 0x10 to "roll", 0x12 to "ryu",
        0x14 to "captaincommando", 0x16 to "chunli", 0x18 to "jin", 0x1A to "zangief",
        0x1C to "strider", 0x1E to "morrigan", 0x20 to "megaman", 0x24 to "redvenom",
        0x26 to "orangehulk", 0x28 to "goldwarmachine", 0x2A to "shadowlady", 0x2C to "lilith",
    )[id]
}

private val MVSC = DetectSpec(
    memoryBase = 0xFF0000,
    windows = listOf(0xFF3000 to 0x1000, 0xFF4000 to 0x10),
    player = 0xFF3000,
    playerStride = 0x400,
    character = 0x53,
    x = 0x0C,
    grounded = 0xB4,
    facing = { ram, base -> ram.byte(base + 0x4B) and 1 == 0 },
    team = listOf(0xFF3053, 0xFF3853),
    teamStride = 0x400,
    inRound = { it.byte(0xFF4000) == 8 },
    ids = MVSC_IDS,
    pointBase = { ram, base -> if (ram.byte(base) == 1) base else base + 0x800 },
)

/** Detection for a roster, or null if its game has no RAM map. */
fun detectSpec(roster: String?): DetectSpec? = when (roster) {
    "kof97" -> KOF97
    "kof97plus" -> KOF97_PLUS
    "kof2002", "kof2002mp" -> KOF2002
    "kof2005" -> KOF2005
    "doubledr" -> DOUBLE_DRAGON
    "sfiii3" -> SF3
    "mvsc" -> MVSC
    else -> null
}

/** What detection saw for one player. Raw IDs are kept for the debug readout. */
data class Detection(
    val inRound: Boolean,
    /** Team in pick order (roster ids); null where an ID isn't known. */
    val team: List<String?>,
    val teamRaw: List<Int>,
    /** The fighter on screen and their team slot; only trusted in a round. */
    val active: String?,
    val activeRaw: Int,
    val activeSlot: Int?,
    val facingLeft: Boolean,
    val x: Int,
    val otherX: Int,
    /** The Super Art picked (1-3), in games that have them. */
    val superArt: Int? = null,
) {
    /** The whole team was recognised. */
    val teamKnown: Boolean get() = team.all { it != null }
}

/** Reads [player]'s (0 = P1, 1 = P2) fighters from [ram]. */
fun detect(spec: DetectSpec, ram: Ram, player: Int = 0): Detection {
    val other = 1 - player
    val base = fighterBase(spec, ram, player)
    val otherBase = fighterBase(spec, ram, other)
    val flags = spec.alternateFlags?.let { ram.byte(it + spec.teamStride * player) } ?: 0
    val teamRaw = spec.team.map { ram.byte(it + spec.teamStride * player) and spec.teamMask }
    val team = teamRaw.mapIndexed { slot, raw ->
        val id = spec.ids.getOrNull(raw)
        if (id != null && flags and (1 shl slot) != 0) spec.alternates[id] ?: id else id
    }
    val activeRaw = ram.byte(base + spec.character)
    // The on-screen ID has no alternate flag of its own, so it is matched
    // against the team: a recognised fighter that isn't in it is a misread.
    val activeSlot = teamRaw.indexOf(activeRaw).takeIf { it >= 0 }
    // Where the fighter on screen says it's the alternate version, so is that team slot.
    val alt = spec.activeAlternate?.invoke(ram, base) == true
    val shown = if (alt && activeSlot != null) {
        team.mapIndexed { slot, id -> if (slot == activeSlot && id != null) spec.alternates[id] ?: id else id }
    } else {
        team
    }
    return Detection(
        inRound = spec.inRound(ram),
        team = shown,
        teamRaw = teamRaw,
        active = activeSlot?.let { shown[it] },
        activeRaw = activeRaw,
        activeSlot = activeSlot,
        facingLeft = spec.facing?.invoke(ram, base) ?: (ram.word(base + spec.x) > ram.word(otherBase + spec.x)),
        x = ram.word(base + spec.x),
        otherX = ram.word(otherBase + spec.x),
        superArt = spec.superArt?.let { ram.byte(it + player) + 1 }?.takeIf { it in 1..3 },
    )
}
