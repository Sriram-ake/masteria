package com.triplethreats.masteria

import com.triplethreats.masteria.routes.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Largest request body or WebSocket frame accepted (a 1 MB scan photo is ~1.4 MB as base64 JSON). */
const val MAX_BODY_BYTES = 4L * 1024 * 1024

/** Rejects bodies that declare more than [MAX_BODY_BYTES] before anything reads them into memory. */
val MaxRequestSize = createApplicationPlugin("MaxRequestSize") {
    onCall { call ->
        val length = call.request.contentLength()
        if (length != null && length > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto("That request is too large."))
        }
    }
}

/**
 * Optional self-ping for Render's free plan, which spins an instance down after ~15 minutes without
 * inbound traffic. The request goes out through Render's public URL, so it counts as traffic.
 * An external monitor (cron-job.org, UptimeRobot) hitting /health works just as well and also wakes a
 * sleeping instance, which a self-ping can't.
 */
object KeepAlive {
    private val log = LoggerFactory.getLogger(KeepAlive::class.java)

    fun start(config: AppConfig) {
        val base = config.keepAliveUrl ?: return
        val url = URI("$base/health")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        log.info("Keep-alive: pinging {} every {} min", url, config.keepAliveMinutes)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(60_000)
            while (true) {
                runCatching {
                    val req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                    client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
                }.onSuccess { if (it != 200) log.warn("Keep-alive ping got HTTP {}", it) }
                    .onFailure { log.warn("Keep-alive ping failed: {}", it.javaClass.simpleName) }
                delay(config.keepAliveMinutes * 60_000)
            }
        }
    }
}
