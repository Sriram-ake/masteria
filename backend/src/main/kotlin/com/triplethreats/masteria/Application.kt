package com.triplethreats.masteria

import com.triplethreats.masteria.ai.AiService
import com.triplethreats.masteria.ai.NimClient
import com.triplethreats.masteria.ai.QuestionPipeline
import com.triplethreats.masteria.content.ContentRepository
import com.triplethreats.masteria.content.QuestionBank
import com.triplethreats.masteria.db.JsonFileStore
import com.triplethreats.masteria.db.MongoStore
import com.triplethreats.masteria.db.Store
import com.triplethreats.masteria.auth.FirebaseTokenVerifier
import com.triplethreats.masteria.auth.IdTokenVerifier
import com.triplethreats.masteria.routes.AuthRateLimit
import com.triplethreats.masteria.routes.ErrorDto
import com.triplethreats.masteria.routes.HealthDto
import com.triplethreats.masteria.routes.RealtimeHub
import com.triplethreats.masteria.routes.RpcRouter
import com.triplethreats.masteria.routes.realtimeRoutes
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds
import com.triplethreats.masteria.routes.JwtIssuer
import com.triplethreats.masteria.routes.aiRoutes
import com.triplethreats.masteria.routes.learnerRoutes
import com.triplethreats.masteria.routes.publicRoutes
import com.triplethreats.masteria.service.GameService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger("com.triplethreats.masteria.Application")

/** Everything the HTTP layer needs, wired once. */
class Services(
    val config: AppConfig,
    val content: ContentRepository,
    val store: Store,
    val bank: QuestionBank,
    val nim: NimClient,
    val game: GameService,
    val pipeline: QuestionPipeline,
    val ai: AiService,
    val jwt: JwtIssuer,
    val firebase: IdTokenVerifier?,
    val hub: RealtimeHub = RealtimeHub(),
) {
    private val closed = AtomicBoolean(false)
    val startedAt: Long = System.currentTimeMillis()

    /** Cheap health snapshot: no database or AI calls, so keep-alive pings cost nothing. */
    fun health() = HealthDto(
        status = "ok", ai = ai.status(), store = store.name, firebase = firebase != null, websocket = true,
        uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000,
    )
    val rpc: RpcRouter by lazy { RpcRouter(game, ai, ::health) }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runBlocking { store.close() } }
        runCatching { nim.close() }
        log.info("Masteria stopped; data flushed.")
    }

    companion object {
        fun create(config: AppConfig): Services {
            val content = ContentRepository.load(config.contentDir)
            val store: Store = config.mongoUri?.let { MongoStore(it) }
                ?: JsonFileStore(config.dataDir.resolve("masteria-db.json"))
            runBlocking { store.init() }
            val bank = QuestionBank(content, store)
            runBlocking { bank.init() }
            val nim = NimClient(config)
            val game = GameService(config, content, store, bank)
            val pipeline = QuestionPipeline(nim, bank, content)
            game.topUp = { topicId, rating -> pipeline.topUpAsync(topicId, rating) }
            val ai = AiService(config, nim, pipeline, game)
            val firebase = config.firebaseProjectId?.let { FirebaseTokenVerifier(it) }
            return Services(config, content, store, bank, nim, game, pipeline, ai, JwtIssuer(config.jwtSecret), firebase)
        }
    }
}

fun main() {
    val config = AppConfig.load()
    log.info("Starting Masteria backend: {}", config)
    val services = Services.create(config)
    log.info("Store: {}; tracks: {}; vetted questions: {}", services.store.name, services.content.tracks.size, services.content.vetted.size)
    services.nim.probeInBackground()
    if (config.mongoUri == null && config.onRender) {
        log.warn("MONGODB_URI is not set: on Render the JSON file store is wiped on every deploy or restart. Set MONGODB_URI to keep accounts and progress.")
    }
    if (!config.jwtSecretFromEnv && config.onRender) {
        log.warn("JWT_SECRET is not set: a new secret is generated on every restart on Render, which signs every user out. Set JWT_SECRET.")
    }
    if (config.firebaseProjectId == null) log.info("Firebase sign-in disabled (FIREBASE_PROJECT_ID not set).")
    KeepAlive.start(config)
    val server = embeddedServer(Netty, port = config.port, host = config.host) { module(services) }
    Runtime.getRuntime().addShutdownHook(Thread { services.shutdown() })
    server.start(wait = true)
}

fun Application.module(services: Services) {
    install(DefaultHeaders) { header("X-Engine", "Masteria") }
    // Behind Render's proxy the socket peer is the proxy; X-Forwarded-For carries the real client IP.
    if (services.config.trustProxy) install(XForwardedHeaders)
    install(MaxRequestSize)
    install(WebSockets) {
        pingPeriod = 20.seconds   // keeps the connection alive through proxies and detects dead phones
        timeout = 45.seconds
        maxFrameSize = MAX_BODY_BYTES
        masking = false
    }
    install(RateLimit) {
        register(AuthRateLimit) {
            rateLimiter(limit = services.config.authRateLimitPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(CallLogging) {
        level = Level.INFO
        // Only method, path, status and timing — never bodies, headers or tokens.
        format { call -> "${call.response.status()?.value ?: "-"} ${call.request.httpMethod.value} ${call.request.path()} ${call.processingTimeMillis()}ms" }
    }
    install(ContentNegotiation) { json(AppJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorDto(e.message)) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("Invalid request: ${rootMessage(e)}"))
        }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("Invalid request: ${e.message?.take(200) ?: "malformed JSON"}"))
        }
        exception<NotFoundException> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorDto("Not found.")) }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Too many attempts. Please wait a minute and try again."))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("Something went wrong on our side. Please try again."))
        }
    }
    install(Authentication) {
        jwt(JwtIssuer.AUTH) {
            realm = "masteria"
            verifier(services.jwt.verifier)
            // WebSocket clients that can't set headers may pass ?token= on /ws only.
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.queryParameters["token"]
                        ?.takeIf { call.request.path() == "/ws" && it.isNotBlank() }
                        ?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            validate { cred -> if (!cred.payload.subject.isNullOrBlank()) JWTPrincipal(cred.payload) else null }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("Please log in again.")) }
        }
    }
    monitor.subscribe(ApplicationStopped) { services.shutdown() }

    routing {
        publicRoutes(services.game, services.jwt, services.firebase, services::health)
        learnerRoutes(services.game, services.hub)
        aiRoutes(services.game, services.ai)
        realtimeRoutes(services.game, services.ai, services.rpc, services.hub)
        route("{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorDto("No such endpoint: ${call.request.httpMethod.value} ${call.request.path()}")) }
        }
    }
}

private fun rootMessage(e: Throwable): String {
    var t: Throwable = e
    while (t.cause != null && t.cause !== t) t = t.cause!!
    return (t.message ?: "malformed body").lineSequence().first().take(200)
}
