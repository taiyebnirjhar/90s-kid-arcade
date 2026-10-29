package com.kofarcade.core.online

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Icons in the app's own set (palette colors and shapes, no game art). */
const val PROFILE_ICONS = 16

/** Names are 1 to 24 characters, as the server checks. */
const val NAME_MAX = 24

/** A name as it's saved: one line, spaces trimmed, at most [NAME_MAX] characters. */
fun cleanName(name: String): String =
    name.replace(Regex("\\s+"), " ").trim().take(NAME_MAX).trim()

/**
 * Your profile as the phone keeps it. [friendCode] comes from the server
 * once it's saved there; [pending] means a change still has to go up.
 */
@Serializable
data class Profile(
    val name: String,
    val icon: Int,
    val friendCode: String? = null,
    val pending: Boolean = true,
    /** The guest it was saved for: a different one (the old one was lost) saves it again. */
    val savedFor: String? = null,
)

/**
 * Your profile and the games you've played (phase 1.3).
 *
 * The phone's copy is what shows: it's there with no internet, and the app
 * never waits on the network for it. [sync] signs in (the first time: a
 * guest, only when the player opens their profile or the Online screen,
 * never at app start), saves the profile and sends the play counts.
 *
 * Play counts are counted on the phone; the server keeps the highest it was
 * sent (a count never goes down).
 */
class Account(private val supa: Supabase, private val store: KeyValue, private val random: Random = Random.Default) {
    @Synchronized
    fun profile(): Profile? = store.get(PROFILE)?.let { runCatching { Json.decodeFromString<Profile>(it) }.getOrNull() }

    val signedIn: Boolean get() = supa.signedIn

    /** Games played per game id, on this phone. */
    @Synchronized
    fun plays(): Map<String, Int> = readCounts(PLAYS)

    /** A game started: counted now, sent with the next sync. */
    @Synchronized
    fun recordPlay(game: String) {
        val counts = plays().toMutableMap()
        counts[game] = (counts[game] ?: 0) + 1
        writeCounts(PLAYS, counts)
    }

    /** Whether something on the phone hasn't gone up yet. */
    @Synchronized
    fun dirty(): Boolean = profile()?.pending == true || plays() != readCounts(SENT)

    /** A new name or icon: kept on the phone now, saved by the next [sync]. */
    @Synchronized
    fun edit(name: String, icon: Int) {
        val clean = cleanName(name)
        require(clean.isNotEmpty()) { "A name, please" }
        require(icon in 0 until PROFILE_ICONS) { "No such icon: $icon" }
        val old = profile()
        save(Profile(clean, icon, old?.friendCode, pending = true, savedFor = old?.savedFor))
    }

    /**
     * Signs in if needed, then sends what's changed. The first time, the
     * profile is made with [defaultName] and a random icon, unless this
     * guest already has one on the server. Throws [OnlineError].
     */
    @Synchronized
    fun sync(defaultName: String): Profile {
        val user = supa.session().user
        var p = profile()
        if (p == null) {
            val remote = supa.select("profiles", mapOf("id" to "eq.$user"), "name,icon,friend_code") as JsonArray
            p = remote.firstOrNull()?.jsonObject?.let { fromServer(it, user) }
                ?: Profile(cleanName(defaultName).ifEmpty { "Player" }, random.nextInt(PROFILE_ICONS))
            save(p)
        }
        if (p.pending || p.friendCode == null || p.savedFor != user) {
            val saved = supa.rpc("save_profile", buildJsonObject {
                put("name", p.name)
                put("icon", p.icon)
            }).jsonObject
            p = fromServer(saved, user)
            save(p)
        }
        val counts = plays()
        if (counts.isNotEmpty() && (counts != readCounts(SENT) || p.savedFor != readSentFor())) {
            supa.rpc("record_plays", buildJsonObject { put("counts", JsonObject(counts.mapValues { (_, n) -> JsonPrimitive(n) })) })
            writeCounts(SENT, counts)
            store.put(SENT_FOR, user)
        }
        return p
    }

    private fun fromServer(o: JsonObject, user: String) = Profile(
        name = o["name"]!!.jsonPrimitive.content,
        icon = o["icon"]!!.jsonPrimitive.int.coerceIn(0, PROFILE_ICONS - 1),
        friendCode = o["friend_code"]?.jsonPrimitive?.contentOrNull,
        pending = false,
        savedFor = user,
    )

    private fun readSentFor(): String? = store.get(SENT_FOR)

    private fun save(p: Profile) = store.put(PROFILE, Json.encodeToString(Profile.serializer(), p))

    private fun readCounts(key: String): Map<String, Int> = store.get(key)?.let { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.int } }.getOrNull()
    }.orEmpty()

    private fun writeCounts(key: String, counts: Map<String, Int>) =
        store.put(key, JsonObject(counts.mapValues { JsonPrimitive(it.value) }).toString())

    private companion object {
        const val PROFILE = "online.profile"
        const val PLAYS = "online.plays"
        const val SENT = "online.playsSent"
        const val SENT_FOR = "online.playsSentFor"
    }
}
