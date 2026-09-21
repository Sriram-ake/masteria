package com.triplethreats.masteria.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.triplethreats.masteria.ApiException
import com.triplethreats.masteria.AppJson
import com.triplethreats.masteria.ai.AiService
import com.triplethreats.masteria.ai.DownstreamClosed
import com.triplethreats.masteria.ai.MentorEnd
import com.triplethreats.masteria.auth.IdTokenVerifier
import com.triplethreats.masteria.badRequest
import com.triplethreats.masteria.db.UserRecord
import com.triplethreats.masteria.notFound
import com.triplethreats.masteria.service.GameService
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.head
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.writeStringUtf8
import java.util.Date

class JwtIssuer(secret: String) {
    val algorithm: Algorithm = Algorithm.HMAC256(secret)
    val verifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    fun issue(userId: String): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject(userId)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
        .sign(algorithm)

    companion object {
        const val ISSUER = "masteria"
        const val AUDIENCE = "masteria-app"
        const val AUTH = "auth-jwt"
    }
}

suspend fun ApplicationCall.currentUser(game: GameService): UserRecord {
    val id = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Please log in again.")
    return game.findUser(id) ?: throw ApiException(HttpStatusCode.Unauthorized, "This account no longer exists. Please sign up again.")
}

/** Rate-limit bucket for the account-creating / password-checking routes. */
val AuthRateLimit = RateLimitName("auth")

fun Route.publicRoutes(
    game: GameService,
    jwt: JwtIssuer,
    firebase: IdTokenVerifier?,
    health: () -> HealthDto,
) {
    // Keep-alive / uptime target (Render free plan spins down after ~15 min without traffic). Touches
    // no database and no AI, so pinging it every few minutes is free. HEAD is answered too, because
    // many uptime monitors use it.
    get("/health") { call.respond(health()) }
    head("/health") { call.respond(HttpStatusCode.OK) }
    get("/ping") { call.respondText("pong") }
    head("/ping") { call.respond(HttpStatusCode.OK) }
    get("/tracks") { call.respond(game.content.tracks.map { it.toDto() }) }

    rateLimit(AuthRateLimit) {
        post("/auth/register") {
            val req = call.receive<RegisterRequest>()
            val user = game.register(req.name, req.email, req.password)
            call.respond(HttpStatusCode.Created, AuthResponse(jwt.issue(user.id), game.userDto(user)))
        }
        post("/auth/login") {
            val req = call.receive<LoginRequest>()
            val user = game.login(req.email, req.password)
            call.respond(AuthResponse(jwt.issue(user.id), game.userDto(user)))
        }
        post("/auth/guest") {
            val req = runCatching { call.receive<GuestRequest>() }.getOrDefault(GuestRequest())
            val user = game.guest(req.name)
            call.respond(HttpStatusCode.Created, AuthResponse(jwt.issue(user.id), game.userDto(user)))
        }
        // Firebase sign-in and sign-up. The app signs in with the Firebase SDK, sends the Firebase ID token,
        // and gets a Masteria token back. If the call also carries a valid guest token, the guest account
        // (with all its progress) becomes this Firebase account.
        post("/auth/firebase") {
            val verifier = firebase ?: throw ApiException(HttpStatusCode.ServiceUnavailable,
                "Firebase sign-in isn't set up on this server yet (FIREBASE_PROJECT_ID is missing).")
            val req = call.receive<FirebaseAuthRequest>()
            if (req.idToken.isBlank() || req.idToken.length > 8192) badRequest("Missing Firebase ID token.")
            val identity = verifier.verify(req.idToken)
            val guest = call.request.headers[HttpHeaders.Authorization]
                ?.removePrefix("Bearer ")?.trim()
                ?.let { token -> runCatching { jwt.verifier.verify(token).subject }.getOrNull() }
                ?.let { game.findUser(it) }
                ?.takeIf { it.isGuest }
            val existedBefore = game.findUserByFirebaseUid(identity.uid) != null
            val user = game.firebaseSignIn(identity, req.name, guest)
            val status = if (existedBefore || guest != null) HttpStatusCode.OK else HttpStatusCode.Created
            call.respond(status, AuthResponse(jwt.issue(user.id), game.userDto(user)))
        }
    }
}

fun Route.learnerRoutes(game: GameService, hub: RealtimeHub) = authenticate(JwtIssuer.AUTH) {
    route("/auth/me") {
        get { call.respond(game.userDto(call.currentUser(game))) }
        delete {
            val user = call.currentUser(game)
            game.deleteUser(user)
            hub.disconnectUser(user.id)
            call.respond(DeletedDto(true))
        }
    }

    post("/onboarding") {
        val user = call.currentUser(game)
        call.respond(game.onboard(user, call.receive<OnboardingRequest>()))
        hub.notifyChanged(user.id)
    }
    get("/onboarding/diagnostic") {
        val user = call.currentUser(game)
        call.respond(game.diagnostic(user, call.request.queryParameters["trackId"]))
    }
    post("/onboarding/diagnostic") {
        val user = call.currentUser(game)
        call.respond(game.submitDiagnostic(user, call.receive<DiagnosticSubmit>()))
        hub.notifyChanged(user.id)
    }

    get("/home") { call.respond(game.home(call.currentUser(game))) }
    get("/map") { call.respond(game.map(call.currentUser(game))) }

    route("/quests") {
        post("/start") {
            val user = call.currentUser(game)
            val req = runCatching { call.receive<StartQuestRequest>() }.getOrElse {
                if (it is ApiException) throw it else StartQuestRequest()
            }
            call.respond(game.startQuest(user, req))
        }
        get("/{id}") {
            val user = call.currentUser(game)
            call.respond(game.getQuest(user, call.parameters["id"] ?: notFound("Quest not found.")))
        }
        post("/{id}/answer") {
            val user = call.currentUser(game)
            val id = call.parameters["id"] ?: notFound("Quest not found.")
            call.respond(game.answer(user, id, call.receive<AnswerDto>()))
        }
        post("/{id}/complete") {
            val user = call.currentUser(game)
            call.respond(game.complete(user, call.parameters["id"] ?: notFound("Quest not found.")))
            hub.notifyChanged(user.id)
        }
    }

    get("/progress") { call.respond(game.progress(call.currentUser(game))) }
    get("/profile") { call.respond(game.profile(call.currentUser(game))) }
    put("/profile") {
        val user = call.currentUser(game)
        call.respond(game.updateProfile(user, call.receive<UpdateProfileRequest>()))
        hub.notifyChanged(user.id)
    }
    get("/streak") { call.respond(game.streak(call.currentUser(game))) }

    post("/questions/{id}/report") {
        val user = call.currentUser(game)
        val id = call.parameters["id"] ?: notFound("Question not found.")
        val req = runCatching { call.receive<ReportRequest>() }.getOrDefault(ReportRequest())
        val q = game.bank.get(id) ?: notFound("Question not found.")
        call.respond(ReportResponse(game.bank.report(q, user.id, req.reason)))
    }
}

fun Route.aiRoutes(game: GameService, ai: AiService) = authenticate(JwtIssuer.AUTH) {
    route("/ai") {
        get("/status") { call.respond(ai.status()) }

        post("/explain") {
            val user = call.currentUser(game)
            call.respond(ai.explain(user, call.receive<ExplainRequest>()))
        }

        post("/scan") {
            val user = call.currentUser(game)
            call.respond(ai.scan(user, call.receive<ScanRequest>()))
        }

        post("/mentor") {
            val user = call.currentUser(game)
            val req = call.receive<MentorRequest>()
            val usable = req.messages.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
            if (usable.isEmpty() || usable.last().role != "user") badRequest("Send at least one user message.")

            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                suspend fun send(json: String) {
                    writeStringUtf8("data: $json\n\n")
                    flush()
                }
                try {
                    val end = ai.mentor(user, req) { text, source ->
                        send(AppJson.encodeToString(MentorDelta.serializer(), MentorDelta(text, source)))
                    }
                    when (end) {
                        MentorEnd.Ok -> send(AppJson.encodeToString(MentorDone.serializer(), MentorDone()))
                        MentorEnd.Fallback -> send(AppJson.encodeToString(MentorDone.serializer(), MentorDone(source = "fallback")))
                        is MentorEnd.Error -> {
                            send(AppJson.encodeToString(MentorError.serializer(), MentorError(end.message)))
                            send(AppJson.encodeToString(MentorDone.serializer(), MentorDone()))
                        }
                    }
                } catch (e: DownstreamClosed) {
                    // learner closed the stream; nothing left to send
                } catch (e: Exception) {
                    runCatching {
                        send(AppJson.encodeToString(MentorError.serializer(), MentorError("The mentor hit a snag. Please ask again.")))
                        send(AppJson.encodeToString(MentorDone.serializer(), MentorDone()))
                    }
                }
            }
        }
    }
}
