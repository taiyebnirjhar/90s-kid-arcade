package com.kofarcade.core.online

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sign-in and the profile (phase 1.3) against a fake of the Supabase calls
 * the app makes, over real HTTP: the rules it follows are the migrations'
 * (server/supabase/tests checks those on Postgres).
 */
class AccountTest {
    /** The project: its guests, tokens, profiles and play counts. */
    private class FakeSupabase {
        val signUps = java.util.concurrent.atomic.AtomicInteger()
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val access = ConcurrentHashMap<String, String>() // token -> user
        val refresh = ConcurrentHashMap<String, String>()
        val profiles = ConcurrentHashMap<String, Triple<String, Int, String>>() // user -> name, icon, friend code
        val plays = ConcurrentHashMap<String, MutableMap<String, Int>>()
        var answer: Int? = null // every call answers this, when set
        var expiresIn = 3600
        private var n = 0

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val (status, out) = synchronized(this@FakeSupabase) {
                    if (ex.requestHeaders.getFirst("apikey") != KEY) 401 to """{"message":"No API key"}"""
                    else answer?.let { it to """{"message":"Too many requests"}""" }
                        ?: handle(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(), body, ex.requestHeaders.getFirst("Authorization"))
                }
                val bytes = out.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"

        private fun session(user: String): Pair<Int, String> {
            n++
            val a = "a$n"
            val r = "r$n"
            access[a] = user
            refresh[r] = user
            return 200 to """{"access_token":"$a","refresh_token":"$r","expires_in":$expiresIn,"token_type":"bearer","user":{"id":"$user"}}"""
        }

        private fun handle(method: String, path: String, query: String, body: String, auth: String?): Pair<Int, String> {
            if (path == "/auth/v1/signup" && method == "POST") {
                signUps.incrementAndGet()
                return session("user-${signUps.get()}")
            }
            if (path == "/auth/v1/token" && query == "grant_type=refresh_token") {
                refreshes.incrementAndGet()
                val token = Json.parseToJsonElement(body).jsonObject["refresh_token"]!!.jsonPrimitive.content
                // Refresh tokens are used once.
                val user = refresh.remove(token) ?: return 400 to """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""
                return session(user)
            }
            val user = auth?.removePrefix("Bearer ")?.let(access::get) ?: return 401 to """{"message":"JWT expired"}"""
            return when {
                path == "/rest/v1/profiles" && method == "GET" -> {
                    val id = Regex("id=eq\\.([^&]+)").find(query)!!.groupValues[1]
                    val p = profiles[id]
                    200 to if (p == null) "[]" else """[{"name":"${p.first}","icon":${p.second},"friend_code":"${p.third}"}]"""
                }
                path == "/rest/v1/rpc/save_profile" -> {
                    val o = Json.parseToJsonElement(body).jsonObject
                    val name = o["name"]!!.jsonPrimitive.content.trim()
                    val icon = o["icon"]!!.jsonPrimitive.int
                    if (name.length !in 1..24 || icon !in 0..63) return 400 to """{"code":"23514","message":"check violation"}"""
                    val code = profiles[user]?.third ?: "FC${profiles.size}"
                    profiles[user] = Triple(name, icon, code)
                    200 to """{"id":"$user","name":"$name","icon":$icon,"friend_code":"$code"}"""
                }
                path == "/rest/v1/rpc/record_plays" -> {
                    val counts = Json.parseToJsonElement(body).jsonObject["counts"]!!.jsonObject
                    val mine = plays.getOrPut(user) { mutableMapOf() }
                    counts.forEach { (g, c) -> mine[g] = maxOf(mine[g] ?: 0, c.jsonPrimitive.int) }
                    204 to ""
                }
                else -> 404 to """{"message":"no route $method $path"}"""
            }
        }
    }

    private class Prefs : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val fake = FakeSupabase()
    private val phone = Prefs()
    private var clock = 1_000_000L

    @AfterTest
    fun stop() = fake.server.stop(0)

    private fun account(url: String = fake.url) = Account(Supabase(url, KEY, phone, now = { clock }), phone, Random(7))

    @Test
    fun nothingHappensUntilAsked() {
        val a = account()
        assertFalse(a.signedIn)
        assertNull(a.profile())
        a.recordPlay("kof97")
        assertEquals(0, fake.signUps.get(), "counting a game played signs nobody in")
    }

    @Test
    fun signsInOnceAndMakesTheProfile() {
        val a = account()
        val p = a.sync("Rafi")
        assertTrue(a.signedIn)
        assertEquals("Rafi", p.name)
        assertEquals("FC0", p.friendCode)
        assertFalse(p.pending)
        assertEquals(Triple("Rafi", p.icon, "FC0"), fake.profiles["user-1"])
        a.sync("Rafi")
        assertEquals(1, fake.signUps.get())
    }

    @Test
    fun theSameGuestAfterARestart() {
        account().sync("Rafi")
        // The app starts again: everything comes from the phone.
        val again = account()
        assertTrue(again.signedIn)
        assertEquals("Rafi", again.profile()?.name)
        again.sync("Someone else")
        assertEquals(1, fake.signUps.get())
        assertEquals(1, fake.profiles.size)
    }

    @Test
    fun anExpiringSessionIsRefreshed() {
        val a = account()
        a.sync("Rafi")
        clock += 3_600_000 - 30_000 // under a minute left
        a.edit("Rafi K", 2)
        a.sync("Rafi")
        assertEquals(1, fake.refreshes.get())
        assertEquals(1, fake.signUps.get())
        assertEquals("Rafi K", fake.profiles["user-1"]?.first)
    }

    @Test
    fun aTokenTheServerDropsIsRefreshedOnce() {
        val a = account()
        a.sync("Rafi")
        fake.access.clear() // e.g. the server's keys were rotated
        a.edit("Rafi K", 2)
        assertEquals("Rafi K", a.sync("Rafi").name)
        assertEquals(1, fake.refreshes.get())
    }

    @Test
    fun aLostGuestStartsAgainWithThePhonesProfileAndCounts() {
        val a = account()
        a.recordPlay("kof97")
        a.edit("Rafi", 4)
        a.sync("x")
        fake.refresh.clear() // the guest was removed on the server
        clock += 4_000_000
        val p = a.sync("x")
        assertEquals(2, fake.signUps.get())
        assertEquals("Rafi", fake.profiles["user-2"]?.first, "the profile is saved again for the new guest")
        assertEquals(4, p.icon)
        assertEquals("user-2", p.savedFor)
        assertEquals(mapOf("kof97" to 1), fake.plays["user-2"]?.toMap(), "and the counts sent again")
    }

    @Test
    fun aNewPhoneFindsTheGuestsProfile() {
        // Signed in, but the phone's copy of the profile is gone.
        account().sync("Rafi")
        phone.map.remove("online.profile")
        val p = account().sync("Default")
        assertEquals("Rafi", p.name)
        assertEquals(1, fake.profiles.size)
    }

    @Test
    fun editsWaitOnThePhoneUntilSynced() {
        val a = account()
        a.sync("Rafi")
        a.edit("  Rafi   the  Great  ", 9)
        assertEquals(Profile("Rafi the Great", 9, "FC0", pending = true, savedFor = "user-1"), a.profile())
        assertTrue(a.dirty())
        a.sync("Rafi")
        assertFalse(a.dirty())
        assertEquals("Rafi the Great", fake.profiles["user-1"]?.first)
        assertFailsWith<IllegalArgumentException> { a.edit("   ", 1) }
        assertFailsWith<IllegalArgumentException> { a.edit("Rafi", PROFILE_ICONS) }
        assertEquals(NAME_MAX, cleanName("x".repeat(40)).length)
    }

    @Test
    fun playsAreCountedOnThePhoneAndSent() {
        val a = account()
        repeat(3) { a.recordPlay("kof97") }
        a.recordPlay("dino")
        a.sync("Rafi")
        assertEquals(mapOf("kof97" to 3, "dino" to 1), fake.plays["user-1"]?.toMap())
        assertFalse(a.dirty())
        a.recordPlay("dino")
        assertTrue(a.dirty())
        a.sync("Rafi")
        assertEquals(2, fake.plays["user-1"]?.get("dino"))
    }

    @Test
    fun offlineStartShowsThePhonesCopy() {
        account().sync("Rafi")
        fake.server.stop(0)
        val a = account()
        a.recordPlay("kof97")
        assertEquals("Rafi", a.profile()?.name, "no network needed")
        a.edit("Rafi K", 1)
        val e = assertFailsWith<OnlineError.Offline> { a.sync("Rafi") }
        assertTrue(explainOnline(e).startsWith("No internet"))
        assertEquals("Rafi K", a.profile()?.name)
        assertTrue(a.dirty(), "kept for later")
    }

    @Test
    fun neverOnlineAndOffline() {
        val a = account("http://127.0.0.1:1")
        assertFailsWith<OnlineError.Offline> { a.sync("Rafi") }
        assertFalse(a.signedIn)
        assertNull(a.profile())
    }

    @Test
    fun aServerAtItsLimitPausesOnline() {
        fake.answer = 429
        val e = assertFailsWith<OnlineError.Paused> { account().sync("Rafi") }
        assertTrue(explainOnline(e).startsWith("Online is paused"))
        fake.answer = null
        assertEquals("Rafi", account().sync("Rafi").name)
    }

    @Test
    fun guestSignInSwitchedOff() {
        val s = Supabase(fake.url, KEY, phone, http = { _, _, _, _ ->
            HttpResponse(422, """{"code":422,"error_code":"anonymous_provider_disabled","msg":"Anonymous sign-ins are disabled"}""")
        })
        val e = assertFailsWith<OnlineError.Refused> { Account(s, phone).sync("Rafi") }
        assertEquals("Guest sign-in is switched off on the server.", explainOnline(e))
    }

    @Test
    fun aRefusedSaveIsNotLost() {
        val a = account()
        a.sync("Rafi")
        // Sent around the app's own check, as a newer server might refuse something.
        phone.put("online.profile", Json.encodeToString(Profile.serializer(), Profile("x".repeat(30), 1, "FC0", pending = true, savedFor = "user-1")))
        val e = assertFailsWith<OnlineError.Refused> { a.sync("Rafi") }
        assertEquals(400, e.status)
        assertTrue(a.dirty())
    }

    @Test
    fun theServersRulesAreTheMigrations() {
        // What the fake answers for save_profile and record_plays matches the
        // database functions' arguments.
        val sql = java.io.File("../../server/supabase/migrations/20261030000000_profile_and_plays.sql").readText()
        assertTrue("save_profile(name text, icon int)" in sql)
        assertTrue("record_plays(counts jsonb)" in sql)
        assertTrue("icon between 0 and 63" in java.io.File("../../server/supabase/migrations/20261029000000_online.sql").readText())
        assertTrue(PROFILE_ICONS <= 64)
        assertNotEquals(0, buildJsonObject { put("n", 1) }.size)
    }

    private companion object {
        const val KEY = "sb_publishable_test"
    }
}
