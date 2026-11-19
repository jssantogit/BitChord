package com.music.bitchord.data.catalog.provider.applemusicweb

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Gentle, process-wide start pacing for Apple Web API calls. Provider
 * authorization stays in RateLimitedProviderTransport; this class only
 * budgets physical requests, including retries. No blocking Thread.sleep.
 */
internal class AppleMusicWebRateLimiter(
    private val minimumIntervalMs: Long = 500L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var lastStartedMs: Long? = null

    init { require(minimumIntervalMs in 0L..10_000L) }

    suspend fun <T> run(operation: suspend () -> T): T {
        lock.lock()
        try {
            lastStartedMs?.let { prior ->
                val waitMs = (minimumIntervalMs - (clockMs() - prior)).coerceAtLeast(0L)
                if (waitMs > 0) sleep(waitMs)
            }
            lastStartedMs = clockMs()
        } finally {
            lock.unlock()
        }
        return operation()
    }
}

internal object AppleMusicWebRequestBudget {
    val limiter = AppleMusicWebRateLimiter()
}

/**
 * Restrict retries to transient server failures. A 403 or 429 always surfaces
 * to the provider policy and health layer. Retry-After above budget is a stop.
 * No unbounded waiting or retry storms on app resume.
 */
internal class AppleMusicWebRetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 400,
    val maxDelayMs: Long = 3_000,
) {
    init {
        require(maxAttempts in 1..4)
        require(baseDelayMs in 0L..10_000L)
        require(maxDelayMs in baseDelayMs..15_000L)
    }

    fun delayFor(status: Int, attempt: Int, retryAfterSeconds: Long? = null): Long? {
        if (status !in setOf(500, 502, 503, 504) ||
            attempt !in 1 until maxAttempts
        ) return null
        if (retryAfterSeconds != null) {
            if (retryAfterSeconds < 0 ||
                retryAfterSeconds > maxDelayMs / 1_000
            ) return null
            return retryAfterSeconds * 1_000
        }
        val backoff = baseDelayMs * (1L shl (attempt - 1))
        return backoff.coerceAtMost(maxDelayMs)
    }
}
