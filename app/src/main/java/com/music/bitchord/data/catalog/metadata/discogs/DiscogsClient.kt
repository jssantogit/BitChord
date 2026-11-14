package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Fixed paths only; no untrusted web link is ever used as an API URL. */
internal interface DiscogsTransport {
    suspend fun get(path: String, userAgent: String): DiscogsHttpResponse
}

internal data class DiscogsHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal class KtorDiscogsTransport(
    private val baseUrl: String = "https://api.discogs.com",
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsTransport {
    // Query parameters are a single hardcoded constant, never provider input.
    private val allowedPath = Regex(
        """^(?:releases|masters)/[1-9][0-9]{0,11}$|^masters/[1-9][0-9]{0,11}/versions\?page=1&per_page=8$""",
    )

    override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
        require(allowedPath.matches(path)) { "Invalid Discogs endpoint" }
        val response = client.get("${baseUrl.trimEnd('/')}/$path") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
        }
        val size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (size != null && size > MAX_RESPONSE_BYTES) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        // Body size cannot rely on Content-Length: chunked/malicious responses
        // must be rejected BEFORE Ktor builds a potentially unbounded String.
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            output.write(buffer, 0, count)
        }
        val body = output.toByteArray().toString(Charsets.UTF_8)
        val retry = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()?.takeIf { it in 0..MAX_RETRY_AFTER }
        return DiscogsHttpResponse(response.status.value, body, retry)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 131_072L
        const val MAX_RETRY_AFTER = 86_400L
    }
}

/** Conservative separate budget; no bypass through one limiter per endpoint. */
internal class DiscogsRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMs: Long = 3_000L,
) {
    private val mutex = Mutex()
    private var lastRequestAt: Long? = null
    /** Monotonic deadline recorded under the same mutex as dispatch. */
    private var throttledUntilMs: Long? = null

    init { require(minimumIntervalMs >= 0L) }

    suspend fun <T> runLimited(block: suspend () -> T): T {
        mutex.lock()
        try {
            lastRequestAt?.let {
                val remaining = minimumIntervalMs - (nowMillis() - it)
                val cooldown = throttledUntilMs?.minus(nowMillis()) ?: 0L
                val pause = maxOf(remaining, cooldown)
                if (pause > 0L) delayMillis(pause)
            } ?: run {
                val cooldown = throttledUntilMs?.minus(nowMillis()) ?: 0L
                if (cooldown > 0L) delayMillis(cooldown)
            }
            lastRequestAt = nowMillis()
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Record a 429 cooldown before releasing the process-wide dispatch mutex,
     * so a concurrent caller cannot race past Retry-After.
     * A missing header uses a conservative 30s pause; an explicit longer
     * retry period is honored up to 24 hours rather than retrying prematurely.
     */
    suspend fun runHttpLimited(block: suspend () -> DiscogsHttpResponse): DiscogsHttpResponse =
        runLimited {
            val response = block()
            if (response.status == 429) {
                val seconds = (response.retryAfterSeconds ?: 30L).coerceIn(3L, 86_400L)
                val target = nowMillis() + seconds * 1_000L
                throttledUntilMs = maxOf(throttledUntilMs ?: Long.MIN_VALUE, target)
            }
            response
        }
}

internal object DiscogsRequestBudget {
    val limiter = DiscogsRateLimiter()
}

internal sealed interface DiscogsLookupResult {
    data class Found(val record: DiscogsRecord) : DiscogsLookupResult
    data class FoundMaster(val record: DiscogsMasterRecord) : DiscogsLookupResult
    data object Disabled : DiscogsLookupResult
    data object InvalidId : DiscogsLookupResult
    data object Missing : DiscogsLookupResult
    data object Unauthorized : DiscogsLookupResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsLookupResult
    data object Unavailable : DiscogsLookupResult
    data object Malformed : DiscogsLookupResult
}

/**
 * A real HTTP adapter, intentionally disabled by default until Discogs terms
 * and the project's distribution constraints have been approved. The release
 * endpoint is read-only, anonymous, and does not request images or marketplace data.
 */
internal class DiscogsClient(
    private val transport: DiscogsTransport = KtorDiscogsTransport(),
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    /** Discogs Master is advisory group context only; never a verified Release. */
    suspend fun fetchMaster(id: Long): DiscogsLookupResult {
        if (!enabled) return DiscogsLookupResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsLookupResult.InvalidId
        val response = try {
            limiter.runHttpLimited { transport.get("masters/$id", userAgent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsLookupResult.Unavailable
        }
        DiscogsDiagnosticStatus.httpResult(DiscogsKind.MASTER, response.status)
        return when (response.status) {
            200 -> {
                val link = DiscogsLink(DiscogsKind.MASTER, id)
                val record = DiscogsRecordParser.parseMaster(link, response.body)
                    ?: return DiscogsLookupResult.Malformed
                DiscogsLookupResult.FoundMaster(record)
            }
            401, 403 -> DiscogsLookupResult.Unauthorized
            404 -> DiscogsLookupResult.Missing
            429 -> DiscogsLookupResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsLookupResult.Unavailable
        }
    }

    /**
     * Discogs Master versions are a paginated public read-only endpoint.
     * At most eight versions are examined; when pagination exceeds that
     * budget we return Incomplete, never a fabricated unique edition.
     */
    suspend fun fetchMasterVersions(id: Long): DiscogsMasterVersionsResult {
        if (!enabled) return DiscogsMasterVersionsResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsMasterVersionsResult.Malformed
        val response = try {
            limiter.runHttpLimited {
                transport.get("masters/$id/versions?page=1&per_page=8", userAgent)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsMasterVersionsResult.Unavailable
        }
        if (response.status != 200) return DiscogsMasterVersionsResult.Unavailable
        return try {
            if (response.body.length > 131_072) return DiscogsMasterVersionsResult.Malformed
            val obj = Json.parseToJsonElement(response.body) as? JsonObject
                ?: return DiscogsMasterVersionsResult.Malformed
            val paging = obj["pagination"] as? JsonObject
                ?: return DiscogsMasterVersionsResult.Malformed
            val page = (paging["page"] as? JsonPrimitive)?.intOrNull
            val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
            val perPage = (paging["per_page"] as? JsonPrimitive)?.intOrNull
            val items = (paging["items"] as? JsonPrimitive)?.intOrNull
            if (page != 1 || perPage != 8 || pages == null || pages !in 1..100_000 ||
                items == null || items !in 1..100_000
            ) return DiscogsMasterVersionsResult.Malformed
            if (pages != 1 || items > 8) return DiscogsMasterVersionsResult.Incomplete
            val rows = obj["versions"] as? JsonArray
                ?: return DiscogsMasterVersionsResult.Malformed
            if (rows.size != items) return DiscogsMasterVersionsResult.Malformed
            val ids = rows.map { row ->
                val value = ((row as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
                    ?: return DiscogsMasterVersionsResult.Malformed
                if (value !in 1..999_999_999_999L) {
                    return DiscogsMasterVersionsResult.Malformed
                }
                value
            }
            if (ids.distinct().size != ids.size) DiscogsMasterVersionsResult.Malformed
            else DiscogsMasterVersionsResult.Found(ids)
        } catch (_: Exception) {
            DiscogsMasterVersionsResult.Malformed
        }
    }

    suspend fun fetchRelease(id: Long): DiscogsLookupResult {
        if (!enabled) return DiscogsLookupResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsLookupResult.InvalidId
        val response = try {
            limiter.runHttpLimited { transport.get("releases/$id", userAgent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsLookupResult.Unavailable
        }
        DiscogsDiagnosticStatus.httpResult(DiscogsKind.RELEASE, response.status)
        return when (response.status) {
            200 -> {
                val link = DiscogsLink(DiscogsKind.RELEASE, id)
                val record = DiscogsRecordParser.parse(link, response.body)
                    ?: return DiscogsLookupResult.Malformed
                DiscogsLookupResult.Found(record)
            }
            401, 403 -> DiscogsLookupResult.Unauthorized
            404 -> DiscogsLookupResult.Missing
            429 -> DiscogsLookupResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsLookupResult.Unavailable
        }
    }
}

/** A complete, bounded list of versions is essential before claiming an
 * exact edition is unique. The Main Release alone is never exhaustive. */
internal sealed interface DiscogsMasterVersionsResult {
    data class Found(val releaseIds: List<Long>) : DiscogsMasterVersionsResult
    data object Incomplete : DiscogsMasterVersionsResult
    data object Disabled : DiscogsMasterVersionsResult
    data object Malformed : DiscogsMasterVersionsResult
    data object Unavailable : DiscogsMasterVersionsResult
}

/**
 * Ephemeral source-specific cache; does not persist in the local music catalog,
 * backup, source-assets or Room. No stale-while-revalidate for API Content.
 */
internal class DiscogsMemoryEvidenceStore(private val maxEntries: Int = 128) {
    private data class Key(
        val canonicalMbid: String, val kind: DiscogsKind, val discogsId: Long,
    )
    private val entries = linkedMapOf<Key, DiscogsObservation>()

    init { require(maxEntries > 0) }

    @Synchronized
    fun put(value: DiscogsObservation) {
        val key = Key(value.canonicalMbid.lowercase(Locale.ROOT), value.kind, value.record.link.id)
        entries.remove(key)
        entries[key] = value
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(canonicalMbid: String, link: DiscogsLink, nowMillis: Long): DiscogsObservation? {
        val key = Key(canonicalMbid.lowercase(Locale.ROOT), link.kind, link.id)
        val value = entries[key] ?: return null
        if (!value.isDisplayableAt(nowMillis)) {
            entries.remove(key)
            return null
        }
        return value
    }

    @Synchronized
    fun clear() = entries.clear()
}
