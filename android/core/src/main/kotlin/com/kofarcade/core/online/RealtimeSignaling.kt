package com.kofarcade.core.online

import com.kofarcade.core.net.NetLog
import com.kofarcade.core.net.Signal
import com.kofarcade.core.net.Signaling
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A room's signaling over Supabase Realtime: the room's private channel
 * (`room:CODE`, which only its members may join: the database's rules),
 * carrying [Signal]s as broadcasts. Speaks Realtime's protocol (Phoenix
 * channels over a WebSocket) itself: join, heartbeat, a fresh token each
 * hour, and a reconnect with backoff when the socket drops. Signals sent
 * while it's down wait (a few seconds' worth) and go once it's back.
 */
class RealtimeSignaling(
    private val supa: Supabase,
    private val room: String,
    override val self: String,
    private val client: OkHttpClient = shared,
    /** The socket's address (tests: a fake server). */
    private val socketUrl: String = supa.url.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/') +
        "/realtime/v1/websocket?apikey=${supa.publicKey}&vsn=1.0.0",
) : Signaling {
    private val topic = "realtime:room:$room"
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "realtime").apply { isDaemon = true } }
    private val lock = Object()
    private var socket: WebSocket? = null
    private var joined = false
    private var joinRef = ""
    private var ref = 0
    private var attempt = 0
    private var token = ""
    @Volatile private var closed = false
    @Volatile private var handler: ((Signal) -> Unit)? = null
    private val waiting = ArrayDeque<Pair<Long, String>>()

    /** Why the channel can't be joined, if the server said (shown in the room's report). */
    @Volatile var problem: String? = null
        private set

    /** Whether the channel is joined now. */
    val ready: Boolean get() = synchronized(lock) { joined }

    init {
        timers.execute(::open)
        timers.scheduleAtFixedRate(::beat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    override fun listen(handler: (Signal) -> Unit) {
        this.handler = handler
    }

    override fun send(signal: Signal) {
        if (closed) throw IOException("Signaling closed")
        val payload = buildJsonObject {
            put("type", "broadcast")
            put("event", "signal")
            put("payload", Json.parseToJsonElement(signal.encode()))
        }
        synchronized(lock) {
            if (joined && push("broadcast", payload)) return
            // Down for now: it goes once the channel's back (or is dropped as stale).
            if (waiting.size >= MAX_WAITING) waiting.removeFirst()
            waiting.addLast(System.nanoTime() to payload.toString())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            if (joined) push("phx_leave", JsonObject(emptyMap()))
            socket?.close(1000, "bye")
            socket = null
            joined = false
        }
        timers.shutdownNow()
    }

    // ----- The socket -----

    private fun open() {
        if (closed) return
        try {
            token = supa.accessToken()
        } catch (e: OnlineError) {
            problem = explainOnline(e)
            retry()
            return
        }
        val ws = client.newWebSocket(Request.Builder().url(socketUrl).build(), Listener())
        synchronized(lock) { socket = ws }
    }

    private fun retry() {
        if (closed) return
        val delay = minOf(30_000L, 500L shl minOf(attempt++, 6))
        runCatching { timers.schedule(::open, delay, TimeUnit.MILLISECONDS) }
    }

    // Called with the lock held.
    private fun push(event: String, payload: JsonObject, topicOf: String = topic): Boolean {
        val ws = socket ?: return false
        val msg = buildJsonObject {
            put("topic", topicOf)
            put("event", event)
            put("payload", payload)
            put("ref", (++ref).toString())
            if (topicOf == topic) put("join_ref", joinRef)
        }
        return ws.send(msg.toString())
    }

    private fun join() = synchronized(lock) {
        joinRef = (++ref + 1).toString()
        push("phx_join", buildJsonObject {
            putJsonObject("config") {
                putJsonObject("broadcast") {
                    put("self", false)
                    put("ack", false)
                }
                putJsonObject("presence") { put("key", "") }
                put("private", true)
            }
            put("access_token", token)
        })
    }

    private fun beat() {
        if (closed) return
        synchronized(lock) {
            push("heartbeat", JsonObject(emptyMap()), "phoenix")
            // The token runs for an hour: the channel gets each new one.
            if (joined) {
                val fresh = runCatching { supa.accessToken() }.getOrNull()
                if (fresh != null && fresh != token) {
                    token = fresh
                    push("access_token", buildJsonObject { put("access_token", fresh) })
                }
            }
        }
    }

    private fun onText(text: String) {
        val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val event = m["event"]?.jsonPrimitive?.contentOrNull
        val payload = m["payload"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (m["topic"]?.jsonPrimitive?.contentOrNull != topic) return
        when (event) {
            "phx_reply" -> {
                if (m["ref"]?.jsonPrimitive?.contentOrNull != joinRef) return
                val ok = payload?.get("status")?.jsonPrimitive?.contentOrNull == "ok"
                if (ok) {
                    synchronized(lock) {
                        joined = true
                        attempt = 0
                        problem = null
                        val cutoff = System.nanoTime() - STALE_NS
                        while (waiting.isNotEmpty()) {
                            val (at, p) = waiting.removeFirst()
                            if (at >= cutoff) push("broadcast", Json.parseToJsonElement(p).jsonObject)
                        }
                    }
                    NetLog.add("signaling: in room $room")
                } else {
                    val reason = payload?.get("response")?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: "refused"
                    problem = reason
                    NetLog.add("signaling: couldn't join room $room: $reason")
                    // Not allowed (not a member, or the room closed): reconnecting won't change that soon.
                    synchronized(lock) { socket?.close(1000, "refused") }
                }
            }
            "broadcast" -> {
                val inner = payload?.get("payload")?.jsonObject ?: return
                val s = Signal.decode(inner.toString()) ?: return
                if (s.to == self) handler?.invoke(s)
            }
            "phx_error", "phx_close" -> {
                synchronized(lock) { joined = false }
                if (!closed) {
                    runCatching { timers.schedule(::join, 1_000, TimeUnit.MILLISECONDS) }
                }
            }
            "system" -> {
                val status = payload?.get("status")?.jsonPrimitive?.contentOrNull
                if (status == "error") problem = payload["message"]?.jsonPrimitive?.contentOrNull
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (synchronized(lock) { socket !== webSocket }) return
            join()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { socket !== webSocket }) return
            onText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The server's closing it (a restart, a deploy): agree, and come back.
            webSocket.close(1000, null)
            dropped(webSocket, "closed by the server ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t.message ?: "failed")

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, "closed ($code)")

        private fun dropped(webSocket: WebSocket, why: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                socket = null
                joined = false
            }
            if (closed) return
            NetLog.add("signaling: connection lost ($why), reconnecting")
            retry()
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 25_000L
        private const val MAX_WAITING = 200
        /** Signals older than this aren't worth sending once back (the other side has moved on). */
        private const val STALE_NS = 15_000_000_000L

        val shared: OkHttpClient by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    }
}
