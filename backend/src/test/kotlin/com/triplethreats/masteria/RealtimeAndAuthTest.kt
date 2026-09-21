package com.triplethreats.masteria

import com.triplethreats.masteria.auth.FirebaseIdentity
import com.triplethreats.masteria.auth.IdTokenVerifier
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RealtimeAndAuthTest {
    private val fixtureDir = File(javaClass.classLoader.getResource("fixtures/content/tracks.json")!!.toURI()).parentFile.absolutePath

    private fun services(firebase: IdTokenVerifier? = null, rateLimit: Int = 30): Services {
        val config = AppConfig(
            port = 0, nvidiaApiKey = null, nimBaseUrl = "http://127.0.0.1:9/v1", chatModel = "m", fallbackModels = emptyList(),
            verifyModel = "v", visionModel = "vis", visionFallbackModels = emptyList(), jwtSecret = "test-secret-test-secret-test-secret-1234",
            dataDir = Files.createTempDirectory("masteria-rt"), mongoUri = null, zone = ZoneId.of("Asia/Kolkata"), contentDir = fixtureDir,
            authRateLimitPerMinute = rateLimit,
        )
        val base = Services.create(config)
        return if (firebase == null) base else Services(
            base.config, base.content, base.store, base.bank, base.nim, base.game, base.pipeline, base.ai, base.jwt, firebase,
        )
    }

    private fun json(text: String): JsonObject = AppJson.parseToJsonElement(text).jsonObject

    /** Stub verifier: tokens look like "uid|email|verified". */
    private val stubFirebase = IdTokenVerifier { token ->
        val parts = token.split('|')
        if (parts.size != 3) throw ApiException(HttpStatusCode.Unauthorized, "bad token")
        FirebaseIdentity(parts[0], parts[1], parts[2] == "true", null, "password")
    }

    @Test
    fun healthAndPingAreCheapAndSupportHead() = testApplication {
        application { module(services()) }
        val health = client.get("/health")
        assertEquals(200, health.status.value)
        val body = json(health.bodyAsText())
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["websocket"]!!.jsonPrimitive.boolean)
        assertEquals(200, client.head("/health").status.value)
        assertEquals("pong", client.get("/ping").bodyAsText())
        assertEquals(200, client.head("/ping").status.value)
    }

    @Test
    fun firebaseSignInCreatesLinksAndUpgradesGuests() = testApplication {
        application { module(services(stubFirebase)) }
        suspend fun fb(token: String, bearer: String? = null) = client.post("/auth/firebase") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"$token","name":"Asha"}""")
            if (bearer != null) bearerAuth(bearer)
        }

        // new account, then the same Firebase user signs in again → same Masteria user
        val first = fb("uid-1|asha@example.com|false")
        assertEquals(201, first.status.value, first.bodyAsText())
        val id1 = json(first.bodyAsText())["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val again = fb("uid-1|asha@example.com|false")
        assertEquals(200, again.status.value)
        assertEquals(id1, json(again.bodyAsText())["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val token = json(again.bodyAsText())["token"]!!.jsonPrimitive.content
        assertEquals(200, client.get("/auth/me") { bearerAuth(token) }.status.value)

        // a different Firebase user with the same unverified email can't take the account over
        assertEquals(409, fb("uid-2|asha@example.com|false").status.value)
        // invalid token
        assertEquals(401, fb("garbage").status.value)
        // Firebase accounts can't use the legacy password login
        val pw = client.post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":"asha@example.com","password":"whatever"}""") }
        assertEquals(401, pw.status.value)

        // guest upgrade keeps the same user id
        val guest = client.post("/auth/guest") { contentType(ContentType.Application.Json); setBody("""{"name":"G"}""") }
        val g = json(guest.bodyAsText())
        val guestId = g["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val up = fb("uid-3|ravi@example.com|true", bearer = g["token"]!!.jsonPrimitive.content)
        assertEquals(200, up.status.value, up.bodyAsText())
        val upUser = json(up.bodyAsText())["user"]!!.jsonObject
        assertEquals(guestId, upUser["id"]!!.jsonPrimitive.content)
        assertFalse(upUser["isGuest"]!!.jsonPrimitive.boolean)
        assertEquals("ravi@example.com", upUser["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun firebaseDisabledReturns503() = testApplication {
        application { module(services()) }
        val r = client.post("/auth/firebase") { contentType(ContentType.Application.Json); setBody("""{"idToken":"x"}""") }
        assertEquals(503, r.status.value)
    }

    @Test
    fun authRoutesAreRateLimited() = testApplication {
        application { module(services(rateLimit = 3)) }
        val codes = (1..5).map { client.post("/auth/guest").status.value }
        assertEquals(listOf(201, 201, 201, 429, 429), codes)
    }

    @Test
    fun websocketCarriesApiCallsAndPushesChanges() = testApplication {
        application { module(services()) }
        val guest = json(client.post("/auth/guest").bodyAsText())
        val token = guest["token"]!!.jsonPrimitive.content
        val ws = createClient { install(WebSockets) }

        // no token → upgrade refused
        assertTrue(runCatching { ws.webSocket("/ws") { incoming.receive() } }.isFailure)

        ws.webSocket("/ws", request = { header(HttpHeaders.Authorization, "Bearer $token") }) {
            suspend fun nextFrame(): JsonObject = json((incoming.receive() as Frame.Text).readText())
            suspend fun call(id: String, method: String, path: String, body: String? = null): JsonObject {
                send(Frame.Text("""{"id":"$id","method":"$method","path":"$path"${body?.let { ",\"body\":$it" } ?: ""}}"""))
                while (true) {
                    val f = nextFrame()
                    if (f["type"]!!.jsonPrimitive.content == "response" && f["id"]?.jsonPrimitive?.content == id) return f
                }
            }

            assertEquals("ready", nextFrame()["type"]!!.jsonPrimitive.content)
            assertEquals(200, call("1", "GET", "/auth/me")["status"]!!.jsonPrimitive.int)
            assertEquals(404, call("2", "GET", "/nope")["status"]!!.jsonPrimitive.int)
            val onb = call("3", "POST", "/onboarding",
                """{"learnerType":"school","goal":"grades","selfLevel":"beginner","dailyMinutes":15,"trackId":"fix-math","isMinor":false}""")
            assertEquals(200, onb["status"]!!.jsonPrimitive.int, onb.toString())
            assertEquals(400, call("4", "POST", "/onboarding", """{"learnerType":"x"}""")["status"]!!.jsonPrimitive.int)
            val diag = call("5", "GET", "/onboarding/diagnostic?trackId=fix-math")
            assertEquals(6, diag["body"]!!.jsonObject["questions"]!!.jsonArray.size)
            val start = call("6", "POST", "/quests/start", "{}")
            assertEquals(200, start["status"]!!.jsonPrimitive.int, start.toString())
            val questId = start["body"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val qid = start["body"]!!.jsonObject["question"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val ans = call("7", "POST", "/quests/$questId/answer", """{"questionId":"$qid","answerIndex":0,"answerText":"1"}""")
            assertEquals(200, ans["status"]!!.jsonPrimitive.int, ans.toString())

            // mentor streams SSE-equivalent events, ending with done, then a response frame
            send(Frame.Text("""{"id":"m","method":"POST","path":"/ai/mentor","body":{"messages":[{"role":"user","content":"help"}]}}"""))
            val events = mutableListOf<JsonObject>()
            while (true) {
                val f = nextFrame()
                if (f["id"]?.jsonPrimitive?.content != "m") continue
                if (f["type"]!!.jsonPrimitive.content == "response") { assertEquals(200, f["status"]!!.jsonPrimitive.int); break }
                events += f["body"]!!.jsonObject
            }
            assertTrue(events.any { it.containsKey("delta") })
            assertTrue(events.last()["done"]!!.jsonPrimitive.boolean)
        }

        // A change made over HTTP is pushed to an open socket of the same user.
        ws.webSocket("/ws?token=$token") {
            assertEquals("ready", json((incoming.receive() as Frame.Text).readText())["type"]!!.jsonPrimitive.content)
            val put = client.put("/profile") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"dailyMinutes":30}""")
            }
            assertEquals(200, put.status.value)
            val pushed = json((incoming.receive() as Frame.Text).readText())
            assertEquals("event", pushed["type"]!!.jsonPrimitive.content)
            assertEquals("changed", pushed["event"]!!.jsonPrimitive.content)
        }
        assertNotEquals("", token)
    }
}
