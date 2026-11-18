package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSingleFlight
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MUSICBRAINZ_BOUNDED_RESPONSE_MAX_BYTES = 2_000_000L

/**
 * Non-retriable XML proof body failure. A deterministic size violation
 * must not re-download the same oversized response in the retry loop.
 * Transient transport IO still follows the standard retry policy.
 */
internal class MusicBrainzBoundedResponseException(message: String) : IOException(message)

internal interface MusicBrainzTransport {
    suspend fun get(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
    ): String
}

/** Opt-in bounded body contract; unbounded transports must never service proof requests. */
internal interface BoundedMusicBrainzTransport {
    suspend fun getLimited(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
        maxResponseBytes: Long,
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
) : MusicBrainzTransport, BoundedMusicBrainzTransport {
    override suspend fun get(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
    ): String = measureMetadataHttp(
        provider = "musicbrainz",
        endpoint = metadataEndpointLabel(path),
        status = { response: Pair<Int, String> -> response.first },
    ) {
        val response = request(path, parameters, userAgent)
        response.status.value to response.bodyAsText()
    }.second

    override suspend fun getLimited(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
        maxResponseBytes: Long,
    ): String = measureMetadataHttp(
        provider = "musicbrainz",
        endpoint = metadataEndpointLabel(path),
        status = { response: Pair<Int, String> -> response.first },
    ) {
        require(maxResponseBytes > 0L && maxResponseBytes <= MUSICBRAINZ_BOUNDED_RESPONSE_MAX_BYTES)
        val response = request(path, parameters, userAgent, automaticStatusValidation = false)
        val status = response.status.value
        if (status !in 200..299) {
            val text = "HTTP $status response body skipped by bounded request"
            val statusFailure = when (status) {
                in 300..399 -> RedirectResponseException(response, text)
                in 400..499 -> ClientRequestException(response, text)
                in 500..599 -> ServerResponseException(response, text)
                else -> ResponseException(response, text)
            }
            response.bodyAsChannel().cancel(IOException("Discarding non-success MusicBrainz response body"))
            throw statusFailure
        }
        status to response.readBodyLimited(maxResponseBytes)
    }.second

    private suspend fun request(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
        automaticStatusValidation: Boolean = true,
    ): HttpResponse = client.get("${baseUrl.trimEnd('/')}/$path") {
        if (!automaticStatusValidation) expectSuccess = false
        header(HttpHeaders.UserAgent, userAgent)
        parameters.forEach { (name, value) -> parameter(name, value) }
    }

    private suspend fun HttpResponse.readBodyLimited(maxBytes: Long): String {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            val tooLarge = MusicBrainzBoundedResponseException("MusicBrainz XML response exceeds limit")
            bodyAsChannel().cancel(tooLarge)
            throw tooLarge
        }
        val channel: ByteReadChannel = bodyAsChannel()
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192L).toInt())
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            // Read at most one byte beyond the limit: this detects chunked or
            // under-reported bodies without ever buffering their full contents.
            val requested = minOf(buffer.size.toLong(), maxBytes - total + 1L).toInt()
            val read = channel.readAvailable(buffer, 0, requested)
            if (read == -1) break
            if (read == 0) {
                yield()
                continue
            }
            if (total + read > maxBytes) {
                val tooLarge = MusicBrainzBoundedResponseException("MusicBrainz XML response exceeds limit")
                channel.cancel(tooLarge)
                throw tooLarge
            }
            output.write(buffer, 0, read)
            total += read
        }
        if (declaredLength != null && total != declaredLength) {
            throw MusicBrainzBoundedResponseException("MusicBrainz XML Content-Length mismatch")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

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
    val transport: MusicBrainzTransport by lazy { KtorMusicBrainzTransport() }
    val inFlight = ProviderSingleFlight<String>()
}

/** A bounded proof and an unlimited search can never share HTTP results.
 * Different capability tickets (and revisions) must never share producer work. */
private data class MusicBrainzInFlightKey(
    val transport: MusicBrainzTransport,
    val gate: ProviderPolicyGate?,
    val revision: Long?,
    val capability: ProviderCapability,
    val path: String,
    val parameters: Map<String, String>,
    val userAgent: String,
    val maxResponseBytes: Long?,
)

internal class MusicBrainzClient(
    private val transport: MusicBrainzTransport = MusicBrainzRequestBudget.transport,
    internal val rateLimiter: MusicBrainzRateLimiter = MusicBrainzRequestBudget.limiter,
    val userAgent: String = defaultMusicBrainzUserAgent(),
    private val retryDelayMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val admission: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
    private val singleFlight: ProviderSingleFlight<String> = MusicBrainzRequestBudget.inFlight,
) {
    suspend fun get(
        path: String,
        parameters: Map<String, String> = mapOf("fmt" to "json"),
        capability: ProviderCapability = ProviderCapability.IDENTITY,
    ): String = request(path, parameters, capability, null) {
        transport.get(path, parameters, userAgent)
    }

    suspend fun getLimited(
        path: String,
        parameters: Map<String, String>,
        maxResponseBytes: Long,
        capability: ProviderCapability = ProviderCapability.IDENTITY,
    ): String {
        require(maxResponseBytes > 0L && maxResponseBytes <= MUSICBRAINZ_BOUNDED_RESPONSE_MAX_BYTES)
        val bounded = transport as? BoundedMusicBrainzTransport
            ?: throw IOException("MusicBrainz transport does not support bounded responses")
        return request(path, parameters, capability, maxResponseBytes) {
            bounded.getLimited(path, parameters, userAgent, maxResponseBytes)
        }
    }

    private suspend fun request(
        path: String,
        parameters: Map<String, String>,
        capability: ProviderCapability,
        maxResponseBytes: Long?,
        call: suspend () -> String,
    ): String {
        val gate = admission()
        val provider = ProviderKey("musicbrainz")
        // Android can never treat an uninitialized runtime as permission to
        // contact MusicBrainz. A headless plain JVM test can supply a fake
        // transport before Android infrastructure exists.
        if (gate == null && ProviderRuntime.isAndroidEnvironment()) {
            throw ProviderRequestDeniedException(capability, provider)
        }
        val ticket = gate?.open(capability, provider)
        if (gate != null && ticket == null) {
            throw ProviderRequestDeniedException(capability, provider)
        }
        fun ensureAdmitted() {
            if (gate != null && !gate.accept(requireNotNull(ticket))) {
                throw ProviderRequestDeniedException(capability, provider)
            }
        }

        val key = MusicBrainzInFlightKey(
            transport = transport,
            gate = gate,
            revision = ticket?.revision,
            capability = capability,
            path = path,
            parameters = parameters.toMap(),
            userAgent = userAgent,
            maxResponseBytes = maxResponseBytes,
        )
        return singleFlight.runGuarded(
            gate = gate,
            capability = capability,
            provider = provider,
            key = key,
        ) {
            var attempt = 1
            while (true) {
                try {
                    ensureAdmitted()
                    // Each retry is an independent network request and goes
                    // through the global 1rps limiter. Even after its wait,
                    // revoke must be checked again BEFORE touching transport.
                    val response = rateLimiter.runLimited {
                        ensureAdmitted()
                        call().also { ensureAdmitted() }
                    }
                    ensureAdmitted()
                    return@runGuarded response
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (response: ResponseException) {
                    val status = response.response.status.value
                    if ((status != HTTP_TOO_MANY_REQUESTS &&
                            status != HTTP_SERVICE_UNAVAILABLE) ||
                        attempt >= MAX_ATTEMPTS
                    ) throw response
                    val retryAfter = response.response.headers[HttpHeaders.RetryAfter]
                    val wait = if (retryAfter == null) BACKOFF_MILLIS[attempt - 1]
                        else retryAfter.retryAfterMillisOrNull() ?: throw response
                    retryDelayMillis(wait)
                    attempt += 1
                } catch (bounded: MusicBrainzBoundedResponseException) {
                    // Size and declared-length violations are deterministic:
                    // retrying downloads more untrusted XML for no benefit.
                    throw bounded
                } catch (io: IOException) {
                    if (attempt >= MAX_ATTEMPTS) throw io
                    retryDelayMillis(BACKOFF_MILLIS[attempt - 1])
                    attempt += 1
                }
            }
            error("MusicBrainz request loop unexpectedly returned")
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

    override suspend fun lookupRecording(externalId: String): MetadataLookupResult<RecordingMetadataEnrichment> =
        lookupRecordingInternal(externalId, requireOfficialAudio = false)

    /** Chart-only lookup: do not infer Official from a recording title, artist
     * credit or external popularity. Requires an exact linked audio edition. */
    internal suspend fun lookupOfficialAudioRecording(
        externalId: String,
    ): MetadataLookupResult<RecordingMetadataEnrichment> =
        lookupRecordingInternal(externalId, requireOfficialAudio = true)

    private suspend fun lookupRecordingInternal(
        externalId: String, requireOfficialAudio: Boolean,
    ): MetadataLookupResult<RecordingMetadataEnrichment> {
        val mbid = externalId.validMbidOrNull()
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.INVALID_EXTERNAL_ID)
        val raw = request(
            path = "recording/$mbid",
            parameters = mapOf("fmt" to "json", "inc" to
                if (requireOfficialAudio) "artist-credits+releases+release-groups"
                else "artist-credits"),
        ) ?: return MetadataLookupResult.Failure(MetadataLookupFailure.UNAVAILABLE)
        val dto = decode<MusicBrainzRecordingDto>(raw)
            ?: return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        if (!dto.id.equals(mbid, ignoreCase = true)) {
            return MetadataLookupResult.Failure(MetadataLookupFailure.MALFORMED_RESPONSE)
        }
        val officialAudioEdition = if (requireOfficialAudio)
            dto.verifiedOfficialAudioRelease() else null
        return MetadataLookupResult.Success(
            RecordingMetadataEnrichment(
                musicBrainzId = mbid,
                title = dto.title.clean(),
                durationMs = dto.length?.takeIf { it >= 0L },
                artistDisplay = renderArtistCredit(dto.artistCredit),
                artistCredits = dto.artistCredit?.toEvidence(),
                officialAudioReleaseMbid = officialAudioEdition?.first,
                officialAudioReleaseGroupMbid = officialAudioEdition?.second,
            ),
        )
    }

    /** No bootlegs, video recordings, fan compilations, live-only versions,
     * DJ mixes or Other/video releases are eligible for the public chart. */
    private fun MusicBrainzRecordingDto.verifiedOfficialAudioRelease(): Pair<String, String?>? {
        if (video == true) return null
        val disallowed = setOf(
            "live", "compilation", "remix", "dj-mix", "mixtape/street",
            "demo", "interview", "spokenword", "audiobook", "audio drama",
        )
        return releases.orEmpty()
            .filter { release ->
                release.status.equals("Official", ignoreCase = true) &&
                    release.id.validMbidOrNull() != null &&
                    release.group?.primaryType?.lowercase(java.util.Locale.ROOT) in
                        setOf("album", "single", "ep") &&
                    release.group?.secondaryTypes.orEmpty().none {
                        it.lowercase(java.util.Locale.ROOT) in disallowed
                    }
            }
            .sortedWith(compareBy<MusicBrainzRecordingReleaseDto> {
                it.date.orEmpty().ifBlank { "9999-12-31" }
            }.thenBy { it.id.orEmpty() })
            .firstOrNull()?.let { release ->
                release.id.validMbidOrNull()?.let { confirmedId ->
                    confirmedId to release.group?.id.validMbidOrNull()
                }
            }
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
    val video: Boolean? = null,
    val releases: List<MusicBrainzRecordingReleaseDto>? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCreditDto>? = null,
)

@Serializable
private data class MusicBrainzRecordingReleaseDto(
    val id: String? = null,
    val date: String? = null,
    val status: String? = null,
    @SerialName("release-group") val group: MusicBrainzRecordingGroupDto? = null,
)

@Serializable
private data class MusicBrainzRecordingGroupDto(
    val id: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String>? = null,
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
