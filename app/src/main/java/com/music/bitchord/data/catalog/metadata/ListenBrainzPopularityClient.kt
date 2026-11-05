package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.get
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

internal const val LISTENBRAINZ_PUBLIC_TTL_MS = 6L * 60 * 60 * 1_000

/** Read-only, unauthenticated public popularity. No personal tokens or listens. */
internal interface ListenBrainzPopularityTransport {
    suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse
    suspend fun topRecordingsForArtist(artistMbid: String): ListenBrainzPopularityHttpResponse
}

internal data class ListenBrainzPopularityHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
    val rateLimitRemaining: Long? = null,
    val rateLimitResetInSeconds: Long? = null,
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
            rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull(),
            rateLimitResetInSeconds = response.headers["X-RateLimit-Reset-In"]?.toLongOrNull(),
        )
    }

    override suspend fun topRecordingsForArtist(artistMbid: String): ListenBrainzPopularityHttpResponse {
        val response = client.get("https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/$artistMbid") {
            header(HttpHeaders.UserAgent,
                "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)")
        }
        return ListenBrainzPopularityHttpResponse(
            status = response.status.value,
            body = if (response.status.value == 200) response.bodyAsText() else "",
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull(),
            rateLimitResetInSeconds = response.headers["X-RateLimit-Reset-In"]?.toLongOrNull(),
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
    private var serverResetNotBeforeMs: Long? = null

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            val now = nowMillis()
            val spacing = lastStart?.let { 1_000L - (now - it) } ?: 0L
            val reset = serverResetNotBeforeMs?.let { it - now } ?: 0L
            val wait = maxOf(spacing, reset)
            if (wait > 0L) waitMillis(wait)
            lastStart = nowMillis()
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /** Follow the server's reset budget for both public reads and scrobbles. */
    suspend fun observeRateLimit(remaining: Long?, resetInSeconds: Long?) {
        if (remaining != 0L || resetInSeconds == null || resetInSeconds <= 0L) return
        mutex.lock()
        try {
            val until = nowMillis() + resetInSeconds.coerceAtMost(300L) * 1_000L
            serverResetNotBeforeMs = maxOf(serverResetNotBeforeMs ?: until, until)
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

internal sealed interface ListenBrainzArtistTopFetchResult {
    data class Success(
        val recordings: List<ListenBrainzArtistTopRecording>,
        val fetchedAtMs: Long,
        val fromCache: Boolean,
    ) : ListenBrainzArtistTopFetchResult
    data object InvalidRequest : ListenBrainzArtistTopFetchResult
    data object Unavailable : ListenBrainzArtistTopFetchResult
    data object Malformed : ListenBrainzArtistTopFetchResult
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
    // Serialize same-category fetches and recheck TTL after waiting. The shared
    // rate limiter alone prevents bursts, not redundant simultaneous lookups.
    private val recordingFetchMutex = Mutex()
    private val artistFetchMutex = Mutex()
    private val cache = mutableMapOf<List<String>, CacheValue>()
    private data class ArtistCacheValue(
        val recordings: List<ListenBrainzArtistTopRecording>,
        val atMs: Long,
    )
    private val artistCache = mutableMapOf<String, ArtistCacheValue>()

    suspend fun topRecordingsForArtist(artistMbid: String): ListenBrainzArtistTopFetchResult {
        val id = artistMbid.lowercase(Locale.ROOT)
        if (!MBID.matches(id)) return ListenBrainzArtistTopFetchResult.InvalidRequest
        artistFetchMutex.lock()
        try {
        cacheMutex.lock()
        try {
            val saved = artistCache[id]
            if (saved != null && nowMillis() >= saved.atMs &&
                nowMillis() - saved.atMs < LISTENBRAINZ_PUBLIC_TTL_MS
            ) {
                return ListenBrainzArtistTopFetchResult.Success(saved.recordings, saved.atMs, fromCache = true)
            }
        } finally {
            cacheMutex.unlock()
        }

        for (attempt in 0..1) {
            val response = try {
                limiter.run { transport.topRecordingsForArtist(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ListenBrainzArtistTopFetchResult.Unavailable
            }
            limiter.observeRateLimit(response.rateLimitRemaining, response.rateLimitResetInSeconds)
            if (response.status == 200) {
                val parsed = parseListenBrainzArtistTopRecordings(response.body, id)
                if (parsed !is ListenBrainzArtistTopParseResult.Success) {
                    return ListenBrainzArtistTopFetchResult.Malformed
                }
                val time = nowMillis()
                cacheMutex.lock()
                try {
                    if (artistCache.size >= MAX_CACHED_BATCHES) artistCache.clear()
                    artistCache[id] = ArtistCacheValue(parsed.recordings, time)
                } finally {
                    cacheMutex.unlock()
                }
                return ListenBrainzArtistTopFetchResult.Success(parsed.recordings, time, fromCache = false)
            }
            if (attempt == 0 && response.status in setOf(429, 503)) {
                retryDelay((response.retryAfterSeconds ?: 1L).coerceIn(1L, 30L) * 1_000L)
                continue
            }
            return ListenBrainzArtistTopFetchResult.Unavailable
        }
        return ListenBrainzArtistTopFetchResult.Unavailable
        } finally {
            artistFetchMutex.unlock()
        }
    }

    suspend fun recordingPopularity(ids: List<String>): ListenBrainzPopularityFetchResult {
        // Conservative batch size. No title or artist-name matching allowed.
        val normalized = ids.map { it.lowercase(Locale.ROOT) }
        if (normalized.isEmpty() || normalized.size > 100 ||
            normalized.any { !MBID.matches(it) } ||
            normalized.distinct().size != normalized.size
        ) return ListenBrainzPopularityFetchResult.InvalidRequest

        // Canonicalize the batch for cache and upstream deduplication, but
        // preserve the requested order in every public response.
        val batch = normalized.sorted()
        recordingFetchMutex.lock()
        try {
        cacheMutex.lock()
        try {
            val saved = cache[batch]
            if (saved != null && nowMillis() >= saved.atMs &&
                nowMillis() - saved.atMs < LISTENBRAINZ_PUBLIC_TTL_MS
            ) {
                return ListenBrainzPopularityFetchResult.Success(
                    orderObservations(saved.evidence, normalized), saved.atMs, fromCache = true)
            }
        } finally {
            cacheMutex.unlock()
        }

        for (attempt in 0..1) {
            val response = try {
                limiter.run { transport.recordingPopularity(batch) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ListenBrainzPopularityFetchResult.Unavailable
            }
            limiter.observeRateLimit(response.rateLimitRemaining, response.rateLimitResetInSeconds)
            if (response.status == 200) {
                val parsed = parseListenBrainzRecordingPopularity(response.body, batch)
                if (parsed !is ListenBrainzPopularityParseResult.Success) {
                    return ListenBrainzPopularityFetchResult.Malformed
                }
                val time = nowMillis()
                cacheMutex.lock()
                try {
                    if (cache.size >= MAX_CACHED_BATCHES) cache.clear()
                    cache[batch] = CacheValue(parsed.observations, time)
                } finally {
                    cacheMutex.unlock()
                }
                return ListenBrainzPopularityFetchResult.Success(
                    orderObservations(parsed.observations, normalized), time, fromCache = false)
            }
            if (attempt == 0 && response.status in setOf(429, 503)) {
                // Clamp Retry-After; never trust unbounded server delays.
                retryDelay((response.retryAfterSeconds ?: 1L).coerceIn(1L, 30L) * 1_000L)
                continue
            }
            return ListenBrainzPopularityFetchResult.Unavailable
        }
        return ListenBrainzPopularityFetchResult.Unavailable
        } finally {
            recordingFetchMutex.unlock()
        }
    }

    private fun orderObservations(
        evidence: List<ListenBrainzPopularityEvidence>, requested: List<String>,
    ): List<ListenBrainzPopularityEvidence> {
        val byMbid = evidence.associateBy {
            when (it) {
                is ListenBrainzPopularityEvidence.Known -> it.recordingMbid
                is ListenBrainzPopularityEvidence.Unknown -> it.recordingMbid
            }
        }
        return requested.map(byMbid::getValue)
    }

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val MAX_CACHED_BATCHES = 32
    }
}
