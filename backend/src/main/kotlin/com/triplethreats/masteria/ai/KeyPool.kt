package com.triplethreats.masteria.ai

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * NVIDIA NIM API keys used round-robin, so load is spread across keys and one bad key never takes the AI down.
 *  - 429 (rate limited) → that key rests for Retry-After, else 30 s, 60 s, 2 min ... up to 10 min.
 *  - 401 / 403 (invalid, expired or out of credits) → that key is sidelined for 30 min, then tried again.
 *  - Any success clears the key's failure count.
 * Keys are never logged; they appear as "key#2 (…a1b2)".
 */
class KeyPool(keys: List<String>, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(KeyPool::class.java)

    class Slot internal constructor(val index: Int, val key: String) {
        @Volatile internal var blockedUntil = 0L
        @Volatile internal var failures = 0
        @Volatile internal var lastProblem: String? = null
        val label: String get() = "key#${index + 1} (…${key.takeLast(4)})"
    }

    private val slots = keys.filter { it.isNotBlank() }.distinct().mapIndexed { i, k -> Slot(i, k) }
    private val cursor = AtomicInteger()

    val size: Int get() = slots.size
    fun healthyCount(): Int = clock().let { now -> slots.count { it.blockedUntil <= now } }

    /** Next usable key not in [exclude], round-robin; null when every key is resting or already tried. */
    fun acquire(exclude: Set<Slot> = emptySet()): Slot? {
        if (slots.isEmpty()) return null
        val now = clock()
        val start = Math.floorMod(cursor.getAndIncrement(), slots.size)
        for (i in slots.indices) {
            val s = slots[(start + i) % slots.size]
            if (s !in exclude && s.blockedUntil <= now) return s
        }
        return null
    }

    fun success(s: Slot) {
        if (s.failures > 0 || s.lastProblem != null) log.info("NIM {} is working again", s.label)
        s.failures = 0
        s.lastProblem = null
        s.blockedUntil = 0
    }

    fun rateLimited(s: Slot, retryAfterMs: Long?) {
        s.failures++
        val backoff = retryAfterMs?.coerceIn(1_000, 600_000)
            ?: (30_000L shl (s.failures - 1).coerceAtMost(4)).coerceAtMost(600_000)
        s.blockedUntil = clock() + backoff
        s.lastProblem = "rate limited"
        log.warn("NIM {} rate limited; resting {} s, rotating to the next key", s.label, backoff / 1000)
    }

    fun rejected(s: Slot, status: Int) {
        s.failures++
        s.blockedUntil = clock() + REJECTED_REST_MS
        s.lastProblem = "HTTP $status"
        log.warn("NIM {} rejected (HTTP {}): invalid, expired or out of credits; sidelined for 30 min", s.label, status)
    }

    /** For /ai/status and logs: one line per key, no secrets. */
    fun describe(): List<String> = clock().let { now ->
        slots.map { s ->
            if (s.blockedUntil > now) "${s.label}: resting ${(s.blockedUntil - now) / 1000}s (${s.lastProblem})" else "${s.label}: ok"
        }
    }

    companion object {
        const val REJECTED_REST_MS = 30 * 60_000L

        /** Parses Retry-After in seconds (the HTTP-date form is rare for APIs and ignored). */
        fun retryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.times(1000)
    }
}
