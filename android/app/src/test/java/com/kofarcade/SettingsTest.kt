package com.kofarcade

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Every setting is kept: each one changed, then read back through a new
 * Prefs (as the game screen reads them when it opens), and the Look the
 * game applies carries each of them.
 */
class SettingsTest {
    @get:Rule
    val paparazzi = Paparazzi()

    // The phone's settings file, in memory (layoutlib's own keeps nothing).
    private val files = HashMap<String, MemoryPrefs>()
    private val context: Context get() = object : ContextWrapper(paparazzi.context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = files.getOrPut(name) { MemoryPrefs() }
    }

    @Test
    fun everySettingIsSavedAndReadBack() {
        val p = Prefs(context)
        val changed = Look(
            ScreenMode.entries.last(), StickMode.entries.last(), 1.25f, 0.55f, LabelMode.entries.last(),
            haptics = false, leftHanded = true, stepFrames = 4, pressFrames = 9, rapidFire = false,
            sound = false, volume = 0.4f, chatBubbles = false, chatFilter = false, voices = false, voiceVolume = 0.3f, micOpen = false,
        )
        p.screen = changed.screen
        p.stick = changed.stick
        p.size = changed.size
        p.opacity = changed.opacity
        p.labelMode = changed.labelMode
        p.haptics = changed.haptics
        p.leftHanded = changed.leftHanded
        p.stepFrames = changed.stepFrames
        p.pressFrames = changed.pressFrames
        p.rapidFire = changed.rapidFire
        p.soundOn = changed.sound
        p.volume = changed.volume
        p.chatBubbles = changed.chatBubbles
        p.chatFilter = changed.chatFilter
        p.voices = changed.voices
        p.voiceVolume = changed.voiceVolume
        p.micOpen = changed.micOpen
        p.detectInfo = true
        p.problemReports = false
        p.openRooms = false
        p.mutedPlayers = setOf("phone-1", "phone-2")

        val again = Prefs(context)
        assertEquals(changed, again.look())
        assertEquals(true, again.detectInfo)
        assertEquals(false, again.problemReports)
        assertEquals(false, again.openRooms)
        assertEquals(setOf("phone-1", "phone-2"), again.mutedPlayers)
    }

    @Test
    fun aMovedButtonIsKeptPerKindOfGameAndHand() {
        val p = Prefs(context)
        val tweaks = mapOf("face:A" to HudView.Tweak(12f, -8f, 1.2f), "stick" to HudView.Tweak(-20f, 4f, 0.9f))
        p.setTweaks("FIGHTER.R", tweaks)
        assertEquals(tweaks, Prefs(context).tweaks("FIGHTER.R"))
        assertEquals(emptyMap<String, HudView.Tweak>(), Prefs(context).tweaks("FIGHTER.L"))
    }
}

/** A SharedPreferences in memory: what's put is what's got. */
private class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = (values[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
        }
    }
}
