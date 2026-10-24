package com.kofarcade.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ROM checking: works out which version of a game a zip holds, and whether
// it has every file FBNeo needs, from the CRC32 of each file in it (a zip
// lists those up front, so this doesn't read the data). The list of versions
// and their files comes from FBNeo's own driver (resources/romsets.json,
// made by tools/romsets.py).

@Serializable
data class RomFile(val name: String, val size: Long, val crc: String, val optional: Boolean = false)

/** One version of a game as FBNeo knows it: "sengoku3a" is a version of "sengoku3". */
@Serializable
data class RomSet(
    val name: String,
    val parent: String? = null,
    /** The set our game list knows it by (Game.sets). */
    val base: String,
    val title: String,
    val files: List<RomFile>,
) {
    val required: List<RomFile> get() = files.filterNot { it.optional }
}

@Serializable
private data class RomDb(val bios: List<RomFile>, val sets: List<RomSet>)

private val romDb: RomDb by lazy {
    val text = RomSet::class.java.getResourceAsStream("/romsets.json")!!.bufferedReader().use { it.readText() }
    Json { ignoreUnknownKeys = true }.decodeFromString<RomDb>(text)
}

val romSets: List<RomSet> get() = romDb.sets

fun romSet(name: String): RomSet? = romDb.sets.find { it.name == name }

/** The game a set (any version) belongs to. */
fun gameForSet(name: String): Game? = romSet(name)?.let { set -> GAMES.find { set.base in it.sets } }
    ?: GAMES.find { name in it.sets }

/** A file inside a zip: what the zip's directory says about it. */
data class ZipItem(val name: String, val size: Long, val crc: Long)

sealed interface RomCheck {
    /** The Neo Geo BIOS, complete. */
    data object Bios : RomCheck

    /** A complete game. [parentNeeded]: some files come from the parent version's zip. */
    data class Game(val set: RomSet, val parentNeeded: Boolean) : RomCheck

    /** The closest version found, with what's missing. */
    data class Incomplete(val set: RomSet, val missing: List<RomFile>, val wrongBios: List<RomFile> = emptyList()) : RomCheck

    /** Not a game the app knows, nor the BIOS. */
    data object Unknown : RomCheck
}

private fun crcOf(item: ZipItem) = "%08x".format(item.crc)

/**
 * Identifies a zip from its [items]. [otherZip] gives the items of another zip
 * already on the phone (by set name), for versions stored as "split" sets that
 * rely on their parent's zip for shared files.
 */
fun checkRom(items: List<ZipItem>, otherZip: (String) -> List<ZipItem>? = { null }): RomCheck {
    val crcs = items.mapTo(HashSet(), ::crcOf)
    val bios = romDb.bios
    val biosFound = bios.count { it.crc in crcs }

    data class Score(val set: RomSet, val own: Int, val missing: List<RomFile>, val fromParent: Boolean)

    val scores = romDb.sets.map { set ->
        val required = set.required
        val own = required.count { it.crc in crcs }
        var missing = required.filterNot { it.crc in crcs }
        var fromParent = false
        if (missing.isNotEmpty() && own > 0 && set.parent != null) {
            val parentCrcs = otherZip(set.parent)?.mapTo(HashSet(), ::crcOf).orEmpty()
            val stillMissing = missing.filterNot { it.crc in parentCrcs }
            fromParent = stillMissing.size < missing.size
            missing = stillMissing
        }
        Score(set, own, missing, fromParent)
    }.filter { it.own > 0 }

    // Prefer complete versions, then the one matching most of the zip's own
    // files (the right version rather than one sharing a few chips with it).
    val complete = scores.filter { it.missing.isEmpty() }.maxWithOrNull(
        compareBy<Score> { it.own }.thenBy { if (it.set.name == it.set.base) 1 else 0 },
    )
    if (complete != null) return RomCheck.Game(complete.set, complete.fromParent)
    if (biosFound == bios.size) return RomCheck.Bios

    val best = scores.maxWithOrNull(
        compareBy<Score> { it.own.toDouble() / it.set.required.size }.thenBy { it.own },
    )
    if (best != null && best.own >= biosFound) return RomCheck.Incomplete(best.set, best.missing)
    if (biosFound > 0) {
        return RomCheck.Incomplete(BIOS_SET, bios.filterNot { it.crc in crcs })
    }
    return RomCheck.Unknown
}

/** The BIOS as a set, for reporting what's missing from a neogeo.zip. */
val BIOS_SET: RomSet by lazy { RomSet("neogeo", null, "neogeo", "Neo Geo BIOS", romDb.bios) }

/** A plain description of what's missing, e.g. "261-m1.m1 (sound program)". */
fun describe(file: RomFile): String {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val part = when {
        file.name.startsWith("sp-") || file.name.startsWith("uni-bios") -> "system BIOS"
        file.name == "sm1.sm1" -> "BIOS sound program"
        file.name == "sfix.sfix" -> "BIOS text graphics"
        file.name == "000-lo.lo" -> "BIOS zoom table"
        ext.startsWith("p") -> "game program"
        ext.startsWith("m") -> "sound program"
        ext.startsWith("v") -> "sound samples"
        ext.startsWith("c") -> "graphics"
        ext.startsWith("s") -> "text graphics"
        else -> null
    }
    return if (part != null) "${file.name} ($part)" else file.name
}

/**
 * The version of [game] to boot, from the zips on the phone ([zips], set
 * names): its main set if present, otherwise any other version of it.
 */
fun installedSet(game: Game, zips: Collection<String>): String? =
    game.sets.firstOrNull { it in zips }
        ?: zips.sorted().firstOrNull { name -> romSet(name)?.base?.let { it in game.sets } == true }

/**
 * One number for a zip's contents, for checking that two phones have the
 * same files (CRC32 over the sorted file CRCs, so names and order don't count).
 */
fun romFingerprint(items: List<ZipItem>): Long {
    val crc = java.util.zip.CRC32()
    for (c in items.map { it.crc }.sorted()) {
        for (shift in 0 until 32 step 8) crc.update(((c shr shift) and 0xFF).toInt())
    }
    return crc.value
}
