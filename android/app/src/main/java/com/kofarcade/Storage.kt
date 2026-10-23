package com.kofarcade

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kofarcade.core.Game
import com.kofarcade.core.Move
import com.kofarcade.core.setName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ROM zips, copied into the app's private storage when you pick them. They
 * never leave the phone. FBNeo identifies a game by its zip name, so files are
 * stored under the set name it expects ("King of Fighters 97.zip" -> kof97.zip).
 */
class RomStore(private val context: Context) {
    val dir: File = File(context.filesDir, "roms").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun has(name: String) = file(name).let { it.isFile && it.length() > 0 }

    /** The zip to boot for [game]: the first of its sets that is present. */
    fun setFor(game: Game): String? = game.sets.firstOrNull { has("$it.zip") }

    /**
     * Copies the picked file in. If its name is one of [accept] (a set name)
     * it keeps that name, otherwise it is stored as [storeAs].
     */
    fun import(uri: Uri, storeAs: String, accept: List<String> = emptyList()): String {
        val picked = displayName(uri)?.let(::setName)
        val name = if (picked != null && picked in accept) "$picked.zip" else storeAs
        val tmp = File(dir, "$name.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Can't read the picked file" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        require(tmp.length() > 0) { "The picked file is empty" }
        tmp.renameTo(file(name))
        return name
    }

    fun remove(name: String) {
        file(name).delete()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}

enum class StickMode(val label: String) {
    FLOATING("Floating: appears under your thumb"),
    FIXED("Fixed: always shown"),
    HIDDEN("Hidden: works, not drawn"),
}

enum class LabelMode(val label: String) {
    SUPERS("Supers only"),
    ALL("All moves"),
    OFF("None"),
}

enum class ScreenMode(val label: String) {
    FULL("Full screen (stretched)"),
    ORIGINAL("Original 4:3"),
}

/** Settings, teams and custom move lists. */
class Prefs(context: Context) {
    // Keeps its original file name (from when the app was "KOF Arcade") so
    // settings survive the rename.
    private val sp = context.getSharedPreferences("kof-arcade", Context.MODE_PRIVATE)

    var stick: StickMode
        get() = runCatching { StickMode.valueOf(sp.getString("stick", null)!!) }.getOrDefault(StickMode.FLOATING)
        set(v) = sp.edit().putString("stick", v.name).apply()

    var size: Float
        get() = sp.getFloat("size", 1f)
        set(v) = sp.edit().putFloat("size", v).apply()

    var opacity: Float
        get() = sp.getFloat("opacity", 0.8f)
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

    var screen: ScreenMode
        get() = runCatching { ScreenMode.valueOf(sp.getString("screen", null)!!) }.getOrDefault(ScreenMode.FULL)
        set(v) = sp.edit().putString("screen", v.name).apply()

    /** Your control layout for a kind of game (fighter / shooter / brawler) and hand. */
    fun tweaks(key: String): Map<String, HudView.Tweak> {
        val raw = sp.getString("layout.$key", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { id ->
                val a = o.getJSONArray(id)
                HudView.Tweak(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
        }.getOrDefault(emptyMap())
    }

    fun setTweaks(key: String, tweaks: Map<String, HudView.Tweak>) {
        val o = JSONObject()
        tweaks.forEach { (id, t) -> o.put(id, JSONArray().put(t.dx.toDouble()).put(t.dy.toDouble()).put(t.scale.toDouble())) }
        sp.edit().putString("layout.$key", o.toString()).apply()
    }

    /** The game you played last, for the home screen's Continue banner. */
    var lastGame: String?
        get() = sp.getString("lastGame", null)
        set(v) = sp.edit().putString("lastGame", v).apply()

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
                Move(o.getString("name"), o.getString("cmd"), o.optString("type", "special"))
            }
        }.getOrNull()
    }

    fun setCustomMoves(key: String, moves: List<Move>?) {
        if (moves == null) {
            sp.edit().remove("moves.$key").apply()
            return
        }
        val arr = JSONArray()
        moves.forEach { arr.put(JSONObject().put("name", it.name).put("cmd", it.cmd).put("type", it.type)) }
        sp.edit().putString("moves.$key", arr.toString()).apply()
    }
}
