package com.kofarcade.core.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder

/** What the app keeps on the phone (SharedPreferences there, a map in tests). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Why an online call didn't go through. */
sealed class OnlineError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No internet, or the server didn't answer. */
    class Offline(cause: IOException) : OnlineError("No connection: ${cause.message}", cause)

    /**
     * A free allowance ran out (D8: the feature pauses, nothing is billed):
     * too many sign-ins or calls, or the project is over its limits or paused.
     */
    class Paused(val status: Int, message: String) : OnlineError(message)

    /** The server said no: [code] is its error code, if it gave one. */
    class Refused(val status: Int, val code: String?, message: String) : OnlineError(message)
}

/** In plain words, for the screen. */
fun explainOnline(e: Throwable): String = when (e) {
    is OnlineError.Offline -> "No internet right now. Your profile shows from this phone and syncs when you're back online."
    is OnlineError.Paused -> "Online is paused for now: the free server's limit was reached. Nearby play still works. Try again later."
    is OnlineError.Refused -> when (e.code) {
        "anonymous_provider_disabled" -> "Guest sign-in is switched off on the server."
        // The database's own words: "The room is full: 8 phones at most", "You have 3 rooms open already",
        // "This room was made locked: make an open room for anyone to join".
        "53400", "P0002", "22023" -> e.message.orEmpty()
        else -> "The server said no (${e.status}): ${e.message}"
    }
    else -> "Something went wrong: ${e.message}"
}

/** A signed-in guest: tokens from Supabase Auth, kept on the phone. */
data class AuthSession(val user: String, val access: String, val refresh: String, val expiresAtMs: Long)

/**
 * The few Supabase calls the app makes, over plain HTTP: guest sign-in
 * (Auth), and database functions and reads (PostgREST). Only the project's
 * public key is here; what a player may do is decided by the database's
 * rules (server/supabase/migrations).
 *
 * The session is kept in [store], so the same guest comes back after a
 * restart. Blocking: call it off the main thread.
 */
class Supabase(
    val url: String,
    private val key: String,
    private val store: KeyValue,
    private val http: Http = JdkHttp,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var session: AuthSession? = load()

    /** Whether this phone has signed in before (no network needed to tell). */
    val signedIn: Boolean @Synchronized get() = session != null

    val userId: String? @Synchronized get() = session?.user

    /**
     * A session good for at least a minute: the kept one, refreshed if it's
     * running out, or a new guest the first time. A guest whose refresh token
     * the server no longer knows (it was revoked or the user was removed) is
     * lost for good; a new one is made, and [userId] changes.
     */
    @Synchronized
    fun session(): AuthSession {
        val s = session ?: return signUp()
        if (s.expiresAtMs - now() > 60_000) return s
        return try {
            keep(auth("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", s.refresh) }))
        } catch (e: OnlineError.Refused) {
            if (e.status !in 400..404) throw e
            session = null
            store.put(SESSION, null)
            signUp()
        }
    }

    private fun signUp(): AuthSession = keep(auth("/auth/v1/signup", JsonObject(emptyMap())))

    private fun auth(path: String, body: JsonObject): JsonObject =
        Json.parseToJsonElement(call("POST", path, body.toString(), token = null)).jsonObject

    private fun keep(o: JsonObject): AuthSession {
        val s = AuthSession(
            user = o["user"]!!.jsonObject["id"]!!.jsonPrimitive.content,
            access = o["access_token"]!!.jsonPrimitive.content,
            refresh = o["refresh_token"]!!.jsonPrimitive.content,
            expiresAtMs = now() + o["expires_in"]!!.jsonPrimitive.long * 1000,
        )
        session = s
        store.put(SESSION, Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("user", s.user)
            put("access", s.access)
            put("refresh", s.refresh)
            put("expiresAtMs", s.expiresAtMs)
        }))
        return s
    }

    private fun load(): AuthSession? = store.get(SESSION)?.let { raw ->
        runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            AuthSession(
                o["user"]!!.jsonPrimitive.content,
                o["access"]!!.jsonPrimitive.content,
                o["refresh"]!!.jsonPrimitive.content,
                o["expiresAtMs"]!!.jsonPrimitive.long,
            )
        }.getOrNull()
    }

    /** The signed-in guest's token now (refreshed if it's running out): for Realtime. */
    fun accessToken(): String = session().access

    /** The project's public key. */
    val publicKey: String get() = key

    /** An Edge Function, as the signed-in guest; its answer as JSON. */
    fun function(name: String, body: JsonObject): JsonElement =
        Json.parseToJsonElement(signedInCall("POST", "/functions/v1/$name", body.toString()).ifEmpty { "null" })

    /** A database function, as the signed-in guest; its result as JSON. */
    fun rpc(name: String, args: JsonObject): JsonElement = Json.parseToJsonElement(signedInCall("POST", "/rest/v1/rpc/$name", args.toString()).ifEmpty { "null" })

    /** Rows of [table] matching [filter] (PostgREST's: "id=eq.…"), as the signed-in guest. */
    fun select(table: String, filter: Map<String, String>, columns: String = "*"): JsonElement {
        val query = (filter + ("select" to columns)).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return Json.parseToJsonElement(signedInCall("GET", "/rest/v1/$table?$query", null))
    }

    private fun signedInCall(method: String, path: String, body: String?): String {
        val s = session()
        return try {
            call(method, path, body, s.access)
        } catch (e: OnlineError.Refused) {
            // A token the server stopped taking (signed out elsewhere, keys
            // rotated): once more with a fresh one.
            if (e.status != 401) throw e
            synchronized(this) { if (session == s) session = s.copy(expiresAtMs = 0) }
            call(method, path, body, session().access)
        }
    }

    private fun call(method: String, path: String, body: String?, token: String?): String {
        val headers = buildMap {
            put("apikey", key)
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val r = try {
            http.send(method, url.trimEnd('/') + path, headers, body)
        } catch (e: IOException) {
            throw OnlineError.Offline(e)
        }
        if (r.status in 200..299) return r.body
        val err = runCatching { Json.parseToJsonElement(r.body).jsonObject }.getOrNull()
        fun field(vararg names: String) = names.firstNotNullOfOrNull { err?.get(it)?.jsonPrimitive?.contentOrNull }
        val message = field("msg", "message", "error_description", "error") ?: r.body.take(200).ifEmpty { "HTTP ${r.status}" }
        val code = field("error_code", "code")
        // 402: over the plan's limits; 429: too many requests; 503 and 540: the project is down or paused.
        // But a database function's own refusal (a room full: SQL state 53400, which the API answers
        // with 503) says what it is, in its own words.
        if (r.status in PAUSED && (code == null || !SQL_STATE.matches(code))) throw OnlineError.Paused(r.status, message)
        throw OnlineError.Refused(r.status, code, message)
    }

    private companion object {
        const val SESSION = "online.session"
        val PAUSED = setOf(402, 429, 503, 540)
        val SQL_STATE = Regex("[0-9A-Z]{5}")
    }
}
