package com.kofarcade

import android.content.Context
import android.net.Uri
import com.kofarcade.core.BIOS
import com.kofarcade.core.Game
import com.kofarcade.core.Move
import com.kofarcade.core.RomCheck
import com.kofarcade.core.ZipItem
import com.kofarcade.core.checkRom
import com.kofarcade.core.describe
import com.kofarcade.core.gameForSet
import com.kofarcade.core.installedSet
import com.kofarcade.core.romFingerprint
import com.kofarcade.core.repackCps3
import com.kofarcade.core.romSet
import com.kofarcade.core.setName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * ROM zips, copied into the app's private storage. FBNeo identifies a game by
 * its zip name, so each zip is checked first (see core RomCheck) and stored
 * under the name of the version it holds: whatever it was called,
 * a complete Sengoku 3 (set 2) becomes sengoku3a.zip, the BIOS neogeo.zip.
 */
class RomStore(private val context: Context) {
    val dir: File = File(context.filesDir, "roms").apply { mkdirs() }

    init {
        // Copies and downloads cut off when the app was last closed: nothing
        // can still be writing them in a new process.
        synchronized(RomStore) {
            if (!cleaned) {
                cleaned = true
                dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
            }
        }
    }

    private companion object {
        var cleaned = false
    }

    fun file(name: String) = File(dir, name)

    fun has(name: String) = file(name).let { it.isFile && it.length() > 0 }

    /** Set names of the zips on the phone. */
    fun zips(): List<String> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zip") && it.length() > 0 }.map { setName(it.name) }

    /** The zip to boot for [game], if any version of it is here. */
    fun setFor(game: Game): String? = installedSet(game, zips())

    /**
     * A new scratch file in the ROM folder, for a copy or download in
     * progress: each its own, so two at once (a download left running by a
     * rotated screen, an import meanwhile) never write the same file.
     */
    fun partFile(label: String): File = File.createTempFile("$label-", ".part", dir)

    /** Copies the picked file in and checks it. */
    fun import(uri: Uri): RomCheck {
        val tmp = partFile("import")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Can't read the picked file" }
                tmp.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return add(tmp)
    }

    /**
     * Checks the zip at [tmp]. The BIOS or a complete game is moved into
     * place, replacing other versions of the same game that FBNeo would
     * otherwise pick first; anything else is deleted.
     */
    fun add(tmp: File): RomCheck {
        try {
            return checkAndStore(tmp)
        } finally {
            // Moved into place, or not wanted: either way no scratch file stays behind.
            tmp.delete()
        }
    }

    // A zip already here, for a version that borrows files from it; a broken
    // one counts as missing (the new file isn't to blame).
    private fun existing(set: String): List<ZipItem>? =
        file("$set.zip").takeIf { it.isFile }?.let { runCatching { items(it) }.getOrNull() }

    private fun checkAndStore(tmp: File): RomCheck {
        var check = try {
            checkRom(items(tmp), ::existing)
        } catch (e: ZipException) {
            tmp.delete()
            throw IllegalArgumentException("That file isn't a zip")
        }
        // An older Street Fighter III dump: the same chips, packed differently.
        if (check !is RomCheck.Game && check != RomCheck.Bios) {
            val repacked = partFile("repack")
            try {
                if (runCatching { repackCps3(tmp, repacked) }.getOrDefault(false)) {
                    tmp.delete()
                    check(repacked.renameTo(tmp)) { "Couldn't save the converted zip" }
                    check = checkRom(items(tmp), ::existing)
                }
            } finally {
                repacked.delete()
            }
        }
        when (check) {
            RomCheck.Bios -> moveIn(tmp, BIOS)
            is RomCheck.Game -> {
                val set = check.set
                moveIn(tmp, "${set.name}.zip")
                // Other versions of the same card would be booted instead of
                // this one, so they go; a hack with its own card stays, and so
                // does any zip another one still borrows files from (its parent).
                val game = gameForSet(set.name)
                val kept = zips()
                val parents = kept.mapNotNull { romSet(it)?.parent }.toSet()
                kept.filter { it != set.name && it != set.parent && it !in parents && gameForSet(it) == game }
                    .forEach { file("$it.zip").delete() }
            }
            else -> Unit
        }
        return check
    }

    private fun moveIn(tmp: File, name: String) {
        file(name).delete()
        check(tmp.renameTo(file(name))) { "Couldn't save $name" }
    }

    /** The [romFingerprint] of a zip here, or null if it's missing or unreadable. */
    fun fingerprint(name: String): Long? = file(name).takeIf { it.isFile }?.let { runCatching { romFingerprint(items(it)) }.getOrNull() }

    private fun items(zip: File): List<ZipItem> = ZipFile(zip).use { z ->
        z.entries().asSequence().filterNot { it.isDirectory }.map { ZipItem(it.name, it.size, it.crc) }.toList()
    }

    fun remove(name: String) {
        file(name).delete()
    }
}

/** What a check found, in words, for a toast or dialog. */
fun RomCheck.message(): String = when (this) {
    RomCheck.Bios -> "Neo Geo BIOS added."
    is RomCheck.Game -> {
        val game = gameForSet(set.name)
        "Added ${game?.title ?: set.title}: ${set.title}." +
            if (parentNeeded) " It also uses your ${set.parent}.zip, so keep that one." else ""
    }
    is RomCheck.Incomplete -> {
        val list = missing.take(6).joinToString("\n") { "• " + describe(it) } +
            if (missing.size > 6) "\n• and ${missing.size - 6} more" else ""
        "This is ${set.title}, but it's missing ${missing.size} file${if (missing.size == 1) "" else "s"} " +
            "the emulator needs:\n$list\n\nIt's from an older or different ROM set. " +
            "You need a copy made for current FBNeo."
    }
    RomCheck.Unknown -> "This zip isn't one of the app's games or the Neo Geo BIOS."
}

enum class StickMode(val label: String, val short: String) {
    FLOATING("Appears under your thumb, anywhere on the left", "Floating"),
    FIXED("Always in the same place", "Fixed"),
    HIDDEN("Works the same, but isn't drawn", "Hidden"),
}

enum class LabelMode(val label: String) {
    SUPERS("Supers only"),
    ALL("All moves"),
    OFF("None"),
}

/**
 * How wide the game picture is. Neo Geo games are 4:3; anything wider
 * stretches them, so the in-between widths trade a small gap at the sides for
 * fighters that keep their shape. [aspect] null = the whole screen.
 */
enum class ScreenMode(val label: String, val aspect: Float?, val short: String) {
    ORIGINAL("Original shape, no stretch", 4f / 3f, "4:3"),
    SLIGHT("A little wider, small gaps at the sides", 3f / 2f, "Slightly wide"),
    WIDE("Widescreen, a little stretched", 16f / 9f, "16:9"),
    FULL("The whole screen, most stretch", null, "Full"),
}

private const val LAYOUT_VERSION = 3

/** Settings, teams and custom move lists. */
class Prefs(context: Context) {
    // Keeps its original file name (from when the app was "KOF Arcade") so
    // settings survive the rename.
    private val sp = context.getSharedPreferences("kof-arcade", Context.MODE_PRIVATE)

    var stick: StickMode
        get() = runCatching { StickMode.valueOf(sp.getString("stick", null)!!) }.getOrDefault(StickMode.FLOATING)
        set(v) = sp.edit().putString("stick", v.name).apply()

    var size: Float
        get() = sp.getFloat("size", HudView.DEFAULT_SIZE)
        set(v) = sp.edit().putFloat("size", v).apply()

    var opacity: Float
        get() = sp.getFloat("opacity", 0.46f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var leftHanded: Boolean
        get() = sp.getBoolean("leftHanded", false)
        set(v) = sp.edit().putBoolean("leftHanded", v).apply()

    /** Which move buttons show their name; supers only keeps the ring quiet. */
    var labelMode: LabelMode
        get() = runCatching { LabelMode.valueOf(sp.getString("labelMode", null)!!) }.getOrDefault(LabelMode.SUPERS)
        set(v) = sp.edit().putString("labelMode", v.name).apply()

    var stepFrames: Int
        get() = sp.getInt("stepFrames", 2)
        set(v) = sp.edit().putInt("stepFrames", v).apply()

    var pressFrames: Int
        get() = sp.getInt("pressFrames", 4)
        set(v) = sp.edit().putInt("pressFrames", v).apply()

    /** Metal Slug, Nightmare in the Dark: holding Shoot / Fire keeps firing. */
    var rapidFire: Boolean
        get() = sp.getBoolean("rapidFire", true)
        set(v) = sp.edit().putBoolean("rapidFire", v).apply()

    /** The games' sound on or off (off keeps the volume for when it's back on). */
    var soundOn: Boolean
        get() = sp.getBoolean("soundOn", true)
        set(v) = sp.edit().putBoolean("soundOn", v).apply()

    /** The games' volume, 0.1 to 1. */
    var volume: Float
        get() = sp.getFloat("volume", 1f)
        set(v) = sp.edit().putFloat("volume", v).apply()

    /** Your private ROM library (a Cloudflare Worker in front of R2; see server/). */
    var libraryUrl: String
        get() = sp.getString("libraryUrl", null)?.takeIf { it.isNotBlank() } ?: Library.DEFAULT_URL
        set(v) = sp.edit().putString("libraryUrl", v.takeIf { it != Library.DEFAULT_URL }).apply()

    var libraryKey: String?
        get() = sp.getString("libraryKey", null)
        set(v) = sp.edit().putString("libraryKey", v).apply()

    /** The set a library file was saved as, once downloaded ("neogeo (1).zip" -> neogeo). */
    fun librarySet(file: String): String? = sp.getString("lib.$file", null)

    fun setLibrarySet(file: String, set: String?) = sp.edit().putString("lib.$file", set).apply()

    /** Shows what detection reads, to check it (and map unknown fighters) on a phone. */
    var detectInfo: Boolean
        get() = sp.getBoolean("detectInfo", false)
        set(v) = sp.edit().putBoolean("detectInfo", v).apply()

    var screen: ScreenMode
        get() = runCatching { ScreenMode.valueOf(sp.getString("screen", null)!!) }.getOrDefault(ScreenMode.WIDE)
        set(v) = sp.edit().putString("screen", v.name).apply()

    /** Your control layout for a kind of game (fighter / shooter / brawler) and hand. */
    fun tweaks(key: String): Map<String, HudView.Tweak> {
        forgetOldLayouts()
        val raw = sp.getString("layout.$key", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { id ->
                val a = o.getJSONArray(id)
                HudView.Tweak(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Saved layouts are offsets from the default spots. Version 2 moved the
     * defaults to the layouts arranged on a phone (KOF's square, Metal Slug,
     * Sengoku 3), so offsets saved against the old ones would move everything
     * twice: they're dropped once. Version 3: smaller controls in the corners,
     * Block above the stick, the third quick button beside A (as players
     * arranged them).
     */
    private fun forgetOldLayouts() {
        if (sp.getInt("layoutVersion", 1) >= LAYOUT_VERSION) return
        val edit = sp.edit()
        sp.all.keys.filter { it.startsWith("layout.") }.forEach(edit::remove)
        edit.putInt("layoutVersion", LAYOUT_VERSION).apply()
    }

    fun setTweaks(key: String, tweaks: Map<String, HudView.Tweak>) {
        forgetOldLayouts()
        val o = JSONObject()
        tweaks.forEach { (id, t) -> o.put(id, JSONArray().put(t.dx.toDouble()).put(t.dy.toDouble()).put(t.scale.toDouble())) }
        sp.edit().putString("layout.$key", o.toString()).apply()
    }

    /** The game you played last, for the home screen's Continue banner. */
    var lastGame: String?
        get() = sp.getString("lastGame", null)
        set(v) = sp.edit().putString("lastGame", v).apply()

    /** This phone's own id in rooms (made once): a player that dropped out gets its seat back. */
    val deviceId: String
        get() = sp.getString("deviceId", null)
            ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }

    /** Character ids in each team slot for a game ("" = empty). */
    fun team(gameId: String, size: Int): List<String?> {
        val ids = sp.getString("team.$gameId", "")!!.split(",")
        return List(size) { i -> ids.getOrNull(i)?.takeIf { it.isNotEmpty() } }
    }

    fun setTeam(gameId: String, ids: List<String?>) =
        sp.edit().putString("team.$gameId", ids.joinToString(",") { it ?: "" }).apply()

    fun active(gameId: String): Int = sp.getInt("active.$gameId", 0)

    fun setActive(gameId: String, index: Int) = sp.edit().putInt("active.$gameId", index).apply()

    /** Moves you edited for a character, or null if you never changed them. */
    fun customMoves(key: String): List<Move>? {
        val raw = sp.getString("moves.$key", null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Move(o.getString("name"), o.getString("cmd"), o.optString("type", "special"), o.optString("note"))
            }
        }.getOrNull()
    }

    fun setCustomMoves(key: String, moves: List<Move>?) {
        if (moves == null) {
            sp.edit().remove("moves.$key").apply()
            return
        }
        val arr = JSONArray()
        moves.forEach { arr.put(JSONObject().put("name", it.name).put("cmd", it.cmd).put("type", it.type).put("note", it.note)) }
        sp.edit().putString("moves.$key", arr.toString()).apply()
    }
}
