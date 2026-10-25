package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface MusicBrainzTransport {
    suspend fun get(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
    ): String
}

internal class KtorMusicBrainzTransport(
    private val baseUrl: String = "https://musicbrainz.org/ws/2",
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    },
) : MusicBrainzTransport {
    override suspend fun get(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
    ): String = client.get("${baseUrl.trimEnd('/')}/$path") {
        header(HttpHeaders.UserAgent, userAgent)
        parameters.forEach { (name, value) -> parameter(name, value) }
    }.bodyAsText()
}

/**
 * One limiter is shared by every endpoint used through a MusicBrainzClient.
 * It deliberately serializes dispatch as well as spacing request starts, which
 * is stricter than MusicBrainz's one-request-per-second requirement and avoids
 * endpoint-specific limiters accidentally racing each other.
 */
internal class MusicBrainzRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val minimumIntervalMs: Long = 1_000L,
) {
    private val mutex = Mutex()
    private var lastStartedAtMs: Long? = null

    init {
        require(minimumIntervalMs >= 1_000L) {
            "MusicBrainz requests must be limited to at most one per second"
        }
    }

    suspend fun <T> runLimited(block: suspend () -> T): T {
        mutex.lock()
        try {
            lastStartedAtMs?.let { lastStarted ->
                val remaining = minimumIntervalMs - (nowMillis() - lastStarted)
                if (remaining > 0L) delayMillis(remaining)
            }
            lastStartedAtMs = nowMillis()
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Enrichment, identity discovery and public catalog search must share one
 * per-process request budget. Individual MusicBrainzClient instances are not
 * allowed to independently send one request per second each.
 */
internal object MusicBrainzRequestBudget {
    val limiter = MusicBrainzRateLimiter()
}

internal class MusicBrainzClient(
    private val transport: MusicBrainzTransport = KtorMusicBrainzTransport(),
    internal val rateLimiter: MusicBrainzRateLimiter = MusicBrainzRequestBudget.limiter,
    val userAgent: String = defaultMusicBrainzUserAgent(),
    private val retryDelayMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    suspend fun get(
        path: String,
        parameters: Map<String, String> = mapOf("fmt" to "json"),
    ): String {
        var attempt = 1
        while (true) {
            try {
                // A retry is a new request and must pass through the same shared
                // budget as the first attempt and every other MusicBrainz endpoint.
                return rateLimiter.runLimited {
                    transport.get(path, parameters, userAgent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (response: ResponseException) {
                val status = response.response.status.value
                if (status != HTTP_TOO_MANY_REQUESTS && status != HTTP_SERVICE_UNAVAILABLE ||
                    attempt >= MAX_ATTEMPTS
                ) throw response

                val retryAfter = response.response.headers[HttpHeaders.RetryAfter]
                val delay = when {
                    retryAfter == null -> BACKOFF_MILLIS[attempt - 1]
                    else -> retryAfter.retryAfterMillisOrNull() ?: throw response
                }
                retryDelayMillis(delay)
                attempt += 1
            }
        }
    }

    private fun String.retryAfterMillisOrNull(): Long? {
        val seconds = toLongOrNull()?.takeIf { it in 0L..MAX_RETRY_AFTER_SECONDS } ?: return null
        return seconds * MILLIS_PER_SECOND
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val MAX_ATTEMPTS = 3
        const val MAX_RETRY_AFTER_SECONDS = 30L
        const val MILLIS_PER_SECOND = 1_000L
        val BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L)
    }
}

internal fun defaultMusicBrainzUserAgent(): String =
    "Bitchord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)"

internal class MusicBrainzMetadataEnrichmentProvider(
    private val client: MusicBrainzClient = MusicBrainzClient(),
) : CanonicalMetadataEnrichmentProvider {
    override val providerKey: String = MUSICBRAINZ_PROVIDER

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    override suspend fun lookupArtist(externalId: String): MetadataLookupResult<ArtistMetadataEnrichment> {
        val mbid = externalId.validMbidOrNull()
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.INVALID_EXTERNAL_ID)
        val raw = request("artist/$mbid") ?: return MetadataLookupResult.Failure(MetadataLookupFailure.UNAVAILABLE)
        val dto = decode<MusicBrainzArtistDto>(raw)
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        if (!dto.id.equals(mbid, ignoreCase = true)) {
            return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        }
        return MetadataLookupResult.Success(
            ArtistMetadataEnrichment(
                musicBrainzId = mbid,
                name = dto.name.clean(),
                sortName = dto.sortName.clean(),
                disambiguation = dto.disambiguation.clean(),
                country = dto.country.clean(),
                area = dto.area?.name.clean(),
            ),
        )
    }

    override suspend fun lookupRecording(externalId: String): MetadataLookupResult<RecordingMetadataEnrichment> {
        val mbid = externalId.validMbidOrNull()
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.INVALID_EXTERNAL_ID)
        val raw = request(
            path = "recording/$mbid",
            parameters = mapOf("fmt" to "json", "inc" to "artist-credits"),
        ) ?: return MetadataLookupResult.Failure(MetadataLookupFailure.UNAVAILABLE)
        val dto = decode<MusicBrainzRecordingDto>(raw)
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        if (!dto.id.equals(mbid, ignoreCase = true)) {
            return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        }
        return MetadataLookupResult.Success(
            RecordingMetadataEnrichment(
                musicBrainzId = mbid,
                title = dto.title.clean(),
                durationMs = dto.length?.takeIf { it >= 0L },
                artistDisplay = renderArtistCredit(dto.artistCredit),
                artistCredits = dto.artistCredit?.toEvidence(),
            ),
        )
    }

    override suspend fun lookupRelease(externalId: String): MetadataLookupResult<ReleaseMetadataEnrichment> {
        val mbid = externalId.validMbidOrNull()
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.INVALID_EXTERNAL_ID)
        val raw = request(
            path = "release/$mbid",
            parameters = mapOf("fmt" to "json", "inc" to "artist-credits"),
        ) ?: return MetadataLookupResult.Failure(MetadataLookupFailure.UNAVAILABLE)
        val dto = decode<MusicBrainzReleaseDto>(raw)
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        if (!dto.id.equals(mbid, ignoreCase = true)) {
            return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        }
        return MetadataLookupResult.Success(
            ReleaseMetadataEnrichment(
                musicBrainzId = mbid,
                title = dto.title.clean(),
                artistDisplay = renderArtistCredit(dto.artistCredit),
                releaseDate = dto.date.clean(),
                country = dto.country.clean(),
                status = dto.status.clean(),
                artistCredits = dto.artistCredit?.toEvidence(),
            ),
        )
    }

    private suspend fun request(
        path: String,
        parameters: Map<String, String> = mapOf("fmt" to "json"),
    ): String? = try {
        client.get(path, parameters)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Do not propagate URL/body/transport details into catalog-facing errors.
        null
    }

    private inline fun <reified T> decode(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()

    private fun renderArtistCredit(credits: List<MusicBrainzArtistCreditDto>?): String? {
        val present = credits ?: return null
        if (present.isEmpty()) return null
        return buildString {
            present.forEach { credit ->
                append(credit.name.clean() ?: credit.artist?.name.clean().orEmpty())
                append(credit.joinPhrase.orEmpty())
            }
        }.trim().takeIf(String::isNotEmpty)
    }

    private fun List<MusicBrainzArtistCreditDto>.toEvidence(): List<MusicBrainzArtistCredit> =
        mapNotNull { credit ->
            val artistId = credit.artist?.id.validMbidOrNull() ?: return@mapNotNull null
            val name = credit.name.clean() ?: credit.artist?.name.clean() ?: return@mapNotNull null
            MusicBrainzArtistCredit(
                musicBrainzArtistId = artistId,
                name = name,
                joinPhrase = credit.joinPhrase?.takeIf(String::isNotEmpty),
            )
        }

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.validMbidOrNull(): String? {
        val value = clean() ?: return null
        return value.takeIf { MBID_REGEX.matches(it) }
    }

    companion object {
        private val MBID_REGEX = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}

@Serializable
private data class MusicBrainzAreaDto(
    val name: String? = null,
)

@Serializable
private data class MusicBrainzArtistRefDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class MusicBrainzArtistCreditDto(
    val name: String? = null,
    @SerialName("joinphrase") val joinPhrase: String? = null,
    val artist: MusicBrainzArtistRefDto? = null,
)

@Serializable
private data class MusicBrainzArtistDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("sort-name") val sortName: String? = null,
    val disambiguation: String? = null,
    val country: String? = null,
    val area: MusicBrainzAreaDto? = null,
)

@Serializable
private data class MusicBrainzRecordingDto(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCreditDto>? = null,
)

@Serializable
private data class MusicBrainzReleaseDto(
    val id: String? = null,
    val title: String? = null,
    val date: String? = null,
    val country: String? = null,
    val status: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCreditDto>? = null,
)
