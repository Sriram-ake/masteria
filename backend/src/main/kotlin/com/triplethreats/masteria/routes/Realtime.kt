package com.triplethreats.masteria.routes

import com.triplethreats.masteria.ApiException
import com.triplethreats.masteria.AppJson
import com.triplethreats.masteria.ai.AiService
import com.triplethreats.masteria.ai.DownstreamClosed
import com.triplethreats.masteria.ai.MentorEnd
import com.triplethreats.masteria.badRequest
import com.triplethreats.masteria.db.UserRecord
import com.triplethreats.masteria.notFound
import com.triplethreats.masteria.service.GameService
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

private val log = LoggerFactory.getLogger("com.triplethreats.masteria.Realtime")

/** Live WebSocket connections per user, for server push ("your progress changed on another device"). */
class RealtimeHub {
    class Connection(val id: Long, val userId: String, private val session: WebSocketSession) {
        private val sendLock = Mutex()

        suspend fun send(frame: WsFrame): Boolean = runCatching {
            val text = AppJson.encodeToString(WsFrame.serializer(), frame)
            sendLock.withLock { session.send(Frame.Text(text)) }
        }.isSuccess

        suspend fun close(reason: String) {
            runCatching { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)) }
        }
    }

    private val ids = AtomicLong()
    private val byUser = ConcurrentHashMap<String, MutableSet<Connection>>()

    fun register(userId: String, session: WebSocketSession): Connection {
        val c = Connection(ids.incrementAndGet(), userId, session)
        byUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(c)
        return c
    }

    fun unregister(c: Connection) {
        byUser.computeIfPresent(c.userId) { _, set -> set.remove(c); if (set.isEmpty()) null else set }
    }

    fun connectionCount(): Int = byUser.values.sumOf { it.size }

    /** Tells the user's other open apps to refetch (XP, mastery, map, profile may have changed). */
    suspend fun notifyChanged(userId: String, except: Connection? = null) {
        byUser[userId]?.filter { it !== except }?.forEach { it.send(WsFrame(type = "event", event = "changed")) }
    }

    /** Closes every socket of a deleted account. */
    suspend fun disconnectUser(userId: String) {
        byUser.remove(userId)?.forEach { it.close("Account deleted.") }
    }
}

/** Result of one routed request: HTTP-equivalent status + JSON body, and whether learner data changed. */
class RpcResult(val status: HttpStatusCode, val body: JsonElement, val changed: Boolean = false)

/**
 * Runs the REST API's operations for a WebSocket request. Same service calls, same validation and
 * the same JSON as the HTTP routes, just without a new HTTP request (and TLS round trip) per call.
 */
class RpcRouter(private val game: GameService, private val ai: AiService, private val health: () -> HealthDto) {
    private fun <T> ok(serializer: KSerializer<T>, value: T, changed: Boolean = false, status: HttpStatusCode = HttpStatusCode.OK) =
        RpcResult(status, AppJson.encodeToJsonElement(serializer, value), changed)

    private fun <T> JsonElement?.decode(serializer: KSerializer<T>): T =
        AppJson.decodeFromJsonElement(serializer, this?.takeIf { it !is JsonNull } ?: JsonObject(emptyMap()))

    suspend fun call(user: UserRecord, method: String, rawPath: String, body: JsonElement?): RpcResult {
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').decodeURLQueryComponent() to it.substringAfter('=').decodeURLQueryComponent(plusIsSpace = true)
        }
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        val m = method.uppercase()
        return when {
            m == "GET" && p == listOf("health") -> ok(HealthDto.serializer(), health())
            m == "GET" && p == listOf("tracks") -> ok(ListSerializer(TrackDto.serializer()), game.content.tracks.map { it.toDto() })
            m == "GET" && p == listOf("auth", "me") -> ok(UserDto.serializer(), game.userDto(user))

            m == "POST" && p == listOf("onboarding") -> ok(UserDto.serializer(), game.onboard(user, body.decode(OnboardingRequest.serializer())), changed = true)
            m == "GET" && p == listOf("onboarding", "diagnostic") -> ok(DiagnosticDto.serializer(), game.diagnostic(user, query["trackId"]))
            m == "POST" && p == listOf("onboarding", "diagnostic") ->
                ok(DiagnosticResult.serializer(), game.submitDiagnostic(user, body.decode(DiagnosticSubmit.serializer())), changed = true)

            m == "GET" && p == listOf("home") -> ok(HomeDto.serializer(), game.home(user))
            m == "GET" && p == listOf("map") -> ok(MapDto.serializer(), game.map(user))

            m == "POST" && p == listOf("quests", "start") ->
                ok(QuestSessionDto.serializer(), game.startQuest(user, runCatching { body.decode(StartQuestRequest.serializer()) }.getOrDefault(StartQuestRequest())))
            m == "GET" && p.size == 2 && p[0] == "quests" -> ok(QuestSessionDto.serializer(), game.getQuest(user, p[1]))
            m == "POST" && p.size == 3 && p[0] == "quests" && p[2] == "answer" ->
                ok(AnswerResult.serializer(), game.answer(user, p[1], body.decode(AnswerDto.serializer())))
            m == "POST" && p.size == 3 && p[0] == "quests" && p[2] == "complete" ->
                ok(QuestSummaryDto.serializer(), game.complete(user, p[1]), changed = true)

            m == "GET" && p == listOf("progress") -> ok(ProgressDto.serializer(), game.progress(user))
            m == "GET" && p == listOf("profile") -> ok(ProfileDto.serializer(), game.profile(user))
            m == "PUT" && p == listOf("profile") -> ok(ProfileDto.serializer(), game.updateProfile(user, body.decode(UpdateProfileRequest.serializer())), changed = true)
            m == "GET" && p == listOf("streak") -> ok(StreakDto.serializer(), game.streak(user))

            m == "POST" && p.size == 3 && p[0] == "questions" && p[2] == "report" -> {
                val req = runCatching { body.decode(ReportRequest.serializer()) }.getOrDefault(ReportRequest())
                val q = game.bank.get(p[1]) ?: notFound("Question not found.")
                ok(ReportResponse.serializer(), ReportResponse(game.bank.report(q, user.id, req.reason)))
            }

            m == "GET" && p == listOf("ai", "status") -> ok(AiStatusDto.serializer(), ai.status())
            m == "POST" && p == listOf("ai", "explain") -> ok(ExplainResponse.serializer(), ai.explain(user, body.decode(ExplainRequest.serializer())))
            m == "POST" && p == listOf("ai", "scan") -> ok(QuestSessionDto.serializer(), ai.scan(user, body.decode(ScanRequest.serializer())))

            else -> throw ApiException(HttpStatusCode.NotFound, "No such endpoint: $m /${p.joinToString("/")}")
        }
    }
}

private const val MAX_IN_FLIGHT = 8

/**
 * `GET /ws` (WebSocket). Auth: `Authorization: Bearer <jwt>` on the upgrade request (or `?token=` for
 * clients that can't set headers). One persistent connection carries every API call, streams mentor
 * replies, and receives "changed" pushes when the same account makes progress elsewhere.
 */
fun Route.realtimeRoutes(game: GameService, ai: AiService, rpc: RpcRouter, hub: RealtimeHub) = authenticate(JwtIssuer.AUTH) {
    webSocket("/ws") {
        val userId = call.principal<JWTPrincipal>()?.payload?.subject
        val first = userId?.let { game.findUser(it) }
        if (first == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Please log in again."))
            return@webSocket
        }
        val conn = hub.register(first.id, this)
        val jobs = ConcurrentHashMap<String, Job>()
        val permits = Semaphore(MAX_IN_FLIGHT)
        try {
            conn.send(WsFrame(type = "ready", body = AppJson.encodeToJsonElement(UserDto.serializer(), game.userDto(first))))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val req = try {
                    AppJson.decodeFromString(WsRequest.serializer(), frame.readText())
                } catch (e: Exception) {
                    conn.send(WsFrame(type = "response", status = 400, error = "Malformed message."))
                    continue
                }
                val id = req.id ?: run {
                    conn.send(WsFrame(type = "response", status = 400, error = "Every request needs an id."))
                    null
                } ?: continue
                if (req.cancel) {
                    jobs.remove(id)?.cancel()
                    continue
                }
                if (!permits.tryAcquire()) {
                    conn.send(WsFrame(type = "response", id = id, status = 429, error = "Too many requests at once. Please wait a moment."))
                    continue
                }
                jobs[id] = launch {
                    try {
                        handle(conn, first.id, id, req, game, ai, rpc, hub)
                    } finally {
                        permits.release()
                        jobs.remove(id)
                    }
                }
            }
        } finally {
            jobs.values.forEach { it.cancel() }
            hub.unregister(conn)
        }
    }
}

private suspend fun handle(
    conn: RealtimeHub.Connection, userId: String, id: String, req: WsRequest,
    game: GameService, ai: AiService, rpc: RpcRouter, hub: RealtimeHub,
) {
    fun fail(status: Int, message: String) = WsFrame(type = "response", id = id, status = status, error = message)
    val reply: WsFrame = try {
        // Re-read the user each time: XP, track and onboarding state change between calls.
        val user = game.findUser(userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "This account no longer exists. Please sign up again.")
        val method = req.method.uppercase()
        val path = req.path.substringBefore('?').trim('/')
        when {
            method == "POST" && path == "ai/mentor" -> {
                mentorOverSocket(conn, id, user, req.body, ai)
                WsFrame(type = "response", id = id, status = 200)
            }
            method == "DELETE" && path == "auth/me" -> {
                game.deleteUser(user)
                conn.send(WsFrame(type = "response", id = id, status = 200, body = AppJson.encodeToJsonElement(DeletedDto.serializer(), DeletedDto(true))))
                hub.disconnectUser(userId)
                return
            }
            else -> {
                val r = rpc.call(user, method, req.path, req.body)
                if (r.changed) hub.notifyChanged(userId, except = conn)
                WsFrame(type = "response", id = id, status = r.status.value, body = r.body)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownstreamClosed) {
        return
    } catch (e: ApiException) {
        fail(e.status.value, e.message)
    } catch (e: SerializationException) {
        fail(400, "Invalid request: ${e.message?.take(200) ?: "malformed JSON"}")
    } catch (e: BadRequestException) {
        fail(400, "Invalid request.")
    } catch (e: IllegalArgumentException) {
        fail(400, "Invalid request: ${e.message?.take(200)}")
    } catch (e: Exception) {
        log.error("Unhandled error on WS {} {}", req.method, req.path, e)
        fail(500, "Something went wrong on our side. Please try again.")
    }
    conn.send(reply)
}

/** Same events as the SSE route (`{"delta":..}`, `{"error":..}`, `{"done":true}`), each as a "stream" frame. */
private suspend fun mentorOverSocket(conn: RealtimeHub.Connection, id: String, user: UserRecord, body: JsonElement?, ai: AiService) {
    val req = AppJson.decodeFromJsonElement(MentorRequest.serializer(), body ?: badRequest("Send at least one user message."))
    val usable = req.messages.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
    if (usable.isEmpty() || usable.last().role != "user") badRequest("Send at least one user message.")
    suspend fun <T> event(serializer: KSerializer<T>, value: T) {
        if (!conn.send(WsFrame(type = "stream", id = id, body = AppJson.encodeToJsonElement(serializer, value)))) {
            throw DownstreamClosed(IllegalStateException("socket closed"))
        }
    }
    when (val end = ai.mentor(user, req) { text, source -> event(MentorDelta.serializer(), MentorDelta(text, source)) }) {
        MentorEnd.Ok -> event(MentorDone.serializer(), MentorDone())
        MentorEnd.Fallback -> event(MentorDone.serializer(), MentorDone(source = "fallback"))
        is MentorEnd.Error -> {
            event(MentorError.serializer(), MentorError(end.message))
            event(MentorDone.serializer(), MentorDone())
        }
    }
}
