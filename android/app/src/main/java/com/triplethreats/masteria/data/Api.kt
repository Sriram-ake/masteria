package com.triplethreats.masteria.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Same settings as the backend (API.md: ignoreUnknownKeys + explicitNulls = false). */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

/** Every failure the UI sees. [offline] = the server couldn't be reached (show "check connection / settings"). */
class ApiException(message: String, val code: Int = 0, val offline: Boolean = false) : Exception(message)

/**
 * The backend API. Calls go over the realtime WebSocket when it is open (no per-call handshake) and fall
 * back to plain HTTPS otherwise, so the app keeps working if the socket is down or blocked.
 */
class Api(
    private val baseUrl: () -> String,
    private val token: () -> String?,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Render's free plan can take ~50 s to wake a sleeping instance; scans take up to ~60 s.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Set by the app container once the realtime client exists. */
    var realtime: RealtimeClient? = null

    // ------------------------------------------------------------------ auth
    suspend fun register(name: String, email: String, password: String): AuthResponse =
        post("/auth/register", RegisterRequest.serializer(), RegisterRequest(name, email, password), AuthResponse.serializer(), realtime = false)
    suspend fun login(email: String, password: String): AuthResponse =
        post("/auth/login", LoginRequest.serializer(), LoginRequest(email, password), AuthResponse.serializer(), realtime = false)
    suspend fun guest(name: String): AuthResponse =
        post("/auth/guest", GuestRequest.serializer(), GuestRequest(name), AuthResponse.serializer(), realtime = false)
    /** Exchanges a Firebase ID token for a Masteria session. Sent with the current (guest) token, if any, to keep guest progress. */
    suspend fun firebaseAuth(idToken: String, name: String?): AuthResponse =
        post("/auth/firebase", FirebaseAuthRequest.serializer(), FirebaseAuthRequest(idToken, name), AuthResponse.serializer(), realtime = false)
    suspend fun me(): UserDto = get("/auth/me", UserDto.serializer())
    suspend fun deleteAccount(): DeletedDto = send("DELETE", "/auth/me", null, DeletedDto.serializer())

    // ------------------------------------------------------------------ onboarding
    suspend fun tracks(): List<TrackDto> = get("/tracks", ListSerializer(TrackDto.serializer()))
    suspend fun onboard(req: OnboardingRequest): UserDto = post("/onboarding", OnboardingRequest.serializer(), req, UserDto.serializer())
    suspend fun diagnostic(trackId: String?): DiagnosticDto =
        get("/onboarding/diagnostic" + (trackId?.let { "?trackId=" + URLEncoder.encode(it, "UTF-8") } ?: ""), DiagnosticDto.serializer())
    suspend fun submitDiagnostic(req: DiagnosticSubmit): DiagnosticResult =
        post("/onboarding/diagnostic", DiagnosticSubmit.serializer(), req, DiagnosticResult.serializer())

    // ------------------------------------------------------------------ game
    suspend fun home(): HomeDto = get("/home", HomeDto.serializer())
    suspend fun map(): MapDto = get("/map", MapDto.serializer())
    suspend fun startQuest(topicId: String?, boss: Boolean): QuestSessionDto =
        post("/quests/start", StartQuestRequest.serializer(), StartQuestRequest(topicId, boss), QuestSessionDto.serializer())
    suspend fun quest(id: String): QuestSessionDto = get("/quests/${enc(id)}", QuestSessionDto.serializer())
    suspend fun answer(questId: String, answer: AnswerDto): AnswerResult =
        post("/quests/${enc(questId)}/answer", AnswerDto.serializer(), answer, AnswerResult.serializer())
    suspend fun complete(questId: String): QuestSummaryDto = send("POST", "/quests/${enc(questId)}/complete", null, QuestSummaryDto.serializer())
    suspend fun progress(): ProgressDto = get("/progress", ProgressDto.serializer())
    suspend fun profile(): ProfileDto = get("/profile", ProfileDto.serializer())
    suspend fun updateProfile(req: UpdateProfileRequest): ProfileDto =
        send("PUT", "/profile", AppJson.encodeToJsonElement(UpdateProfileRequest.serializer(), req), ProfileDto.serializer())
    suspend fun streak(): StreakDto = get("/streak", StreakDto.serializer())
    suspend fun report(questionId: String, reason: String?): ReportResponse =
        post("/questions/${enc(questionId)}/report", ReportRequest.serializer(), ReportRequest(reason), ReportResponse.serializer())

    // ------------------------------------------------------------------ AI
    suspend fun aiStatus(): AiStatusDto = get("/ai/status", AiStatusDto.serializer())
    suspend fun explain(req: ExplainRequest): ExplainResponse = post("/ai/explain", ExplainRequest.serializer(), req, ExplainResponse.serializer())
    suspend fun scan(req: ScanRequest): QuestSessionDto = post("/ai/scan", ScanRequest.serializer(), req, QuestSessionDto.serializer())

    /** Mentor reply as a stream of events: over the socket when open, else server-sent events over HTTP. */
    fun mentor(req: MentorRequest): Flow<MentorEvent> = flow {
        val body = AppJson.encodeToJsonElement(MentorRequest.serializer(), req)
        val rt = realtime
        if (rt != null && rt.isConnected) {
            var started = false
            try {
                rt.stream("POST", "/ai/mentor", body).collect { frame ->
                    started = true
                    if (frame.type == "stream") frame.body?.let { emitWire(it) }
                    else if ((frame.status ?: 200) >= 400) emit(MentorEvent.Failure(frame.error ?: "The mentor hit a snag. Please ask again."))
                }
                return@flow
            } catch (e: RealtimeUnavailable) {
                // Dropped mid-reply: re-asking over HTTP would repeat what's already on screen.
                if (started) {
                    emit(MentorEvent.Failure("The mentor was interrupted. Please ask again."))
                    return@flow
                }
            }
        }
        sse(body)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<MentorEvent>.emitWire(el: JsonElement) {
        val e = runCatching { AppJson.decodeFromJsonElement(MentorWireEvent.serializer(), el) }.getOrNull() ?: return
        when {
            e.error != null -> emit(MentorEvent.Failure(e.error))
            e.done -> emit(MentorEvent.Done)
            e.delta != null -> emit(MentorEvent.Delta(e.delta, e.source))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<MentorEvent>.sse(body: JsonElement) {
        val request = request("POST", "/ai/mentor", body).newBuilder().header("Accept", "text/event-stream").build()
        val call = http.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            emit(MentorEvent.Failure("Can't reach the mentor right now. Check your connection."))
            return
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(MentorEvent.Failure(errorMessage(resp.code, resp.body?.string())))
                return
            }
            val source = resp.body?.source() ?: return
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val el = runCatching { AppJson.parseToJsonElement(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    emitWire(el)
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) emit(MentorEvent.Failure("The mentor was interrupted. Please ask again."))
            }
        }
    }

    /** `/health` on an arbitrary address (Settings → Test). */
    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        val target = (url.trimEnd('/') + "/health").toHttpUrlOrNull() ?: return@withContext false
        runCatching {
            http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(target).build()).await().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ plumbing
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> get(path: String, out: KSerializer<T>): T = send("GET", path, null, out)

    private suspend fun <I, T> post(path: String, inS: KSerializer<I>, body: I, out: KSerializer<T>, realtime: Boolean = true): T =
        send("POST", path, AppJson.encodeToJsonElement(inS, body), out, realtime)

    private suspend fun <T> send(method: String, path: String, body: JsonElement?, out: KSerializer<T>, useRealtime: Boolean = true): T {
        val rt = realtime
        if (useRealtime && rt != null && rt.isConnected) {
            try {
                val frame = rt.request(method, path, body, timeoutMs = 90_000)
                val status = frame.status ?: 500
                if (status >= 400) {
                    throw ApiException(frame.error ?: errorMessage(status, null), code = status)
                }
                return AppJson.decodeFromJsonElement(out, frame.body ?: JsonNull)
            } catch (e: RealtimeUnavailable) {
                // Socket dropped or not ready: the same call over HTTP below. (Answer and complete are idempotent.)
            } catch (e: ApiException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("The server sent something unexpected. Please try again.")
            }
        } else if (useRealtime) {
            rt?.connect()
        }
        return withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request(method, path, body)).await()
            } catch (e: IOException) {
                throw ApiException("Can't reach the server. Check your connection and try again.", offline = true)
            }
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(errorMessage(resp.code, text), code = resp.code)
                try {
                    AppJson.decodeFromString(out, text)
                } catch (e: Exception) {
                    throw ApiException("The server sent something unexpected. Please try again.", code = resp.code)
                }
            }
        }
    }

    private fun request(method: String, path: String, body: JsonElement?): Request {
        val url = (baseUrl().trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw ApiException("The server address looks wrong. Fix it in Settings.", offline = true)
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        token()?.let { builder.header("Authorization", "Bearer $it") }
        val requestBody = when {
            body != null -> AppJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON)
            method == "POST" || method == "PUT" -> "{}".toRequestBody(JSON)
            else -> null
        }
        return builder.method(method, requestBody).build()
    }

    private fun errorMessage(code: Int, body: String?): String {
        val fromServer = body?.let { runCatching { AppJson.decodeFromString(ErrorDto.serializer(), it).error }.getOrNull() }
        return fromServer ?: when (code) {
            401 -> "Please log in again."
            404 -> "Not found."
            413 -> "That's too large to send."
            429 -> "Too many attempts. Please wait a minute and try again."
            in 500..599 -> "The server had a problem. Please try again."
            else -> "Something went wrong (HTTP $code)."
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** OkHttp call as a cancellable suspend function. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
