package com.triplethreats.masteria

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/** Shared JSON settings (API.md: ignoreUnknownKeys + explicitNulls = false on both sides). */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

/** Thrown by services; StatusPages turns it into `{"error": message}` with [status]. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun badRequest(msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)
fun notFound(msg: String): Nothing = throw ApiException(HttpStatusCode.NotFound, msg)
fun conflict(msg: String): Nothing = throw ApiException(HttpStatusCode.Conflict, msg)
fun forbidden(msg: String): Nothing = throw ApiException(HttpStatusCode.Forbidden, msg)
fun unprocessable(msg: String): Nothing = throw ApiException(HttpStatusCode.UnprocessableEntity, msg)
