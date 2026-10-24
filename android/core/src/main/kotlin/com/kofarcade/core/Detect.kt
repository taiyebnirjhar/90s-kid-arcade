package com.kofarcade.core

// Automatic fighter detection: reads the KOF engine's RAM to find your team,
// which member is fighting and which way they face.
//
// Addresses are 68000 addresses in Neo Geo work RAM (0x100000-0x10FFFF), from
// the hitbox viewers, training-mode scripts and cheat files the fighting game
// community keeps (sources in docs/kof-ram.md). KOF '97 and 2002 share one
// player layout: P1 at 0x108100, P2 0x200 later, X at +0x18, facing in bit 0
// of +0x31, character ID in the byte at +0x71. The 2005 hacks are KOF 2002
// underneath and use its addresses and IDs. Checked by running kof97 and
// kf2k5uni in FBNeo (tools/emu.py; results at the top of docs/kof-ram.md).

/**
 * A window of 68000 RAM as FBNeo stores it: 16-bit words in host (little
 * endian) order, so the byte at 68000 address A sits at index (A - base) xor 1.
 */
class Ram(private val base: Int, private val buf: ByteArray) {
    init {
        require(base % 2 == 0) { "base must be even" }
    }

    fun byte(addr: Int): Int {
        val i = (addr - base) xor 1
        require(i in buf.indices) { "0x${addr.toString(16)} is outside the RAM read" }
        return buf[i].toInt() and 0xFF
    }

    /** A big-endian 68000 word at an even address. */
    fun word(addr: Int): Int = (byte(addr) shl 8) or byte(addr + 1)

    companion object {
        /** The part of RAM detection reads: 0x108000-0x10AFFF. */
        const val START = 0x108000
        const val LENGTH = 0x3000
    }
}

/** Where one game keeps what detection needs. */
class DetectSpec(
    /** Character ID while fighting (P1; P2 is [PLAYER_STRIDE] later). */
    val character: Int = 0x108171,
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
)

private const val PLAYER = 0x108100
private const val PLAYER_STRIDE = 0x200
private const val X = 0x18
private const val FACING = 0x31

private val KOF97 = DetectSpec(
    team = listOf(0x10A84B, 0x10A84C, 0x10A84D),
    teamStride = 0x11,
    inRound = { (it.byte(0x10A83E) and 0x80) == 0 && it.byte(0x10A840) > 0 && (it.byte(0x10A788) and 2) == 0 },
    ids = listOf(
        "kyo", "benimaru", "daimon", "terry", "andy", "joe", "ryo", "robert",
        "yuri", "leona", "ralf", "clark", "athena", "kensou", "chin", "chizuru",
        "mai", "king", "kim", "chang", "choi", "yashiro", "shermie", "chris",
        "yamazaki", "mary", "billy", "iori", "oiori", "oleona", null /* Orochi */, "shingo",
    ),
    alternates = mapOf("kyo" to "kyo94", "yashiro" to "oyashiro", "shermie" to "oshermie", "chris" to "ochris"),
    alternateFlags = 0x10A857,
)

private val KOF2002 = DetectSpec(
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
)

/** Detection for a roster, or null if its game has no RAM map. */
fun detectSpec(roster: String?): DetectSpec? = when (roster) {
    "kof97" -> KOF97
    "kof2002" -> KOF2002
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
) {
    /** The whole team was recognised. */
    val teamKnown: Boolean get() = team.all { it != null }
}

/** Reads [player]'s (0 = P1, 1 = P2) fighters from [ram]. */
fun detect(spec: DetectSpec, ram: Ram, player: Int = 0): Detection {
    val other = 1 - player
    val base = PLAYER + PLAYER_STRIDE * player
    val flags = spec.alternateFlags?.let { ram.byte(it + spec.teamStride * player) } ?: 0
    val teamRaw = spec.team.map { ram.byte(it + spec.teamStride * player) and spec.teamMask }
    val team = teamRaw.mapIndexed { slot, raw ->
        val id = spec.ids.getOrNull(raw)
        if (id != null && flags and (1 shl slot) != 0) spec.alternates[id] ?: id else id
    }
    val activeRaw = ram.byte(base + 0x71)
    // The on-screen ID has no alternate flag of its own, so it is matched
    // against the team: a recognised fighter that isn't in it is a misread.
    val activeSlot = teamRaw.indexOf(activeRaw).takeIf { it >= 0 }
    return Detection(
        inRound = spec.inRound(ram),
        team = team,
        teamRaw = teamRaw,
        active = activeSlot?.let { team[it] },
        activeRaw = activeRaw,
        activeSlot = activeSlot,
        facingLeft = ram.byte(base + FACING) and 1 == 0,
        x = ram.word(base + X),
        otherX = ram.word(PLAYER + PLAYER_STRIDE * other + X),
    )
}
