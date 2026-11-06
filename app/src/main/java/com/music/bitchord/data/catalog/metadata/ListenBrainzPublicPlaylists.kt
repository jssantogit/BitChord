package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

private val PUBLIC_PLAYLIST_MbID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private const val PUBLIC_PLAYLIST_PREFIX = "catalog:PUBLIC_PLAYLIST:"
private const val MB_RECORDING_PREFIX = "https://musicbrainz.org/recording/"
private const val LB_PLAYLIST_PREFIX = "https://listenbrainz.org/playlist/"

/** Public ListenBrainz playlist identity, never a Song or a local playlist. */
internal data class ListenBrainzPublicPlaylist(
    val mbid: String,
    val title: String,
    val creator: String,
    val description: String?,
) {
    val browseId: String get() = PUBLIC_PLAYLIST_PREFIX + mbid
    // Official public endpoint for artwork derived from this exact playlist.
    val artworkUrl: String get() =
        "https://api.listenbrainz.org/1/art/playlist/$mbid/opengraph/"
}

internal fun publicPlaylistMbid(browseId: String?): String? =
    browseId?.takeIf { it.startsWith(PUBLIC_PLAYLIST_PREFIX) }
        ?.removePrefix(PUBLIC_PLAYLIST_PREFIX)?.lowercase(Locale.ROOT)
        ?.takeIf(PUBLIC_PLAYLIST_MbID::matches)

internal data class ListenBrainzPublicPlaylistTrack(
    val recordingMbid: String?,
    val title: String,
    val artist: String,
)

internal data class ListenBrainzPublicPlaylistDetail(
    val playlist: ListenBrainzPublicPlaylist,
    val tracks: List<ListenBrainzPublicPlaylistTrack>,
)

internal data class ListenBrainzPublicPlaylistHttp(val status: Int, val body: String)

internal interface ListenBrainzPublicPlaylistTransport {
    suspend fun search(query: String): ListenBrainzPublicPlaylistHttp
    suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp
}

/** Anonymous GET only; deliberately no token, scrobble, legacy backend or write. */
internal class KtorListenBrainzPublicPlaylistTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 8_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : ListenBrainzPublicPlaylistTransport {
    override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp =
        measureMetadataHttp(
            provider = "listenbrainz", endpoint = "public_playlist_search",
            status = { r: ListenBrainzPublicPlaylistHttp -> r.status },
        ) {
            val response = client.get("https://api.listenbrainz.org/1/playlist/search") {
                parameter("query", query)
                parameter("count", 25)
                parameter("offset", 0)
                header(HttpHeaders.UserAgent, "BitChord/${BuildConfig.VERSION_NAME}")
            }
            ListenBrainzPublicPlaylistHttp(response.status.value,
                if (response.status.value == 200) response.bodyAsText() else "")
        }

    override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp =
        measureMetadataHttp(
            provider = "listenbrainz", endpoint = "public_playlist_detail",
            status = { r: ListenBrainzPublicPlaylistHttp -> r.status },
        ) {
            val response = client.get("https://api.listenbrainz.org/1/playlist/$mbid") {
                header(HttpHeaders.UserAgent, "BitChord/${BuildConfig.VERSION_NAME}")
            }
            ListenBrainzPublicPlaylistHttp(response.status.value,
                if (response.status.value == 200) response.bodyAsText() else "")
        }
}

private fun JsonObject.optionalText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun checkedPlaylistId(raw: String?): String? =
    raw?.lowercase(Locale.ROOT)?.takeIf(PUBLIC_PLAYLIST_MbID::matches)

private fun parsedJson(raw: String, maxLength: Int): JsonObject? =
    raw.takeIf { it.length <= maxLength }?.let {
        try { Json.parseToJsonElement(it) as? JsonObject }
        catch (_: Exception) { null }
    }

/** Fuzzy search itself never proves a genre: require a whole term in public text. */
private fun matchesPlaylistWords(
    playlist: ListenBrainzPublicPlaylist, aliases: List<String>,
): Boolean {
    val words = "${playlist.title} ${playlist.description.orEmpty()}".lowercase(Locale.ROOT)
    return aliases.any { alias ->
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(alias.lowercase(Locale.ROOT)) +
            "(?![\\p{L}\\p{N}])").containsMatchIn(words)
    }
}

internal fun publicPlaylistGenreQuery(tag: String): String = when (tag) {
    "hip-hop" -> "hip hop"
    "rnb" -> "r&b"
    else -> tag
}

private fun publicGenreAliases(tag: String): List<String> = when (tag) {
    "hip-hop" -> listOf("hip hop", "hip-hop", "hiphop")
    "rnb" -> listOf("r&b", "rnb", "rhythm and blues")
    "k-pop" -> listOf("k-pop", "kpop", "korean pop")
    "electronic" -> listOf("electronic", "electronica")
    "classical" -> listOf("classical", "classica")
    else -> listOf(tag)
}

/** Only validated public playlist identities appear in genre discovery. */
internal fun parseListenBrainzPublicPlaylistSearch(
    body: String, genreTag: String? = null,
): List<ListenBrainzPublicPlaylist>? {
    val root = parsedJson(body, 300_000) ?: return null
    val incoming = root["playlists"] as? JsonArray ?: return null
    if (incoming.size > 100) return null
    val aliases = genreTag?.let(::publicGenreAliases)
        ?: listOf("discover", "discovery", "descoberta")
    return incoming.asSequence().mapNotNull { node ->
        val data = node as? JsonObject ?: return@mapNotNull null
        if ((data["public"] as? JsonPrimitive)?.booleanOrNull != true)
            return@mapNotNull null
        val mbid = checkedPlaylistId(data.optionalText("playlist_mbid"))
            ?: return@mapNotNull null
        val title = data.optionalText("title")?.takeIf { it.length <= 180 }
            ?: return@mapNotNull null
        if (title.any { it.isISOControl() }) return@mapNotNull null
        val creator = data.optionalText("creator")?.takeIf { it.length <= 100 }
            ?: "ListenBrainz"
        val description = data.optionalText("description")?.takeIf { it.length <= 1_000 }
        ListenBrainzPublicPlaylist(mbid, title, creator, description)
    }.filter { matchesPlaylistWords(it, aliases) }
        .distinctBy { it.mbid }.take(12).toList()
}

/** Read-only JSPF; recording IDs are validated links, not inferred by title. */
internal fun parseListenBrainzPublicPlaylistDetail(
    body: String, selected: ListenBrainzPublicPlaylist,
): ListenBrainzPublicPlaylistDetail? {
    if (checkedPlaylistId(selected.mbid) == null) return null
    val root = parsedJson(body, 1_200_000) ?: return null
    val playlist = root["playlist"] as? JsonObject ?: return null
    val ownIdentifier = playlist.optionalText("identifier")
    if (ownIdentifier != null && ownIdentifier != LB_PLAYLIST_PREFIX + selected.mbid &&
        ownIdentifier != LB_PLAYLIST_PREFIX + selected.mbid + "/"
    ) return null
    val ext = (playlist["extension"] as? JsonObject)
        ?.get("https://musicbrainz.org/doc/jspf#playlist") as? JsonObject
    if ((ext?.get("public") as? JsonPrimitive)?.booleanOrNull == false) return null
    val tracks = playlist["track"] as? JsonArray ?: return null
    if (tracks.size > 1_000) return null
    val rows = tracks.take(150).mapNotNull { node ->
        val obj = node as? JsonObject ?: return@mapNotNull null
        val identifiers = when (val id = obj["identifier"]) {
            is JsonPrimitive -> listOfNotNull(id.contentOrNull)
            is JsonArray -> id.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        val mbid = identifiers.mapNotNull { raw ->
            raw.takeIf { it.startsWith(MB_RECORDING_PREFIX) }
                ?.removePrefix(MB_RECORDING_PREFIX)?.removeSuffix("/")
                ?.let(::checkedPlaylistId)
        }.firstOrNull()
        ListenBrainzPublicPlaylistTrack(
            mbid, obj.optionalText("title")?.takeIf { it.length <= 200 }
                ?: "Gravação sem título",
            obj.optionalText("creator")?.takeIf { it.length <= 200 }
                ?: "Crédito não informado",
        )
    }
    return ListenBrainzPublicPlaylistDetail(selected, rows)
}

internal class ListenBrainzPublicPlaylistClient(
    private val transport: ListenBrainzPublicPlaylistTransport =
        KtorListenBrainzPublicPlaylistTransport(),
    private val limiter: ListenBrainzRateLimiter = ListenBrainzRequestBudget.limiter,
) {
    suspend fun searchGenre(tag: String): List<ListenBrainzPublicPlaylist>? {
        if (tag !in setOf("pop", "rock", "hip-hop", "electronic", "k-pop", "rnb",
                "jazz", "metal", "classical", "folk", "ambient", "punk")) return null
        return search(publicPlaylistGenreQuery(tag), tag)
    }
    suspend fun searchHome(): List<ListenBrainzPublicPlaylist>? = search("discover", null)

    private suspend fun search(query: String, tag: String?): List<ListenBrainzPublicPlaylist>? {
        val response = try { limiter.run { transport.search(query) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        if (response.status != 200) return null
        return parseListenBrainzPublicPlaylistSearch(response.body, tag)
    }

    suspend fun detail(selected: ListenBrainzPublicPlaylist): ListenBrainzPublicPlaylistDetail? {
        if (checkedPlaylistId(selected.mbid) == null) return null
        val response = try { limiter.run { transport.detail(selected.mbid) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        if (response.status != 200) return null
        return parseListenBrainzPublicPlaylistDetail(response.body, selected)
    }
}
