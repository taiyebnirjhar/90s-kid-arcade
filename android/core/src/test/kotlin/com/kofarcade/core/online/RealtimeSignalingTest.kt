package com.kofarcade.core.online

import com.kofarcade.core.net.Signal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signaling over a fake of Supabase Realtime (its Phoenix protocol over a
 * real WebSocket): joining the room's private channel, broadcasts to the
 * others, a refused join, and a dropped socket coming back. The real
 * server is checked by LiveServerTest (KOF_LIVE=1).
 */
class RealtimeSignalingTest {
    /** The project: guests sign up, and the room's channel relays broadcasts to the others in it. */
    private inner class FakeRealtime : Dispatcher() {
        val joined = CopyOnWriteArrayList<Pair<WebSocket, String>>()
        /** Every socket's token (the guest it's for), to drop one. */
        val tokens = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()
        val joins = AtomicInteger()
        /** Topics nobody may join (a stranger's room). */
        val forbidden = mutableSetOf<String>()
        private val users = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path.startsWith("/auth/v1/signup")) {
                val n = users.incrementAndGet()
                return MockResponse().setBody("""{"access_token":"t$n","refresh_token":"r$n","expires_in":3600,"user":{"id":"user-$n"}}""")
            }
            if (path.startsWith("/realtime/v1/websocket")) return MockResponse().withWebSocketUpgrade(Socket())
            return MockResponse().setResponseCode(404)
        }

        inner class Socket : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = Json.parseToJsonElement(text).jsonObject
                val topic = m["topic"]!!.jsonPrimitive.content
                val ref = m["ref"]?.jsonPrimitive?.contentOrNull
                when (m["event"]!!.jsonPrimitive.content) {
                    "phx_join" -> {
                        joins.incrementAndGet()
                        m["payload"]?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull?.let { tokens[webSocket] = it }
                        val ok = topic !in forbidden
                        if (ok) joined += webSocket to topic
                        webSocket.send(buildJsonObject {
                            put("topic", topic)
                            put("event", "phx_reply")
                            put("ref", ref)
                            putJsonObject("payload") {
                                put("status", if (ok) "ok" else "error")
                                putJsonObject("response") { if (!ok) put("reason", "Unauthorized: You do not have permissions to read from this Channel topic") }
                            }
                        }.toString())
                    }
                    "broadcast" -> {
                        val out = buildJsonObject {
                            put("topic", topic)
                            put("event", "broadcast")
                            put("payload", m["payload"]!!)
                        }.toString()
                        // To the others in the channel, not back to the sender (self: false).
                        joined.filter { it.second == topic && it.first !== webSocket }.forEach { it.first.send(out) }
                    }
                    "heartbeat" -> webSocket.send("""{"topic":"phoenix","event":"phx_reply","ref":"$ref","payload":{"status":"ok","response":{}}}""")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                joined.removeIf { it.first === webSocket }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                joined.removeIf { it.first === webSocket }
            }
        }
    }

    private class Mem : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }
    }

    private val realtime = FakeRealtime()
    private val server = MockWebServer().apply {
        dispatcher = realtime
        start()
    }
    private val opened = ArrayList<RealtimeSignaling>()

    @AfterTest
    fun stop() {
        opened.forEach { it.close() }
        realtime.tokens.keys.forEach { runCatching { it.close(1001, "bye") } }
        runCatching { server.shutdown() }
    }

    private fun phone(room: String = "K7Q2MX"): RealtimeSignaling {
        val supa = Supabase(server.url("/").toString(), "key", Mem())
        val id = supa.session().user
        val ws = server.url("/realtime/v1/websocket").toString().replace("http", "ws")
        return RealtimeSignaling(supa, room, id, socketUrl = ws).also { opened += it }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun signalsReachTheOnePhoneTheyreFor() {
        val a = phone()
        val b = phone()
        val c = phone()
        val toB = LinkedBlockingQueue<Signal>()
        val toC = LinkedBlockingQueue<Signal>()
        b.listen { toB.put(it) }
        c.listen { toC.put(it) }
        waitFor("joined") { a.ready && b.ready && c.ready }
        a.send(Signal(Signal.OFFER, a.self, b.self, gen = 3, sdp = "v=0"))
        val got = assertNotNull(toB.poll(5, TimeUnit.SECONDS))
        assertEquals(Signal.OFFER, got.kind)
        assertEquals("v=0", got.sdp)
        assertEquals(3, got.gen)
        assertNull(toC.poll(300, TimeUnit.MILLISECONDS), "only the phone it's addressed to")
    }

    @Test
    fun aStrangerIsRefusedAndSaysWhy() {
        realtime.forbidden += "realtime:room:SECRET"
        val s = phone("SECRET")
        waitFor("refused") { s.problem != null }
        assertTrue(!s.ready)
        assertTrue("permissions" in s.problem!!, s.problem)
    }

    @Test
    fun signalsSentBeforeJoiningWaitAndGo() {
        val b = phone()
        val toB = LinkedBlockingQueue<Signal>()
        b.listen { toB.put(it) }
        waitFor("b joined") { b.ready }
        val a = phone()
        // At once, before a has joined: it waits, then goes.
        a.send(Signal(Signal.HELLO, a.self, b.self))
        assertNotNull(toB.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun aDroppedSocketComesBackAndJoinsAgain() {
        val a = phone()
        val b = phone()
        val toB = LinkedBlockingQueue<Signal>()
        b.listen { toB.put(it) }
        waitFor("joined") { a.ready && b.ready }
        val before = realtime.joins.get()
        // The server drops b's socket (a network change, a server restart).
        realtime.tokens.entries.first { it.value == "t2" }.key.close(1012, "restarting")
        waitFor("rejoined") { realtime.joins.get() > before && b.ready && realtime.joined.size == 2 }
        a.send(Signal(Signal.ANSWER, a.self, b.self, sdp = "x"))
        assertNotNull(toB.poll(5, TimeUnit.SECONDS))
    }
}
