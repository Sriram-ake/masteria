package com.triplethreats.masteria

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64

/** Parses a minimal `.env` file: KEY=VALUE lines, `#` comments, optional quotes, optional `export `. */
object DotEnv {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length - 1)
            } else {
                val hash = value.indexOf(" #")
                if (hash >= 0) value = value.substring(0, hash).trim()
            }
            out[key] = value
        }
        return out
    }

    fun load(): Map<String, String> {
        val candidates = listOf(File(".env"), File("backend/.env"))
        val file = candidates.firstOrNull { it.isFile } ?: return emptyMap()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyMap())
    }
}

data class AppConfig(
    val port: Int,
    val host: String = "0.0.0.0",
    val nvidiaApiKey: String?,
    val nimBaseUrl: String,
    val chatModel: String,
    val fallbackModels: List<String>,
    val verifyModel: String,
    val visionModel: String,
    val visionFallbackModels: List<String>,
    val jwtSecret: String,
    val dataDir: Path,
    val mongoUri: String?,
    val zone: ZoneId,
    val contentDir: String?,
    /** Firebase project whose ID tokens /auth/firebase accepts; null disables Firebase sign-in. */
    val firebaseProjectId: String? = null,
    /** Trust X-Forwarded-* headers (true behind Render's proxy) so rate limits key on the real client IP. */
    val trustProxy: Boolean = false,
    /** Max auth requests (register/login/guest/firebase) per client IP per minute. */
    val authRateLimitPerMinute: Int = 30,
    /** True when JWT_SECRET came from the environment rather than a generated file. */
    val jwtSecretFromEnv: Boolean = true,
    /** True when running on Render (the RENDER env var is set). */
    val onRender: Boolean = false,
    /** Every NIM key to rotate through (NVIDIA_API_KEYS, NVIDIA_API_KEY, NVIDIA_API_KEY_2..20), first = primary. */
    val nvidiaApiKeys: List<String> = listOfNotNull(nvidiaApiKey?.takeIf { it.isNotBlank() }),
    /** Public base URL to ping so a free Render instance never idles out; null disables the self-ping. */
    val keepAliveUrl: String? = null,
    val keepAliveMinutes: Long = 5,
) {
    /** Safe for logs: never includes the key itself. */
    override fun toString(): String =
        "AppConfig(port=$port, host=$host, nimKeys=${nvidiaApiKeys.size}, base=$nimBaseUrl, " +
            "chat=$chatModel, fallbacks=$fallbackModels, verify=$verifyModel, vision=$visionModel, dataDir=$dataDir, " +
            "mongo=${if (mongoUri.isNullOrBlank()) "off" else "on"}, zone=$zone, " +
            "firebase=${firebaseProjectId ?: "off"}, trustProxy=$trustProxy, render=$onRender)"

    companion object {
        fun load(env: Map<String, String> = System.getenv(), dotEnv: Map<String, String> = DotEnv.load()): AppConfig {
            fun get(key: String): String? = env[key]?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }
            fun list(key: String, default: String) = (get(key) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

            val dataDir = Path.of(get("DATA_DIR") ?: "./data").toAbsolutePath().normalize()
            Files.createDirectories(dataDir)
            val visionModel = get("NIM_VISION_MODEL") ?: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
            val onRender = !get("RENDER").isNullOrBlank()
            // Key rotation: NVIDIA_API_KEYS="k1,k2,k3" and/or NVIDIA_API_KEY, NVIDIA_API_KEY_2 ... NVIDIA_API_KEY_20.
            val nimKeys = (list("NVIDIA_API_KEYS", "") + listOfNotNull(get("NVIDIA_API_KEY")) +
                (1..20).mapNotNull { get("NVIDIA_API_KEY_$it") })
                .map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }.distinct()
            val envSecret = get("JWT_SECRET")
            return AppConfig(
                port = get("PORT")?.toIntOrNull() ?: 8080,
                host = get("HOST") ?: "0.0.0.0",
                nvidiaApiKey = nimKeys.firstOrNull(),
                nvidiaApiKeys = nimKeys,
                nimBaseUrl = (get("NIM_BASE_URL") ?: "https://integrate.api.nvidia.com/v1").trimEnd('/'),
                chatModel = get("NIM_CHAT_MODEL") ?: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                fallbackModels = list("NIM_FALLBACK_MODELS", "openai/gpt-oss-20b,meta/llama-3.2-11b-vision-instruct"),
                verifyModel = get("NIM_VERIFY_MODEL") ?: "openai/gpt-oss-20b",
                visionModel = visionModel,
                visionFallbackModels = list("NIM_VISION_FALLBACK_MODELS", "meta/llama-3.2-11b-vision-instruct").filter { it != visionModel },
                jwtSecret = envSecret ?: loadOrCreateSecret(dataDir),
                dataDir = dataDir,
                mongoUri = get("MONGODB_URI"),
                zone = runCatching { ZoneId.of(get("TIMEZONE") ?: "Asia/Kolkata") }.getOrDefault(ZoneId.of("Asia/Kolkata")),
                contentDir = get("CONTENT_DIR"),
                firebaseProjectId = get("FIREBASE_PROJECT_ID"),
                trustProxy = get("TRUST_PROXY")?.lowercase()?.let { it == "true" || it == "1" } ?: onRender,
                authRateLimitPerMinute = get("AUTH_RATE_LIMIT_PER_MINUTE")?.toIntOrNull()?.coerceAtLeast(1) ?: 30,
                jwtSecretFromEnv = envSecret != null,
                onRender = onRender,
                // KEEP_ALIVE_URL wins; KEEP_ALIVE=true uses Render's own public URL (RENDER_EXTERNAL_URL).
                keepAliveUrl = (get("KEEP_ALIVE_URL")
                    ?: get("RENDER_EXTERNAL_URL")?.takeIf { get("KEEP_ALIVE")?.lowercase() in setOf("true", "1") })
                    ?.trimEnd('/'),
                keepAliveMinutes = get("KEEP_ALIVE_MINUTES")?.toLongOrNull()?.coerceIn(1, 14) ?: 5,
            )
        }

        private fun loadOrCreateSecret(dataDir: Path): String {
            val file = dataDir.resolve("jwt-secret.txt")
            if (Files.exists(file)) {
                val s = Files.readString(file).trim()
                if (s.length >= 32) return s
            }
            val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            Files.writeString(file, secret)
            return secret
        }
    }
}
