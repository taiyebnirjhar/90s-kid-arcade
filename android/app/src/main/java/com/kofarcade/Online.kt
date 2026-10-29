package com.kofarcade

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.kofarcade.core.online.Account
import com.kofarcade.core.online.KeyValue
import com.kofarcade.core.online.Supabase
import kotlin.random.Random

/**
 * The app's one [Account] (online play's sign-in, profile and play counts),
 * kept in its own preferences file. Making it touches no network: a guest
 * is signed in only when the player opens their profile (later, the Online
 * screen), never at app start.
 */
object Online {
    @Volatile
    private var account: Account? = null

    fun account(context: Context): Account = account ?: synchronized(this) {
        account ?: run {
            val store = PrefsStore(context.applicationContext.getSharedPreferences("online", Context.MODE_PRIVATE))
            Account(Supabase(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, store), store)
        }.also { account = it }
    }

    /** A first profile's name, until the player picks one. */
    fun defaultName(): String = "Player ${Random.nextInt(1000, 10000)}"

    /** The name others see in a room: the profile's, else the phone's model, as before profiles. */
    fun playerName(context: Context): String =
        account(context).profile()?.name ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

    private class PrefsStore(private val sp: SharedPreferences) : KeyValue {
        override fun get(key: String): String? = sp.getString(key, null)

        // commit, not apply: a session or count must be on disk before the
        // next call reads it back after a restart.
        override fun put(key: String, value: String?) {
            sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
        }
    }
}
