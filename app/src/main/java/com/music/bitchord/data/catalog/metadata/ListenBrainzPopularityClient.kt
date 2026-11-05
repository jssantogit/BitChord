package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/** Read-only, unauthenticated public popularity. No personal tokens or listens. */
internal interface ListenBrainzPopularityTransport {
    suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse
}

internal data class ListenBrainzPopularityHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal class KtorListenBrainzPopularityTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    },
) : ListenBrainzPopularityTransport {
    override suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse {
        val response = client.post("https://api.listenbrainz.org/1/popularity/recording") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent,
                "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)")
            setBody(Json.encodeToString(mapOf("recording_mbids" to recordingMbids)))
        }
        return ListenBrainzPopularityHttpResponse(
            status = response.status.value,
            body = if (response.status.value == 200) response.bodyAsText() else "",
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
        )
    }
}

/** Shared per-process one-request-per-second budget for public calls and scrobbles. */
internal class ListenBrainzRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastStart: Long? = null

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            lastStart?.let {
                val wait = 1_000L - (nowMillis() - it)
                if (wait > 0) waitMillis(wait)
            }
            lastStart = nowMillis()
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

internal object ListenBrainzRequestBudget {
    val limiter = ListenBrainzRateLimiter()
}

internal sealed interface ListenBrainzPopularityFetchResult {
    data class Success(
        val observations: List<ListenBrainzPopularityEvidence>,
        val fetchedAtMs: Long,
        val fromCache: Boolean,
    ) : ListenBrainzPopularityFetchResult
    data object InvalidRequest : ListenBrainzPopularityFetchResult
    data object Unavailable : ListenBrainzPopularityFetchResult
    data object Malformed : ListenBrainzPopularityFetchResult
}

/**
 * A small in-memory TTL avoids repeated public requests when switching tabs.
 * Only verified responses are cached. This does not persist personal data.
 * Network failure never converts missing data to zero or claims a live chart.
 */
internal class ListenBrainzPopularityClient(
    private val transport: ListenBrainzPopularityTransport = KtorListenBrainzPopularityTransport(),
    private val limiter: ListenBrainzRateLimiter = ListenBrainzRequestBudget.limiter,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private data class CacheValue(
        val evidence: List<ListenBrainzPopularityEvidence>,
        val atMs: Long,
    )

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<List<String>, CacheValue>()

    suspend fun recordingPopularity(ids: List<String>): ListenBrainzPopularityFetchResult {
        // Conservative batch size. No title or artist-name matching allowed.
        val normalized = ids.map { it.lowercase(Locale.ROOT) }
        if (normalized.isEmpty() || normalized.size > 100 ||
            normalized.any { !MBID.matches(it) } ||
            normalized.distinct().size != normalized.size
        ) return ListenBrainzPopularityFetchResult.InvalidRequest

        cacheMutex.lock()
        try {
            val saved = cache[normalized]
            if (saved != null && nowMillis() >= saved.atMs &&
                nowMillis() - saved.atMs < CACHE_TTL_MS
            ) {
                return ListenBrainzPopularityFetchResult.Success(
                    saved.evidence, saved.atMs, fromCache = true)
            }
        } finally {
            cacheMutex.unlock()
        }

        for (attempt in 0..1) {
            val response = try {
                limiter.run { transport.recordingPopularity(normalized) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ListenBrainzPopularityFetchResult.Unavailable
            }
            if (response.status == 200) {
                val parsed = parseListenBrainzRecordingPopularity(response.body, normalized)
                if (parsed !is ListenBrainzPopularityParseResult.Success) {
                    return ListenBrainzPopularityFetchResult.Malformed
                }
                val time = nowMillis()
                cacheMutex.lock()
                try {
                    if (cache.size >= MAX_CACHED_BATCHES) cache.clear()
                    cache[normalized] = CacheValue(parsed.observations, time)
                } finally {
                    cacheMutex.unlock()
                }
                return ListenBrainzPopularityFetchResult.Success(
                    parsed.observations, time, fromCache = false)
            }
            if (attempt == 0 && response.status in setOf(429, 503)) {
                // Clamp Retry-After; never trust unbounded server delays.
                retryDelay((response.retryAfterSeconds ?: 1L).coerceIn(1L, 30L) * 1_000L)
                continue
            }
            return ListenBrainzPopularityFetchResult.Unavailable
        }
        return ListenBrainzPopularityFetchResult.Unavailable
    }

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
        const val MAX_CACHED_BATCHES = 32
    }
}
