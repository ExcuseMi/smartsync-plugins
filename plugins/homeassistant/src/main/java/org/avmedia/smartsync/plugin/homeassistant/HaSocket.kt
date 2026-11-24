package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HaChannel] on OkHttp: opens `/api/websocket`, authenticates with the token, matches results to calls by id and
 * reconnects after a drop (5 s doubling to 5 minutes; a refused token waits the longest). The token is never logged.
 */
class HaSocket(private val config: BrokerConfig, private val listener: HaChannel.Listener) : HaChannel {
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HaReply>>()
    private val events = ConcurrentHashMap<Int, (Map<String, Any?>) -> Unit>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var authed = false
    @Volatile private var stopped = false
    private var retry: ScheduledExecutorService? = null
    private var delaySec = FIRST_RETRY_SEC

    override val ready: Boolean get() = authed && socket != null

    override fun start() {
        stopped = false
        retry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ha-plugin-retry").apply { isDaemon = true } }
        open()
    }

    override fun stop() {
        stopped = true
        retry?.shutdownNow()
        retry = null
        socket?.close(1000, "plugin stopped")
        socket = null
        authed = false
        failAll("plugin stopped")
    }

    private fun open() {
        if (stopped) return
        val url = config.haWebsocketUrl() ?: return listener.onLost("not an http(s) address")
        val request = Request.Builder().url(url).build()
        socket = CLIENT.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, config.scrub(t.message ?: t.javaClass.simpleName), false)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed ($code)", false)
        })
    }

    private fun handle(ws: WebSocket, text: String) {
        val m = JsonReader.obj(text) ?: return
        when (m["type"]) {
            "auth_required" -> ws.send(Json.write(mapOf("type" to "auth", "access_token" to config.haToken)))
            "auth_ok" -> {
                authed = true
                delaySec = FIRST_RETRY_SEC
                listener.onReady()
            }
            "auth_invalid" -> {
                delaySec = MAX_RETRY_SEC
                ws.close(1000, "auth refused")
                lost(ws, "Home Assistant refused the token", true)
            }
            "result" -> {
                val id = (m["id"] as? Number)?.toInt() ?: return
                @Suppress("UNCHECKED_CAST")
                val err = m["error"] as? Map<String, Any?>
                val reply = if (m["success"] == true) HaReply(true, m["result"]) else HaReply(false, null, err?.get("code") as? String, err?.get("message") as? String)
                pending.remove(id)?.complete(reply)
                // A refused subscription: mqtt/subscribe needs an administrator's token.
                if (!reply.success && events.remove(id) != null && reply.code == "unauthorized") listener.onLost(ADMIN_NEEDED, true)
            }
            "event" -> {
                val id = (m["id"] as? Number)?.toInt() ?: return
                @Suppress("UNCHECKED_CAST")
                (m["event"] as? Map<String, Any?>)?.let { e -> events[id]?.invoke(e) }
            }
        }
    }

    private fun lost(ws: WebSocket, reason: String, authRefused: Boolean) {
        if (socket !== ws) return
        socket = null
        authed = false
        events.clear()
        failAll(reason)
        listener.onLost(reason, authRefused)
        if (!stopped) {
            val d = delaySec
            delaySec = (delaySec * 2).coerceAtMost(MAX_RETRY_SEC)
            runCatching { retry?.schedule({ open() }, d, TimeUnit.SECONDS) }
        }
    }

    private fun failAll(reason: String) {
        pending.values.forEach { it.complete(HaReply.failed("not_connected", reason)) }
        pending.clear()
    }

    override suspend fun call(message: Map<String, Any?>): HaReply {
        val ws = socket
        if (ws == null || !authed) return HaReply.failed("not_connected", "Home Assistant is not connected")
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<HaReply>()
        pending[id] = d
        if (!ws.send(Json.write(mapOf("id" to id) + message))) {
            pending.remove(id)
            return HaReply.failed("not_connected", "send failed")
        }
        return withTimeoutOrNull(CALL_TIMEOUT_MS) { d.await() } ?: run {
            pending.remove(id)
            HaReply.failed("timeout", "no answer from Home Assistant")
        }
    }

    override fun send(message: Map<String, Any?>): Boolean {
        val ws = socket ?: return false
        if (!authed) return false
        return ws.send(Json.write(mapOf("id" to ids.incrementAndGet()) + message))
    }

    override fun subscribe(message: Map<String, Any?>, onEvent: (Map<String, Any?>) -> Unit): Boolean {
        val ws = socket ?: return false
        if (!authed) return false
        val id = ids.incrementAndGet()
        events[id] = onEvent
        return ws.send(Json.write(mapOf("id" to id) + message))
    }

    companion object {
        const val FIRST_RETRY_SEC = 5L
        const val MAX_RETRY_SEC = 300L
        const val CALL_TIMEOUT_MS = 30_000L
        const val ADMIN_NEEDED = "The token must belong to a Home Assistant administrator (mqtt/subscribe and statistics need one)"

        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()
        }
    }
}
