package com.music.bitchord.data.catalog.provider.opensubsonic

import com.music.bitchord.data.catalog.provider.ProviderAlbum
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URI
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * OpenSubsonic/Navidrome adapter. It enumerates the remote library using ID3-
 * oriented APIs (`getAlbumList2` + `getAlbum`) and emits provider-neutral rows.
 * No canonical IDs are created here.
 */
internal class OpenSubsonicProvider(
    private val config: OpenSubsonicAccountConfig,
    private val transport: OpenSubsonicTransport = KtorOpenSubsonicTransport(config.normalizedServerUrl),
    private val pageSize: Int = 500,
    private val saltFactory: () -> String = ::defaultOpenSubsonicSalt,
) : RemoteCatalogProvider {
    init {
        require(pageSize in 1..500) { "OpenSubsonic pageSize must be between 1 and 500" }
    }

    override val providerKey: String = "opensubsonic:${config.accountId}"

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

    override suspend fun librarySnapshot(): ProviderLibrarySnapshot {
        val albumIds = enumerateAlbumIds()
        val tracks = ArrayList<ProviderTrack>()

        for (albumId in albumIds) {
            val response = request(
                endpoint = "getAlbum",
                extraParameters = mapOf("id" to albumId),
            )
            val album = response.album
                ?: throw OpenSubsonicProtocolException("getAlbum returned no album")
            tracks += album.song.orEmpty()
                .asSequence()
                .filterNot(SubsonicSong::isDir)
                .map { song -> mapTrack(song, album) }
                .toList()
        }

        return ProviderLibrarySnapshot(
            tracks = tracks,
            complete = true,
        )
    }

    private suspend fun enumerateAlbumIds(): List<String> {
        val ids = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        var offset = 0

        while (true) {
            val response = request(
                endpoint = "getAlbumList2",
                extraParameters = mapOf(
                    "type" to "alphabeticalByArtist",
                    "size" to pageSize.toString(),
                    "offset" to offset.toString(),
                ),
            )
            val page = response.albumList2?.album.orEmpty()
            if (page.isEmpty()) break

            var newIds = 0
            for (album in page) {
                if (album.id.isNotBlank() && seen.add(album.id)) {
                    ids += album.id
                    newIds += 1
                }
            }
            if (page.size < pageSize) break
            if (newIds == 0) {
                throw OpenSubsonicProtocolException("Album pagination made no progress")
            }
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
    val album: List<SubsonicAlbum> = emptyList(),
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
)
