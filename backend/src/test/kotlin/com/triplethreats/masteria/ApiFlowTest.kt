package com.triplethreats.masteria

import com.triplethreats.masteria.db.DbSnapshot
import com.triplethreats.masteria.db.JsonFileStore
import com.triplethreats.masteria.db.UserRecord
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiFlowTest {
    private val fixtureDir = File(javaClass.classLoader.getResource("fixtures/content/tracks.json")!!.toURI()).parentFile.absolutePath

    private fun config(dataDir: java.nio.file.Path) = AppConfig(
        port = 0, nvidiaApiKey = null, nimBaseUrl = "http://127.0.0.1:9/v1", chatModel = "m", fallbackModels = emptyList(),
        verifyModel = "v", visionModel = "vis", visionFallbackModels = emptyList(), jwtSecret = "test-secret-test-secret-test-secret-1234",
        dataDir = dataDir, mongoUri = null, zone = ZoneId.of("Asia/Kolkata"), contentDir = fixtureDir,
    )

    private fun json(text: String): JsonObject = AppJson.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.bool(k: String) = this[k]!!.jsonPrimitive.boolean
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray

    /** Fixture key: mcq "t.x.qNN" → NN % 4, numeric (q02) → "2.5". */
    private fun answerFor(q: JsonObject, correct: Boolean): String {
        val id = q.str("id")
        return if (q.str("type") == "numeric") """{"questionId":"$id","answerText":"${if (correct) "10/4" else "7"}","timeMs":1500}"""
        else {
            val key = id.substringAfterLast("q").toInt() % 4
            """{"questionId":"$id","answerIndex":${if (correct) key else (key + 1) % 4},"timeMs":1500}"""
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        if (token != null) bearerAuth(token)
    }

    private suspend fun HttpClient.getAuth(path: String, token: String) = get(path) { bearerAuth(token) }

    private suspend fun HttpClient.playQuest(token: String, topicId: String?, boss: Boolean, correct: (Int) -> Boolean): Pair<JsonObject, JsonObject> {
        val topicJson = topicId?.let { "\"$it\"" } ?: "null"
        val start = postJson("/quests/start", """{"topicId":$topicJson,"boss":$boss}""", token)
        assertEquals(200, start.status.value, start.bodyAsText())
        val session = json(start.bodyAsText())
        val questId = session.str("id")
        var q = session.obj("question")
        var i = 0
        while (true) {
            val r = postJson("/quests/$questId/answer", answerFor(q, correct(i)), token)
            assertEquals(200, r.status.value, r.bodyAsText())
            val res = json(r.bodyAsText())
            assertEquals(correct(i), res.bool("correct"))
            i++
            if (res.bool("done")) break
            q = res.obj("next")
        }
        val done = postJson("/quests/$questId/complete", "", token)
        assertEquals(200, done.status.value, done.bodyAsText())
        return session to json(done.bodyAsText())
    }

    @Test
    fun fullLearnerLoop() {
        val dataDir = Files.createTempDirectory("masteria-test")
        val services = Services.create(config(dataDir))
        testApplication {
            application { module(services) }
            val c = client

            // health + tracks are public
            assertEquals(200, c.get("/health").status.value)
            assertEquals("fix-math", AppJson.parseToJsonElement(c.get("/tracks").bodyAsText()).jsonArray[0].jsonObject.str("id"))
            assertEquals(401, c.get("/home").status.value)
            assertEquals(404, c.get("/nope").status.value)

            // register
            val reg = c.postJson("/auth/register", """{"name":"Asha Rao","email":"asha@example.com","password":"secret1"}""")
            assertEquals(201, reg.status.value, reg.bodyAsText())
            val token = json(reg.bodyAsText()).str("token")
            assertEquals(409, c.postJson("/auth/register", """{"name":"A","email":"ASHA@example.com","password":"secret1"}""").status.value)
            assertEquals(400, c.postJson("/auth/register", """{"name":"A","email":"bad","password":"secret1"}""").status.value)
            assertEquals(400, c.postJson("/auth/register", """{"name":"A","email":"a@b.co","password":"123"}""").status.value)
            assertEquals(401, c.postJson("/auth/login", """{"email":"asha@example.com","password":"wrong!!"}""").status.value)
            assertEquals(200, c.postJson("/auth/login", """{"email":"asha@example.com","password":"secret1"}""").status.value)

            // onboarding (minor without consent is refused)
            val minor = c.postJson("/onboarding", """{"learnerType":"school","goal":"grades","selfLevel":"beginner","dailyMinutes":15,"trackId":"fix-math","isMinor":true}""", token)
            assertEquals(400, minor.status.value)
            assertEquals("A parent or guardian needs to agree before you can continue.", json(minor.bodyAsText()).str("error"))
            val onb = c.postJson("/onboarding", """{"learnerType":"school","goal":"grades","selfLevel":"beginner","dailyMinutes":15,"trackId":"fix-math","isMinor":true,"parentConsent":true}""", token)
            assertEquals(200, onb.status.value, onb.bodyAsText())
            assertTrue(json(onb.bodyAsText()).bool("onboarded"))
            assertFalse(json(onb.bodyAsText()).bool("diagnosed"))

            // diagnostic: Alpha d1+d2 right (53), Beta only d1 right (28) → Beta weakest
            val diag = json(c.getAuth("/onboarding/diagnostic", token).bodyAsText())
            val dq = diag.arr("questions").map { it.jsonObject }
            assertEquals(6, dq.size)
            assertEquals(listOf(1, 2, 3, 1, 2, 3), dq.map { it.int("difficulty") })
            assertTrue(dq.none { it.containsKey("answerIndex") || it.containsKey("answerText") })
            val answers = dq.joinToString(",") { q ->
                val right = if (q.str("topicId") == "t.a") q.int("difficulty") <= 2 else q.int("difficulty") == 1
                answerFor(q, right)
            }
            val dres = c.postJson("/onboarding/diagnostic", """{"trackId":"fix-math","answers":[$answers]}""", token)
            assertEquals(200, dres.status.value, dres.bodyAsText())
            val dr = json(dres.bodyAsText())
            assertEquals("t.b", dr.str("weakestTopicId"))
            assertEquals(3, dr.int("correct"))
            assertEquals(6, dr.int("total"))
            val masteryById = dr.arr("topics").associate { it.jsonObject.str("topicId") to it.jsonObject.int("mastery") }
            assertEquals(mapOf("t.a" to 53, "t.b" to 28, "t.c" to 15), masteryById)
            assertTrue(dr.str("summary").contains("Beta"))
            assertTrue(json(c.getAuth("/auth/me", token).bodyAsText()).bool("diagnosed"))

            // home recommends the weak topic, map shows Gamma locked
            val home = json(c.getAuth("/home", token).bodyAsText())
            assertEquals("t.b", home.obj("recommended").str("topicId"))
            assertEquals("Weakest topic on your path", home.obj("recommended").str("reason"))
            assertTrue(home.str("greeting").endsWith("Asha"))
            val map = json(c.getAuth("/map", token).bodyAsText())
            assertEquals("locked", map.arr("topics").first { it.jsonObject.str("id") == "t.c" }.jsonObject.str("status"))
            assertEquals(403, c.postJson("/quests/start", """{"topicId":"t.c"}""", token).status.value)
            assertEquals(409, c.postJson("/quests/start", """{"topicId":"t.b","boss":true}""", token).status.value)

            // first quest: recommended topic, hint first (diagnostic accuracy on Beta was 33 %)
            val start = json(c.postJson("/quests/start", """{}""", token).bodyAsText())
            assertEquals("t.b", start.str("topicId"))
            assertTrue(start.bool("hintFirst"))
            assertEquals(5, start.int("total"))
            assertEquals(28, start.int("masteryBefore"))
            val qid = start.str("id")
            var q = start.obj("question")
            // wrong question id → 409
            assertEquals(409, c.postJson("/quests/$qid/answer", """{"questionId":"nope","answerIndex":0}""", token).status.value)
            val results = mutableListOf<JsonObject>()
            repeat(5) { i ->
                val t0 = System.nanoTime()
                val body = answerFor(q, correct = i != 1)
                val r = c.postJson("/quests/$qid/answer", body, token)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(ms < 200, "answer took $ms ms")
                val res = json(r.bodyAsText())
                results += res
                // idempotent retry returns the same result
                assertEquals(res, json(c.postJson("/quests/$qid/answer", body, token).bodyAsText()))
                if (!res.bool("done")) q = res.obj("next")
            }
            assertTrue(results.last().bool("done"))
            assertEquals(4, results.last().int("correctCount"))
            assertTrue(results[0].int("masteryAfter") > results[0].int("masteryBefore"))
            assertTrue(results[1].int("masteryAfter") < results[1].int("masteryBefore"))
            assertTrue(results.all { it.str("difficultyChange") in setOf("up", "down", "same") })
            assertEquals(409, c.postJson("/quests/$qid/answer", answerFor(q, true).replace(q.str("id"), "other"), token).status.value)

            val sum = json(c.postJson("/quests/$qid/complete", "", token).bodyAsText())
            assertEquals(sum, json(c.postJson("/quests/$qid/complete", "", token).bodyAsText())) // idempotent
            assertEquals(4, sum.int("correct"))
            assertEquals(80, sum.int("accuracy"))
            val xp = sum.int("xpEarned")
            assertEquals(xp, sum.arr("breakdown").sumOf { it.jsonObject.int("xp") })
            assertTrue(sum.arr("breakdown").any { it.jsonObject.str("label") == "Daily streak" })
            assertEquals(xp / 10, sum.int("coinsEarned"))
            assertEquals(1, sum.int("streakDays"))
            assertTrue(sum.arr("newBadges").any { it.jsonObject.str("id") == "first_quest" })
            assertTrue(sum.str("nextStep").isNotBlank())
            assertEquals(xp, json(c.getAuth("/auth/me", token).bodyAsText()).int("xp"))

            // grind Alpha to the boss, win it, Gamma unlocks
            var alphaReady = false
            repeat(6) {
                if (!alphaReady) {
                    c.playQuest(token, "t.a", boss = false) { true }
                    val m = json(c.getAuth("/map", token).bodyAsText()).arr("topics").first { it.jsonObject.str("id") == "t.a" }.jsonObject
                    alphaReady = m.bool("bossReady")
                }
            }
            assertTrue(alphaReady, "Alpha should reach the boss threshold")
            val home2 = json(c.getAuth("/home", token).bodyAsText())
            assertTrue(home2.arr("bossReady").any { it.jsonObject.str("topicId") == "t.a" })
            val (bossSession, bossSum) = c.playQuest(token, "t.a", boss = true) { it != 0 }
            assertTrue(bossSession.bool("isBoss"))
            assertTrue(bossSum.bool("bossDefeated"), bossSum.toString())
            assertEquals(listOf("Gamma"), bossSum.arr("unlockedTopics").map { it.jsonPrimitive.content })
            assertTrue(bossSum.arr("breakdown").any { it.jsonObject.str("label") == "Skill mastery" })
            assertTrue(bossSum.arr("newBadges").any { it.jsonObject.str("id") == "boss_slayer" })
            val map2 = json(c.getAuth("/map", token).bodyAsText()).arr("topics").associate { it.jsonObject.str("id") to it.jsonObject }
            assertEquals("mastered", map2["t.a"]!!.str("status"))
            assertEquals("available", map2["t.c"]!!.str("status"))
            assertEquals(409, c.postJson("/quests/start", """{"topicId":"t.a","boss":true}""", token).status.value)
            // replaying a mastered topic is a review quest
            val review = json(c.postJson("/quests/start", """{"topicId":"t.a"}""", token).bodyAsText())
            assertTrue(review.bool("isReview"))
            assertEquals(review.str("id"), json(c.getAuth("/quests/${review.str("id")}", token).bodyAsText()).str("id"))

            // progress / profile / streak
            val prog = json(c.getAuth("/progress", token).bodyAsText())
            assertEquals(7, prog.arr("xpLast7Days").size)
            assertEquals(7, prog.arr("dayLabels").size)
            assertTrue(prog.int("questsCompleted") >= 3)
            assertTrue(prog.arr("topics").first { it.jsonObject.str("topicId") == "t.b" }.jsonObject.arr("trend").isNotEmpty())
            val prof = json(c.getAuth("/profile", token).bodyAsText())
            assertEquals(8, prof.arr("badges").size)
            assertEquals(1, prof.int("bossesDefeated"))
            assertEquals(400, c.put("/profile") { contentType(ContentType.Application.Json); setBody("""{"trackId":"nope"}"""); bearerAuth(token) }.status.value)
            val streak = json(c.getAuth("/streak", token).bodyAsText())
            assertEquals(1, streak.int("streakDays"))
            assertTrue(streak.bool("activeToday"))

            // AI routes without NIM: deterministic fallbacks
            val mentor = c.postJson("/ai/mentor", """{"messages":[{"role":"user","content":"Help me with Beta"}],"topicId":"t.b"}""", token)
            assertEquals(200, mentor.status.value)
            assertTrue(mentor.headers["Content-Type"]!!.startsWith("text/event-stream"))
            val events = mentor.bodyAsText().split("\n\n").filter { it.startsWith("data: ") }.map { json(it.removePrefix("data: ")) }
            assertTrue(events.size >= 2)
            assertTrue(events.first().containsKey("delta"))
            assertEquals(true, events.last()["done"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(400, c.postJson("/ai/mentor", """{"messages":[]}""", token).status.value)
            val explain = json(c.postJson("/ai/explain", """{"questionId":"t.b.q01","answerIndex":0}""", token).bodyAsText())
            assertEquals("fallback", explain.str("source"))
            assertEquals(422, c.postJson("/ai/scan", """{"text":"too short"}""", token).status.value)
            assertEquals(false, json(c.getAuth("/ai/status", token).bodyAsText()).bool("online"))

            // report a question: one flag per user
            assertEquals(1, json(c.postJson("/questions/t.b.q03/report", """{"reason":"typo"}""", token).bodyAsText()).int("flags"))
            assertEquals(1, json(c.postJson("/questions/t.b.q03/report", """{}""", token).bodyAsText()).int("flags"))
            assertEquals(404, c.postJson("/questions/none/report", """{}""", token).status.value)

            // guest
            val guest = json(c.postJson("/auth/guest", """{"name":"Ravi"}""").bodyAsText())
            assertTrue(guest.obj("user").bool("isGuest"))
            assertTrue(guest.obj("user").str("email").endsWith("@masteria.local"))

            // delete account
            assertTrue(json(c.delete("/auth/me") { bearerAuth(token) }.bodyAsText()).bool("deleted"))
            assertEquals(401, c.getAuth("/auth/me", token).status.value)
        }
        services.shutdown()
        val snap = AppJson.decodeFromString(DbSnapshot.serializer(), Files.readString(dataDir.resolve("masteria-db.json")))
        assertTrue(snap.users.none { it.email == "asha@example.com" })
        assertTrue(snap.attempts.isEmpty() || snap.attempts.none { a -> snap.users.none { it.id == a.userId } })
    }

    @Test
    fun jsonStorePersistsAndReloads() = runBlocking {
        val dir = Files.createTempDirectory("masteria-store")
        val file = dir.resolve("db.json")
        val s1 = JsonFileStore(file, debounceMs = 10)
        s1.init()
        s1.saveUser(UserRecord(id = "u1", name = "A", email = "a@b.co", passwordHash = "h"))
        s1.close()
        val s2 = JsonFileStore(file)
        s2.init()
        assertNotNull(s2.findUser("u1"))
        assertNotNull(s2.findUserByEmail("A@B.CO"))
        s2.close()
        assertFalse(Files.exists(dir.resolve("db.json.tmp")))
    }
}
