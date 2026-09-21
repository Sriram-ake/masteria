package com.triplethreats.masteria

import com.triplethreats.masteria.ai.ChatOptions
import com.triplethreats.masteria.ai.KeyPool
import com.triplethreats.masteria.ai.NimClient
import com.triplethreats.masteria.ai.NimMessage
import com.triplethreats.masteria.ai.StreamOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.ZoneId
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRotationTest {
    @Test
    fun poolRoundRobinsAndRestsFailingKeys() {
        var now = 0L
        val pool = KeyPool(listOf("a", "b", "c", "b", " "), clock = { now })
        fun slot(k: String) = generateSequence { pool.acquire() }.take(10).first { it.key == k }
        assertEquals(3, pool.size) // blanks and duplicates dropped
        assertEquals(listOf("a", "b", "c", "a"), (1..4).map { pool.acquire()!!.key })

        pool.rateLimited(slot("b"), retryAfterMs = 5_000)
        assertEquals(2, pool.healthyCount())
        assertTrue((1..6).map { pool.acquire()!!.key }.none { it == "b" })
        now += 5_001
        assertEquals(3, pool.healthyCount())

        pool.rejected(slot("a"), 401)
        pool.rejected(slot("c"), 403)
        val b = pool.acquire()!!
        assertEquals("b", b.key)
        assertNull(pool.acquire(exclude = setOf(b)))
        now += KeyPool.REJECTED_REST_MS + 1
        assertEquals(3, pool.healthyCount())
    }

    @Test
    fun nimClientRotatesToAWorkingKey() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/v1/chat/completions") {
                    val key = call.request.header("Authorization")!!.removePrefix("Bearer ")
                    seen += key
                    val stream = call.receiveText().contains("\"stream\":true")
                    when (key) {
                        "bad-key" -> call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        "busy-key" -> {
                            call.response.header("Retry-After", "30")
                            call.respondText("""{"error":"rate limited"}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                        }
                        else -> if (stream) call.respondText(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"hi \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\ndata: [DONE]\n\n",
                            ContentType.Text.EventStream,
                        ) else call.respondText("""{"choices":[{"message":{"content":"ok from good key"}}]}""", ContentType.Application.Json)
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val config = AppConfig(
                port = 0, nvidiaApiKey = "bad-key", nimBaseUrl = "http://127.0.0.1:$port/v1", chatModel = "m", fallbackModels = emptyList(),
                verifyModel = "m", visionModel = "m", visionFallbackModels = emptyList(), jwtSecret = "x".repeat(40),
                dataDir = Files.createTempDirectory("masteria-keys"), mongoUri = null, zone = ZoneId.of("Asia/Kolkata"), contentDir = null,
                nvidiaApiKeys = listOf("bad-key", "busy-key", "good-key"),
            )
            val nim = NimClient(config)
            val reply = nim.chat(listOf(NimMessage("user", "hi")), ChatOptions(listOf("m"), 0.0, 10, 5_000))
            assertNotNull(reply)
            assertEquals("ok from good key", reply.text)
            assertEquals(listOf("bad-key", "busy-key", "good-key"), seen.toList())
            assertEquals(1, nim.keys.healthyCount()) // bad sidelined, busy resting

            // Next calls go straight to the healthy key, streaming included.
            seen.clear()
            val out = StringBuilder()
            val outcome = nim.stream(listOf(NimMessage("user", "hi")), ChatOptions(listOf("m"), 0.0, 10, 5_000), 5_000) { out.append(it) }
            assertIs<StreamOutcome.Completed>(outcome)
            assertEquals("hi there", out.toString())
            assertEquals(listOf("good-key"), seen.toList())
            nim.close()
        } finally {
            server.stop(0, 0)
        }
    }
}
