package com.triplethreats.masteria.ai

import com.triplethreats.masteria.AppConfig
import com.triplethreats.masteria.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** One chat message; [imageDataUrl] turns it into OpenAI-style content parts (text + image_url). */
data class NimMessage(val role: String, val text: String, val imageDataUrl: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        if (imageDataUrl == null) put("content", text)
        else put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", imageDataUrl) })
            })
        })
    }
}

data class ChatOptions(
    val models: List<String>,
    val temperature: Double,
    val maxTokens: Int,
    val timeoutMs: Long,
    /** Only sent to nemotron models as chat_template_kwargs.enable_thinking. */
    val thinking: Boolean = false,
    /** Absolute wall-clock deadline for the whole fallback chain (epoch ms), if any. */
    val deadlineAt: Long? = null,
)

data class NimReply(val text: String, val model: String, val latencyMs: Long)

sealed interface StreamOutcome {
    data class Completed(val model: String, val firstByteMs: Long) : StreamOutcome
    /** Nothing was emitted; the caller can fall back to a deterministic reply. */
    data class FailedBeforeStart(val reason: String) : StreamOutcome
    data class FailedMidStream(val reason: String) : StreamOutcome
}

/** Thrown when writing a delta to our own client failed (client went away): don't fall back, just stop. */
class DownstreamClosed(cause: Throwable) : RuntimeException("client disconnected", cause)

/**
 * OpenAI-compatible NVIDIA NIM client with a per-call model fallback chain (404/429/5xx/timeout → next model),
 * per-call timeouts, and an online flag + last latency for /ai/status. Never throws into callers except
 * [DownstreamClosed] and coroutine cancellation.
 */
class NimClient(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(NimClient::class.java)
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var online: Boolean = false
        private set
    @Volatile var lastLatencyMs: Long? = null
        private set

    /** All configured NIM keys, rotated per request and on 401/403/429. */
    val keys = KeyPool(config.nvidiaApiKeys)

    val enabled: Boolean get() = keys.size > 0
    private val url get() = "${config.nimBaseUrl}/chat/completions"

    fun chatChain(): List<String> = (listOf(config.chatModel) + config.fallbackModels).distinct()
    fun verifyChain(): List<String> =
        (listOf(config.verifyModel, config.chatModel) + config.fallbackModels)
            .distinct().filterNot { it.contains("llama-3.2-11b-vision", ignoreCase = true) } // weak at maths
    fun visionChain(): List<String> = (listOf(config.visionModel) + config.visionFallbackModels).distinct()

    private fun isNemotron(model: String) = model.contains("nemotron", ignoreCase = true)

    private fun body(model: String, messages: List<NimMessage>, o: ChatOptions, stream: Boolean): String =
        AppJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages.map { it.toJson() }))
            put("temperature", o.temperature)
            put("max_tokens", o.maxTokens)
            put("stream", stream)
            if (isNemotron(model)) put("chat_template_kwargs", buildJsonObject { put("enable_thinking", o.thinking) })
        })

    private fun timeoutFor(o: ChatOptions): Long? {
        val remaining = o.deadlineAt?.let { it - System.currentTimeMillis() }
        if (remaining != null && remaining < 1_500) return null
        return if (remaining == null) o.timeoutMs else minOf(o.timeoutMs, remaining)
    }

    /**
     * Fire-and-forget probe of EVERY key so /health reflects reality soon after start and bad keys are
     * sidelined before a learner hits them. Logs one line per key (never the key itself).
     */
    fun probeInBackground() {
        if (!enabled) return
        scope.launch {
            val model = chatChain().first()
            var anyOk = false
            repeat(keys.size) {
                val slot = keys.acquire() ?: return@repeat
                val started = System.currentTimeMillis()
                val status = runCatching {
                    val resp = http.post(url) {
                        bearerAuth(slot.key)
                        contentType(ContentType.Application.Json)
                        setBody(body(model, listOf(NimMessage("user", "Reply with the single word: ok")), ChatOptions(listOf(model), 0.0, 8, 20_000), stream = false))
                        timeout { requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
                    }
                    resp.bodyAsText()
                    resp.status.value
                }.getOrNull()
                when (status) {
                    null -> log.warn("NIM probe {}: no response", slot.label)
                    in 200..299 -> {
                        keys.success(slot); anyOk = true
                        lastLatencyMs = System.currentTimeMillis() - started
                        log.info("NIM probe {}: ok ({} ms)", slot.label, lastLatencyMs)
                    }
                    401, 403 -> keys.rejected(slot, status)
                    429 -> keys.rateLimited(slot, null)
                    else -> log.warn("NIM probe {}: HTTP {} (model {})", slot.label, status, model)
                }
            }
            online = anyOk
            log.info("NIM probe: {} of {} key(s) usable", keys.healthyCount(), keys.size)
        }
    }

    /** What to do after a failed status with one key on one model. */
    private enum class KeyOutcome { NEXT_KEY, NEXT_MODEL }

    /** Bad key or rate limit → another key on the same model; anything else (404/5xx) → the next model. */
    private fun onStatus(slot: KeyPool.Slot, status: Int, retryAfter: String?, model: String): KeyOutcome = when (status) {
        401, 403 -> { keys.rejected(slot, status); KeyOutcome.NEXT_KEY }
        429 -> { keys.rateLimited(slot, KeyPool.retryAfterMs(retryAfter)); KeyOutcome.NEXT_KEY }
        else -> { log.warn("NIM {} -> HTTP {} with {}", model, status, slot.label); KeyOutcome.NEXT_MODEL }
    }

    /** Non-streaming chat. Rotates keys on 401/403/429, then models on 404/5xx/timeout. Null when all failed. */
    suspend fun chat(messages: List<NimMessage>, o: ChatOptions): NimReply? {
        if (!enabled) return null
        models@ for (model in o.models) {
            val tried = mutableSetOf<KeyPool.Slot>()
            while (true) {
                val timeout = timeoutFor(o) ?: break@models
                val slot = keys.acquire(tried)
                if (slot == null) { if (tried.isEmpty()) break@models else continue@models }
                tried += slot
                val started = System.currentTimeMillis()
                try {
                    val resp = http.post(url) {
                        bearerAuth(slot.key)
                        contentType(ContentType.Application.Json)
                        setBody(body(model, messages, o, stream = false))
                        timeout { requestTimeoutMillis = timeout; socketTimeoutMillis = timeout }
                    }
                    val raw = resp.bodyAsText()
                    if (!resp.status.isSuccess()) {
                        when (onStatus(slot, resp.status.value, resp.headers["Retry-After"], model)) {
                            KeyOutcome.NEXT_KEY -> continue
                            KeyOutcome.NEXT_MODEL -> continue@models
                        }
                    }
                    keys.success(slot)
                    val text = extractContent(raw)
                    val latency = System.currentTimeMillis() - started
                    if (text.isNullOrBlank()) {
                        log.warn("NIM {} returned empty content ({} ms)", model, latency)
                        continue@models
                    }
                    online = true
                    lastLatencyMs = latency
                    log.info("NIM {} ok in {} ms via {}", model, latency, slot.label)
                    return NimReply(text, model, latency)
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    log.warn("NIM {} cancelled/timed out after {} ms", model, System.currentTimeMillis() - started)
                    continue@models
                } catch (e: Exception) {
                    log.warn("NIM {} failed after {} ms: {}", model, System.currentTimeMillis() - started, e.javaClass.simpleName)
                    continue@models
                }
            }
        }
        online = false
        return null
    }

    /**
     * Streaming chat. [onDelta] receives visible text pieces (think blocks removed). Rotates keys and falls
     * through the model chain only while nothing has been emitted yet.
     */
    suspend fun stream(
        messages: List<NimMessage>,
        o: ChatOptions,
        firstByteTimeoutMs: Long,
        onDelta: suspend (String) -> Unit,
    ): StreamOutcome {
        if (!enabled) return StreamOutcome.FailedBeforeStart("AI key not configured")
        var lastReason = "AI unavailable"
        models@ for (model in o.models) {
            val tried = mutableSetOf<KeyPool.Slot>()
            while (true) {
                val slot = keys.acquire(tried)
                if (slot == null) {
                    if (tried.isEmpty()) { lastReason = "all AI keys are resting"; break@models } else continue@models
                }
                tried += slot
                val started = System.currentTimeMillis()
                val emitted = AtomicBoolean(false)
                var firstByteMs = -1L
                var badStatus: Pair<Int, String?>? = null
                val filter = ThinkFilter()
                try {
                    val finished = coroutineScope {
                        val work = async {
                            http.preparePost(url) {
                                bearerAuth(slot.key)
                                contentType(ContentType.Application.Json)
                                setBody(body(model, messages, o, stream = true))
                                timeout { requestTimeoutMillis = o.timeoutMs; socketTimeoutMillis = firstByteTimeoutMs }
                            }.execute { resp ->
                                if (!resp.status.isSuccess()) {
                                    badStatus = resp.status.value to resp.headers["Retry-After"]
                                    return@execute false
                                }
                                keys.success(slot)
                                val ch = resp.bodyAsChannel()
                                while (true) {
                                    val line = ch.readLine() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val payload = line.removePrefix("data:").trim()
                                    if (payload == "[DONE]") break
                                    val piece = extractDelta(payload) ?: continue
                                    val visible = filter.accept(piece)
                                    if (visible.isEmpty()) continue
                                    if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - started
                                    emitted.set(true)
                                    try { onDelta(visible) } catch (e: Exception) { throw DownstreamClosed(e) }
                                }
                                true
                            }
                        }
                        val watchdog = launch {
                            delay(firstByteTimeoutMs)
                            if (!emitted.get()) work.cancel(CancellationException("no first token in $firstByteTimeoutMs ms"))
                        }
                        try { work.await() } finally { watchdog.cancel() }
                    }
                    val bad = badStatus
                    if (bad != null) {
                        lastReason = "HTTP ${bad.first}"
                        when (onStatus(slot, bad.first, bad.second, model)) {
                            KeyOutcome.NEXT_KEY -> continue
                            KeyOutcome.NEXT_MODEL -> continue@models
                        }
                    }
                    if (finished && emitted.get()) {
                        online = true
                        lastLatencyMs = firstByteMs
                        log.info("NIM stream {} ok via {}, first token {} ms, total {} ms", model, slot.label, firstByteMs, System.currentTimeMillis() - started)
                        return StreamOutcome.Completed(model, firstByteMs)
                    }
                    if (finished && !emitted.get()) lastReason = "empty reply"
                } catch (e: DownstreamClosed) {
                    throw e
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    lastReason = "timed out"
                    log.warn("NIM stream {} timed out after {} ms", model, System.currentTimeMillis() - started)
                } catch (e: Exception) {
                    lastReason = e.javaClass.simpleName
                    log.warn("NIM stream {} failed after {} ms: {}", model, System.currentTimeMillis() - started, e.javaClass.simpleName)
                }
                if (emitted.get()) return StreamOutcome.FailedMidStream("The mentor was interrupted. Please ask again.")
                continue@models
            }
        }
        online = false
        return StreamOutcome.FailedBeforeStart(lastReason)
    }

    fun close() = http.close()

    companion object {
        private val thinkBlock = Regex("(?s)<think>.*?</think>")

        fun stripThink(text: String): String {
            var t = thinkBlock.replace(text, "")
            val close = t.indexOf("</think>")
            if (close >= 0) t = t.substring(close + "</think>".length)
            return t.trim()
        }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        fun extractContent(raw: String): String? = runCatching {
            val obj = AppJson.parseToJsonElement(raw).jsonObject
            val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return null
            msg["content"].str()?.let { stripThink(it) }
        }.getOrNull()

        fun extractDelta(payload: String): String? = runCatching {
            val obj = AppJson.parseToJsonElement(payload).jsonObject
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content").str()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/** Streaming filter that drops a leading `<think>…</think>` block. */
class ThinkFilter {
    private var buffer = StringBuilder()
    private var decided = false
    private var inThink = false

    fun accept(piece: String): String {
        if (decided && !inThink) return piece
        buffer.append(piece)
        if (!decided) {
            val trimmed = buffer.trimStart()
            if (trimmed.length < "<think>".length && "<think>".startsWith(trimmed)) return ""
            decided = true
            if (trimmed.startsWith("<think>")) inThink = true
            else { val out = buffer.toString(); buffer = StringBuilder(); return out }
        }
        val end = buffer.indexOf("</think>")
        if (end < 0) return ""
        inThink = false
        val rest = buffer.substring(end + "</think>".length).trimStart()
        buffer = StringBuilder()
        return rest
    }
}
