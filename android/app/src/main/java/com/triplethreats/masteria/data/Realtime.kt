package com.triplethreats.masteria.data

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The socket isn't open (or closed mid-request): the caller should use plain HTTP instead. */
class RealtimeUnavailable : Exception("realtime connection unavailable")

/**
 * One persistent WebSocket to `/ws` that carries every API call as `{id, method, path, body}` and gets
 * `{type:"response", id, status, body|error}` back. Saves the TCP + TLS handshake and HTTP overhead of
 * each call (noticeable against a Render deployment), streams mentor replies, and receives "changed"
 * pushes when the same account makes progress on another device. Reconnects with backoff.
 */
class RealtimeClient(
    baseHttp: OkHttpClient,
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val onChanged: () -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    enum class State { Disconnected, Connecting, Connected }

    private val http = baseHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)      // a socket is idle between calls; pings detect dead links
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val unary = ConcurrentHashMap<String, CompletableDeferred<WsFrame>>()
    private val streams = ConcurrentHashMap<String, SendChannel<WsFrame>>()

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()
    val isConnected: Boolean get() = _state.value == State.Connected

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var attempt = 0
    private var reconnectJob: Job? = null

    /** Opens the socket if signed in and not already open. Safe to call often. */
    @Synchronized
    fun connect() {
        wanted = true
        if (socket != null || token() == null) return
        reconnectJob?.cancel(); reconnectJob = null
        open()
    }

    /** Closes the socket and stops reconnecting (sign-out, app in background). */
    @Synchronized
    fun disconnect() {
        wanted = false
        reconnectJob?.cancel(); reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        failAll()
        _state.value = State.Disconnected
    }

    private fun open() {
        val t = token() ?: return
        val url = baseUrl().trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/ws"
        val request = runCatching {
            Request.Builder().url(url).header("Authorization", "Bearer $t").build()
        }.getOrElse { Log.w(TAG, "Bad server address for realtime: $url"); return }
        _state.value = State.Connecting
        socket = http.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val frame = runCatching { AppJson.decodeFromString(WsFrame.serializer(), text) }.getOrNull() ?: return
            when (frame.type) {
                "ready" -> { attempt = 0; _state.value = State.Connected }
                "response" -> {
                    val id = frame.id ?: return
                    unary.remove(id)?.complete(frame)
                    streams.remove(id)?.let { it.trySend(frame); it.close() }
                }
                "stream" -> frame.id?.let { streams[it]?.trySend(frame) }
                "event" -> if (frame.event == "changed") onChanged()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket, reconnect = code != 1008) // 1008 = policy (account deleted / bad token)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, reconnect = code != 1008)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                dropped(webSocket, reconnect = false)
                onUnauthorized()
            } else {
                dropped(webSocket, reconnect = true)
            }
        }
    }

    @Synchronized
    private fun dropped(ws: WebSocket, reconnect: Boolean) {
        if (ws !== socket) return
        socket = null
        _state.value = State.Disconnected
        failAll()
        if (reconnect && wanted && token() != null) {
            val wait = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            attempt++
            reconnectJob = scope.launch {
                delay(wait)
                synchronized(this@RealtimeClient) { if (wanted && socket == null) open() }
            }
        }
    }

    /** Pending calls fail with [RealtimeUnavailable] so the API layer retries them over HTTP. */
    private fun failAll() {
        unary.values.forEach { it.completeExceptionally(RealtimeUnavailable()) }
        unary.clear()
        streams.values.forEach { it.close(RealtimeUnavailable()) }
        streams.clear()
    }

    private fun send(id: String, method: String, path: String, body: JsonElement?): Boolean {
        val ws = socket ?: return false
        if (!isConnected) return false
        return ws.send(AppJson.encodeToString(WsRequest.serializer(), WsRequest(id, method, path, body)))
    }

    /** One request/response. Throws [RealtimeUnavailable] if the socket isn't usable. */
    suspend fun request(method: String, path: String, body: JsonElement?, timeoutMs: Long): WsFrame {
        if (!isConnected) throw RealtimeUnavailable()
        val id = "r" + ids.incrementAndGet()
        val result = CompletableDeferred<WsFrame>()
        unary[id] = result
        try {
            if (!send(id, method, path, body)) throw RealtimeUnavailable()
            return withTimeout(timeoutMs) { result.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw RealtimeUnavailable()
        } finally {
            unary.remove(id)
        }
    }

    /** A streaming request (the mentor): emits every "stream" frame, then completes on the final response. */
    fun stream(method: String, path: String, body: JsonElement?): Flow<WsFrame> = callbackFlow {
        if (!isConnected) throw RealtimeUnavailable()
        val id = "s" + ids.incrementAndGet()
        val inbox = Channel<WsFrame>(Channel.UNLIMITED)
        streams[id] = inbox
        if (!send(id, method, path, body)) {
            streams.remove(id)
            throw RealtimeUnavailable()
        }
        val pump = launch {
            try {
                for (frame in inbox) send(frame)
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            pump.cancel()
            // Leaving early (new question, screen closed): tell the server to stop generating.
            if (streams.remove(id) != null) {
                socket?.send(AppJson.encodeToString(WsRequest.serializer(), WsRequest(id, method, path, cancel = true)))
            }
        }
    }

    private companion object {
        const val TAG = "MasteriaRealtime"
    }
}
