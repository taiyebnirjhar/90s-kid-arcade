package com.kofarcade

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.kofarcade.core.online.Account
import com.kofarcade.core.online.KeyValue
import com.kofarcade.core.online.OnlineRooms
import com.kofarcade.core.online.RealtimeSignaling
import com.kofarcade.core.online.RelayServers
import com.kofarcade.core.online.Supabase
import com.kofarcade.core.online.SupabaseRooms
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
    @Volatile
    private var supabase: Supabase? = null

    fun account(context: Context): Account = account ?: synchronized(this) {
        account ?: run {
            val store = PrefsStore(context.applicationContext.getSharedPreferences("online", Context.MODE_PRIVATE))
            val supa = Supabase(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, store).also { supabase = it }
            Account(supa, store)
        }.also { account = it }
    }

    /**
     * Internet rooms (phase 1.5): made on the server, joined by code, the
     * phones connected over WebRTC, signaling on the room's Realtime channel.
     */
    fun rooms(context: Context): OnlineRooms {
        account(context)
        val supa = supabase!!
        return OnlineRooms(
            SupabaseRooms(supa),
            AndroidRtc.get(context),
            { code, me -> RealtimeSignaling(supa, code, me) },
            // Asked once now (making or joining the room is off the main thread), so the
            // connections' own thread never waits on the server for them.
            { code -> RelayServers(supa, code).also { it.get() }::get },
        )
    }

    /** Friends: requests, the list, presence and invites. */
    fun friends(context: Context): com.kofarcade.core.online.FriendServer {
        account(context)
        return com.kofarcade.core.online.SupabaseFriends(supabase!!)
    }

    /** Whether this phone has been online before (signed in, with a profile): nothing online is asked for otherwise. */
    fun signedIn(context: Context): Boolean = account(context).profile()?.pending == false

    /** Reporting a player (chat, voice, name, play). */
    fun reports(context: Context): com.kofarcade.core.online.Reports {
        account(context)
        return com.kofarcade.core.online.Reports(supabase!!)
    }

    // When each kind of problem was last sent: once in 2 minutes each is plenty.
    private val lastProblem = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Sends the connection log by itself when something went wrong ([kind]:
     * join, host, lost, ended), so the owner sees problems players never
     * report. Only on a phone that's been online before (no account is made
     * for it), unless the player turned it off (Settings); in the background,
     * and nothing is said if it can't go.
     */
    fun reportProblem(context: Context, kind: String, room: String?, error: Throwable? = null, reason: String? = null) {
        val app = context.applicationContext
        if (!Prefs(app).problemReports) return
        val now = System.currentTimeMillis()
        val last = lastProblem[kind]
        if (last != null && now - last < PROBLEM_EVERY_MS) return
        lastProblem[kind] = now
        val log = com.kofarcade.core.net.NetLog.dump()
        Thread({
            runCatching {
                if (!signedIn(app)) return@runCatching
                account(app)
                com.kofarcade.core.online.ProblemReports(supabase!!).send(
                    kind,
                    runCatching { emulatorBuild(app) }.getOrNull(),
                    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${BuildConfig.VERSION_NAME}",
                    room,
                    error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: reason,
                    log,
                )
            }
        }, "problem-report").apply { isDaemon = true }.start()
    }

    private const val PROBLEM_EVERY_MS = 120_000L

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

/**
 * A link that opens an internet room's code in the app (an Android link the
 * app takes), or, without the app, the website's page for it (get the app,
 * then join). Also the room's QR code.
 */
fun joinLink(code: String): String = "https://arcade.taiyebnirjhor.com/join.html?code=" + code.uppercase().filter { it.isLetterOrDigit() }

/** The code in a [joinLink] (or the website's "open in the app" link, kidarcade://join?code=…), or null if it isn't one. */
fun codeFromLink(uri: android.net.Uri?): String? {
    if (uri == null) return null
    // The site's own domain, or its old address (links shared before the move).
    val ours = (uri.scheme == "https" && uri.host == "arcade.taiyebnirjhor.com" && uri.path == "/join.html") ||
        (uri.scheme == "https" && uri.host == "taiyebnirjhar.github.io" && uri.path?.endsWith("/join.html") == true) ||
        (uri.scheme == "kidarcade" && uri.host == "join")
    if (!ours) return null
    return runCatching { uri.getQueryParameter("code") }.getOrNull()?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length == 6 }
}

/** A scanned QR code's room code: the room's link, or just its code (K7Q-2MX); null if it's something else. */
fun scannedRoomCode(raw: String?): String? {
    val text = raw?.trim() ?: return null
    if (text.contains("://")) return codeFromLink(runCatching { android.net.Uri.parse(text) }.getOrNull())
    return text.uppercase().filter { it.isLetterOrDigit() }.takeIf { it.length == 6 && text.length <= 8 }
}
