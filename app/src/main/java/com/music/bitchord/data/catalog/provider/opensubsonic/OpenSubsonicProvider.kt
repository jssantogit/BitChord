package com.music.bitchord.data.catalog.provider.opensubsonic

import com.music.bitchord.data.catalog.identity.IsrcNormalizer
import com.music.bitchord.data.catalog.provider.ProviderAlbum
import com.music.bitchord.data.catalog.provider.ProviderArtist
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.ProviderRemoteRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URI
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal sealed interface OpenSubsonicAuth {
    class Password(
        val username: String,
        val password: String,
    ) : OpenSubsonicAuth {
        init {
            require(username.isNotBlank()) { "OpenSubsonic username must not be blank" }
            require(password.isNotEmpty()) { "OpenSubsonic password must not be empty" }
        }

        override fun toString(): String = "Password(username=$username, password=<redacted>)"
    }

    class ApiKey(
        val value: String,
    ) : OpenSubsonicAuth {
        init {
            require(value.isNotBlank()) { "OpenSubsonic API key must not be blank" }
        }

        override fun toString(): String = "ApiKey(<redacted>)"
    }
}

internal data class OpenSubsonicAccountConfig(
    /** Stable local account key, not a server/music identifier. */
    val accountId: String,
    val serverUrl: String,
    val auth: OpenSubsonicAuth,
) {
    init {
        require(accountId.matches(Regex("[A-Za-z0-9._-]+"))) {
            "OpenSubsonic accountId must contain only letters, digits, '.', '_' or '-'"
        }
        val parsed = runCatching { URI(serverUrl.trim()) }.getOrNull()
        require(parsed != null && parsed.host != null && parsed.userInfo == null) {
            "OpenSubsonic serverUrl must be an absolute HTTP(S) URL without embedded credentials"
        }
        require(parsed.scheme.equals("https", ignoreCase = true) || parsed.scheme.equals("http", ignoreCase = true)) {
            "OpenSubsonic serverUrl must use http or https"
        }
    }

    val normalizedServerUrl: String = serverUrl.trim().trimEnd('/')
    val usesTls: Boolean = normalizedServerUrl.startsWith("https://", ignoreCase = true)
}

internal interface OpenSubsonicTransport {
    suspend fun get(endpoint: String, parameters: Map<String, String>): String
}

internal class KtorOpenSubsonicTransport(
    serverUrl: String,
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
    },
) : OpenSubsonicTransport {
    private val baseUrl = serverUrl.trim().trimEnd('/')

    override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
        client.get("$baseUrl/rest/$endpoint.view") {
            parameters.forEach { (name, value) -> parameter(name, value) }
        }.bodyAsText()
}

/**
 * Counts only, never source IDs, credentials or URLs. Optional collection stays
 * Catalog-internal and cannot affect sync correctness if its observer fails.
 */
internal data class OpenSubsonicEnumerationMetrics(
    val pageRequests: Int,
    val albumRequests: Int,
    val trackCount: Int,
    val peakAlbumRequestsInFlight: Int,
    val elapsedMillis: Long,
)

/**
 * OpenSubsonic/Navidrome adapter. It enumerates the remote library using ID3-
 * oriented APIs (`getAlbumList2` + `getAlbum`) and emits provider-neutral rows.
 * No canonical IDs are created here.
 */
internal class OpenSubsonicProvider(
    private val config: OpenSubsonicAccountConfig,
    private val transport: OpenSubsonicTransport = KtorOpenSubsonicTransport(config.normalizedServerUrl),
    private val pageSize: Int = 500,
    private val saltFactory: () -> String = ::defaultOpenSubsonicSalt,
    private val maxConcurrentAlbumFetches: Int = 4,
    private val onEnumerationMetrics: ((OpenSubsonicEnumerationMetrics) -> Unit)? = null,
) : RemoteCatalogProvider {
    init {
        require(pageSize in 1..500) { "OpenSubsonic pageSize must be between 1 and 500" }
        require(maxConcurrentAlbumFetches in 1..8) { "OpenSubsonic album concurrency must be between 1 and 8" }
    }

    override val providerKey: String = "opensubsonic:${config.accountId}"
    internal val ratingScopeId: String get() = config.ratingScopeId

    /** No secrets/URLs escape this boolean guard or its callers. */
    internal fun matchesSavedConfig(saved: OpenSubsonicAccountConfig?): Boolean =
        saved != null &&
            saved.accountId == config.accountId &&
            saved.normalizedServerUrl == config.normalizedServerUrl &&
            when {
                config.auth is OpenSubsonicAuth.ApiKey && saved.auth is OpenSubsonicAuth.ApiKey ->
                    config.auth.value == saved.auth.value
                config.auth is OpenSubsonicAuth.Password && saved.auth is OpenSubsonicAuth.Password ->
                    config.auth.username == saved.auth.username &&
                        config.auth.password == saved.auth.password
                else -> false
            }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun health(): ProviderHealth = try {
        val response = request("ping")
        ProviderHealth(
            reachable = true,
            serverType = response.type,
            serverVersion = response.serverVersion,
            protocolVersion = response.version,
            openSubsonic = response.openSubsonic == true,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Avoid surfacing transport exception text: request URLs may contain an
        // API key or salted token query parameters.
        ProviderHealth(
            reachable = false,
            detail = "OpenSubsonic request failed",
        )
    }

    override suspend fun capabilities(): ProviderCapabilities {
        // This endpoint is intentionally public in OpenSubsonic, so credentials
        // are not sent merely to discover extension support.
        val response = request(
            endpoint = "getOpenSubsonicExtensions",
            authenticated = false,
        )
        val extensionNames = response.openSubsonicExtensions
            .orEmpty()
            .map { extension -> extension.name.lowercase() }
            .toSet()
        return ProviderCapabilities(
            librarySync = true,
            healthCheck = true,
            directPlay = true,
            serverTranscoding = "transcoding" in extensionNames,
            apiKeyAuthentication = "apikeyauthentication" in extensionNames,
        )
    }

    /**
     * Public extension-manifest discovery: no account credentials sent, no
     * caching by UI re-render and no inference from ping/health. The generation
     * is supplied by the account lifecycle owner, not guessed from a timestamp.
     */
    internal suspend fun streamCapabilityEvidence(generation: Long): OpenSubsonicStreamCapabilitySnapshot {
        require(generation >= 0) { "Generation must be nonnegative" }
        var manifestWasRead = false
        val evidence = try {
            val response = request("getOpenSubsonicExtensions", authenticated = false)
            val manifest = response.openSubsonicExtensions
            if (manifest == null) {
                OpenSubsonicStreamConfidence.UNKNOWN to OpenSubsonicStreamEvidenceBasis.MALFORMED_OR_INCOMPLETE
            } else {
                manifestWasRead = true
                val matching = manifest.filter { it.name.equals("transcoding", ignoreCase = true) }
                when {
                    matching.size > 1 ->
                        OpenSubsonicStreamConfidence.UNKNOWN to OpenSubsonicStreamEvidenceBasis.MALFORMED_OR_INCOMPLETE
                    matching.isEmpty() ->
                        OpenSubsonicStreamConfidence.UNSUPPORTED to OpenSubsonicStreamEvidenceBasis.MANIFEST_ABSENT
                    matching.single().versions.any { it <= 0 } ->
                        OpenSubsonicStreamConfidence.UNKNOWN to OpenSubsonicStreamEvidenceBasis.MALFORMED_OR_INCOMPLETE
                    1 in matching.single().versions ->
                        OpenSubsonicStreamConfidence.ADVERTISED to OpenSubsonicStreamEvidenceBasis.MANIFEST_V1
                    matching.single().versions.isEmpty() ->
                        OpenSubsonicStreamConfidence.UNKNOWN to OpenSubsonicStreamEvidenceBasis.MALFORMED_OR_INCOMPLETE
                    else ->
                        OpenSubsonicStreamConfidence.UNSUPPORTED to OpenSubsonicStreamEvidenceBasis.VERSION_INCOMPATIBLE
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // API/transport messages may contain auth URLs. Never surface them.
            OpenSubsonicStreamConfidence.UNKNOWN to OpenSubsonicStreamEvidenceBasis.NO_RESPONSE
        }
        val traditional = OpenSubsonicStreamCapability(
            OpenSubsonicStreamMechanism.TRADITIONAL_STREAM_BEST_EFFORT,
            if (manifestWasRead) OpenSubsonicStreamConfidence.ADVERTISED else OpenSubsonicStreamConfidence.UNKNOWN,
            if (manifestWasRead) OpenSubsonicStreamEvidenceBasis.PROTOCOL_SPEC else OpenSubsonicStreamEvidenceBasis.NO_RESPONSE,
        )
        return OpenSubsonicStreamCapabilitySnapshot(
            generation = generation,
            traditional = traditional,
            transcoding = OpenSubsonicStreamCapability(
                OpenSubsonicStreamMechanism.EXTENSION_TRANSCODING_V1,
                evidence.first, evidence.second,
            ),
            configurationIsCurrent = ::matchesSavedConfig,
        )
    }

    override suspend fun librarySnapshot(): ProviderLibrarySnapshot {
        val startNanos = System.nanoTime()
        val counts = EnumerationCounts()
        val albumIds = enumerateAlbumIds(counts)
        val tracks = ArrayList<ProviderTrack>()
        val seenSourceKeys = HashSet<String>()

        // Allocate at most maxConcurrentAlbumFetches coroutines/results per batch.
        // awaitAll maintains input order even when the responses arrive reversed.
        // Any failure or cancellation aborts the entire coroutineScope: a partial
        // response can never be tagged complete or passed to the Room sync.
        coroutineScope {
            for (batch in albumIds.chunked(maxConcurrentAlbumFetches)) {
                val pages = batch.map { albumId ->
                    async {
                        val inFlight = counts.active.incrementAndGet()
                        counts.peak.updateAndGet { prior -> maxOf(prior, inFlight) }
                        counts.albumRequests.incrementAndGet()
                        try {
                            fetchAlbumTracks(albumId)
                        } finally {
                            counts.active.decrementAndGet()
                        }
                    }
                }.awaitAll()
                for (albumTracks in pages) {
                    for (track in albumTracks) {
                        if (!seenSourceKeys.add(track.externalTrackId)) {
                            throw OpenSubsonicProtocolException("Duplicate source ID in album enumeration")
                        }
                        tracks += track
                    }
                }
            }
        }
        // Measurement is observational only. Never let logging or callbacks
        // change the success/failure truth of a verified complete snapshot.
        runCatching {
            onEnumerationMetrics?.invoke(OpenSubsonicEnumerationMetrics(
                pageRequests = counts.pageRequests,
                albumRequests = counts.albumRequests.get(),
                trackCount = tracks.size,
                peakAlbumRequestsInFlight = counts.peak.get(),
                elapsedMillis = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0),
            ))
        }
        return ProviderLibrarySnapshot(tracks = tracks, complete = true)
    }

    private suspend fun fetchAlbumTracks(albumId: String): List<ProviderTrack> {
        val response = request(endpoint = "getAlbum", extraParameters = mapOf("id" to albumId))
        val album = response.album
            ?: throw OpenSubsonicProtocolException("getAlbum returned no album")
        if (album.id.trim() != albumId) {
            throw OpenSubsonicProtocolException("getAlbum response did not match request")
        }
        val songs = album.song
            ?: if (album.songCount == 0) emptyList() else
                throw OpenSubsonicProtocolException("getAlbum returned no song list")
        if (album.songCount != null && album.songCount != songs.size) {
            throw OpenSubsonicProtocolException("getAlbum song count is inconsistent")
        }
        return songs.asSequence().filterNot(SubsonicSong::isDir)
            .map { song -> mapTrack(song, album) }.toList()
    }

    private class EnumerationCounts {
        var pageRequests: Int = 0 // single coroutine, pagination is sequential
        val albumRequests = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
    }

    private suspend fun enumerateAlbumIds(counts: EnumerationCounts): List<String> {
        val ids = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        var offset = 0

        while (true) {
            counts.pageRequests += 1
            val response = request(
                endpoint = "getAlbumList2",
                extraParameters = mapOf(
                    "type" to "alphabeticalByArtist",
                    "size" to pageSize.toString(),
                    "offset" to offset.toString(),
                ),
            )
            // A successful HTTP response without the expected envelope is not
            // proof that the library is empty. Treat it as incomplete and NEVER
            // allow an availability prune against this response.
            val listing = response.albumList2
                ?: throw OpenSubsonicProtocolException("getAlbumList2 returned no album list")
            // An omitted/null nested album array is not the same as an
            // explicitly complete empty page; never prune cached assets for it.
            val page = listing.album
                ?: throw OpenSubsonicProtocolException("getAlbumList2 returned no album array")
            if (page.isEmpty()) break
            if (page.size > pageSize) {
                throw OpenSubsonicProtocolException("Album page exceeds requested size")
            }
            for (album in page) {
                val id = album.id.trim()
                if (id.isEmpty() || !seen.add(id)) {
                    throw OpenSubsonicProtocolException("Duplicate or blank album ID in pagination")
                }
                ids += id
            }
            if (page.size < pageSize) break
            offset += page.size
        }

        return ids
    }

    private fun mapTrack(song: SubsonicSong, album: SubsonicAlbum): ProviderTrack {
        val externalTrackId = song.id.trim()
        if (externalTrackId.isEmpty()) {
            throw OpenSubsonicProtocolException("Song is missing its provider ID")
        }
        val externalAlbumId = (song.albumId ?: album.id).trim()
        val artistDisplay = song.displayArtist.clean()
            ?: song.artist.clean()
            ?: album.displayArtist.clean()
            ?: album.artist.clean()
            ?: "Unknown Artist"
        val albumArtistDisplay = album.displayArtist.clean()
            ?: album.artist.clean()
            ?: artistDisplay
        val trackArtists = mapStructuredArtists(
            structured = song.artists,
            fallbackArtistId = song.artistId,
            fallbackDisplay = artistDisplay,
        )
        val albumArtists = mapStructuredArtists(
            structured = album.artists,
            fallbackArtistId = album.artistId,
            fallbackDisplay = albumArtistDisplay,
        )
        val coverArtId = song.coverArt.clean() ?: album.coverArt.clean()
        val artworkUri = coverArtId?.let { artworkId -> providerUri("artwork", artworkId) }
        val albumTitle = album.name.clean()
            ?: album.title.clean()
            ?: album.album.clean()
            ?: song.album.clean()
            ?: externalAlbumId

        return ProviderTrack(
            externalTrackId = externalTrackId,
            title = song.title.clean() ?: externalTrackId,
            artistDisplay = artistDisplay,
            externalArtistId = song.artistId.clean() ?: album.artistId.clean(),
            durationMs = song.duration
                ?.takeIf { seconds -> seconds >= 0 }
                ?.toLong()
                ?.times(1_000L),
            trackNumber = song.track,
            musicBrainzRecordingId = song.musicBrainzId.clean(),
            album = externalAlbumId.takeIf(String::isNotEmpty)?.let {
                ProviderAlbum(
                    externalAlbumId = externalAlbumId,
                    title = albumTitle,
                    artistDisplay = albumArtistDisplay,
                    externalArtistId = album.artistId.clean() ?: song.artistId.clean(),
                    musicBrainzReleaseId = album.musicBrainzId.clean(),
                    artworkUri = album.coverArt.clean()?.let { artworkId ->
                        providerUri("artwork", artworkId)
                    } ?: artworkUri,
                    artists = albumArtists,
                )
            },
            // This is deliberately a credential-free logical URI. The future
            // PlaybackResolver can resolve providerKey + sourceKey into a signed/
            // authenticated stream request without secrets leaking into Room.
            assetUri = providerUri("asset", externalTrackId),
            artworkUri = artworkUri,
            dateAddedSeconds = parseCreatedEpochSeconds(song.created ?: album.created),
            contentType = song.contentType.clean(),
            suffix = song.suffix.clean(),
            bitRateKbps = song.bitRate,
            bitDepth = song.bitDepth,
            samplingRateHz = song.samplingRate,
            artists = trackArtists,
            isrcs = IsrcNormalizer.normalizeDistinct(song.isrc.orEmpty()),
            remoteRating = ProviderRemoteRating(
                numeric = parseNumericRating(song.userRating),
                starred = parseStarred(song.starred),
            ),
        )
    }

    /**
     * OpenSubsonic's structured artist ids are identity evidence. Display text is
     * never tokenized: if the server exposes only one artistId for a display such
     * as "A & B feat. C", that remains one provider alias until stronger evidence
     * arrives.
     */
    private fun mapStructuredArtists(
        structured: List<SubsonicArtist>?,
        fallbackArtistId: String?,
        fallbackDisplay: String?,
    ): List<ProviderArtist> {
        val explicit = structured.orEmpty().mapNotNull { artist ->
            val externalId = artist.id.clean() ?: return@mapNotNull null
            ProviderArtist(
                externalArtistId = externalId,
                name = artist.name.clean() ?: fallbackDisplay.clean() ?: externalId,
                musicBrainzArtistId = artist.musicBrainzId.clean(),
            )
        }
        if (explicit.isNotEmpty()) return explicit

        val fallbackId = fallbackArtistId.clean() ?: return emptyList()
        return listOf(
            ProviderArtist(
                externalArtistId = fallbackId,
                name = fallbackDisplay.clean() ?: fallbackId,
                musicBrainzArtistId = null,
            ),
        )
    }

    private suspend fun request(
        endpoint: String,
        extraParameters: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): SubsonicResponse {
        val parameters = linkedMapOf(
            "v" to OPEN_SUBSONIC_API_VERSION,
            "c" to OPEN_SUBSONIC_CLIENT_NAME,
            "f" to "json",
        )
        if (authenticated) {
            parameters.putAll(openSubsonicAuthenticationParameters(config.auth, saltFactory))
        }
        parameters.putAll(extraParameters)

        val raw = transport.get(endpoint, parameters)
        val envelope = json.decodeFromString<SubsonicEnvelope>(raw)
        val response = envelope.response
        if (!response.status.equals("ok", ignoreCase = true)) {
            val error = response.error
            throw OpenSubsonicApiException(
                code = error?.code,
                serverMessage = error?.message,
            )
        }
        return response
    }

    /** Invalid wire values are quarantined, not converted into local scores. */
    private fun parseNumericRating(value: JsonElement?): RemoteNumericRating {
        if (value == null) return RemoteNumericRating()
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        return when {
            number == 0 -> RemoteNumericRating(RemoteNumericRatingKind.CLEARED_ZERO)
            number != null && number in 1..5 -> RemoteNumericRating(RemoteNumericRatingKind.VALUE, number)
            else -> RemoteNumericRating(RemoteNumericRatingKind.INVALID)
        }
    }

    /** A dated favorite is not a numeric five-star rating. */
    private fun parseStarred(value: JsonElement?): RemoteStarredKind {
        if (value == null) return RemoteStarredKind.NOT_PROVIDED
        val primitive = value as? JsonPrimitive ?: return RemoteStarredKind.INVALID
        if (!primitive.isString) {
            return when (primitive.booleanOrNull) {
                true -> RemoteStarredKind.STARRED
                false -> RemoteStarredKind.NOT_STARRED
                null -> RemoteStarredKind.INVALID
            }
        }
        return if (runCatching { OffsetDateTime.parse(primitive.content) }.isSuccess) {
            RemoteStarredKind.STARRED
        } else {
            RemoteStarredKind.INVALID
        }
    }

    private fun providerUri(kind: String, providerId: String): String =
        "opensubsonic://$kind/${encode(config.accountId)}/${encode(providerId)}"

    private fun encode(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun parseCreatedEpochSeconds(raw: String?): Long? {
        val value = raw.clean() ?: return null
        return runCatching { OffsetDateTime.parse(value).toInstant().epochSecond }.getOrNull()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}

private class OpenSubsonicProtocolException(message: String) : IllegalStateException(message)

private class OpenSubsonicApiException(
    code: Int?,
    serverMessage: String?,
) : IllegalStateException(
    buildString {
        append("OpenSubsonic API request failed")
        if (code != null) append(" (code $code)")
        serverMessage?.takeIf(String::isNotBlank)?.let { message -> append(": $message") }
    },
)

@Serializable
private data class SubsonicEnvelope(
    @SerialName("subsonic-response")
    val response: SubsonicResponse,
)

@Serializable
private data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicError? = null,
    val albumList2: SubsonicAlbumList? = null,
    val album: SubsonicAlbum? = null,
    val openSubsonicExtensions: List<SubsonicExtension>? = null,
)

@Serializable
private data class SubsonicError(
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
private data class SubsonicExtension(
    val name: String,
    val versions: List<Int> = emptyList(),
)

@Serializable
private data class SubsonicAlbumList(
    // Keep missing/null distinct from an explicit [] on the wire.
    val album: List<SubsonicAlbum>? = null,
)

@Serializable
private data class SubsonicArtist(
    val id: String? = null,
    val name: String? = null,
    val musicBrainzId: String? = null,
)

@Serializable
private data class SubsonicAlbum(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val artistId: String? = null,
    val artists: List<SubsonicArtist>? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val musicBrainzId: String? = null,
    val song: List<SubsonicSong>? = null,
)

@Serializable
private data class SubsonicSong(
    val id: String,
    val isDir: Boolean = false,
    val title: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val artistId: String? = null,
    val artists: List<SubsonicArtist>? = null,
    val track: Int? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val created: String? = null,
    val musicBrainzId: String? = null,
    val isrc: List<String>? = null,
    val userRating: JsonElement? = null,
    val starred: JsonElement? = null,
)
