package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
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
private const val MB_RELEASE_PREFIX = "https://musicbrainz.org/release/"
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
    val artworkUrl: String? = null,
)

internal data class ListenBrainzPublicPlaylistDetail(
    val playlist: ListenBrainzPublicPlaylist,
    val tracks: List<ListenBrainzPublicPlaylistTrack>,
)

internal data class ListenBrainzPublicPlaylistHttp(val status: Int, val body: String)

internal interface ListenBrainzPublicPlaylistTransport {
    suspend fun search(query: String): ListenBrainzPublicPlaylistHttp
    suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp
    suspend fun recordingArtwork(recordingMbids: List<String>): ListenBrainzPublicPlaylistHttp? = null
}

/** Anonymous GET only; deliberately no token, scrobble, legacy backend or write. */
internal class KtorListenBrainzPublicPlaylistTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            // The public trigram search is unusually slow upstream (often
            // >30 seconds). Fetch asynchronously; never block MusicBrainz UI.
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 8_000L
            socketTimeoutMillis = 90_000L
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

    override suspend fun recordingArtwork(recordingMbids: List<String>): ListenBrainzPublicPlaylistHttp =
        measureMetadataHttp(
            provider = "listenbrainz", endpoint = "public_playlist_recording_art",
            status = { r: ListenBrainzPublicPlaylistHttp -> r.status },
        ) {
            val response = client.get("https://api.listenbrainz.org/1/metadata/recording/") {
                parameter("recording_mbids", recordingMbids.joinToString(","))
                parameter("inc", "release")
                header(HttpHeaders.UserAgent, "BitChord/" + BuildConfig.VERSION_NAME)
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

/** JSPF annotations are plain text: decode common HTML entities, never render markup. */
private fun publicPlaylistPlainText(value: String): String =
    Regex("&(?:amp|quot|apos|lt|gt|nbsp|#39|#34);").replace(value) { entity ->
        when (entity.value) {
            "&amp;" -> "&"
            "&quot;", "&#34;" -> "\""
            "&apos;", "&#39;" -> "'"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&nbsp;" -> " "
            else -> entity.value
        }
    }

/** Build artwork URLs only from known CAA/MusicBrainz identities, not remote URLs. */
private fun verifiedPublicRecordingArtwork(releaseId: String?, caaId: String? = null): String? {
    val release = checkedPlaylistId(releaseId) ?: return null
    val base = "https://coverartarchive.org/release/$release"
    if (caaId == null) return "$base/front-250.jpg"
    val imageId = caaId.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return "$base/$imageId-250.jpg"
}

/** ListenBrainz recording metadata returns an object keyed by exact requested MBIDs. */
internal fun parseListenBrainzPublicRecordingArtwork(
    body: String, requestedRecordingMbids: List<String>,
): Map<String, String> {
    val requested = requestedRecordingMbids.mapNotNull(::checkedPlaylistId).take(25).toSet()
    val root = parsedJson(body, 300_000) ?: return emptyMap()
    return requested.mapNotNull { recording ->
        val node = root[recording] as? JsonObject ?: return@mapNotNull null
        val release = node["release"] as? JsonObject ?: return@mapNotNull null
        val cover = verifiedPublicRecordingArtwork(
            release.optionalText("caa_release_mbid"), release.optionalText("caa_id"),
        ) ?: return@mapNotNull null
        recording to cover
    }.toMap()
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

/** Fuzzy search can return 200 with no relevant match. Retry a bounded,
 * genre-specific phrase rather than mislabel unrelated playlists. */
internal fun publicPlaylistGenreQueries(tag: String): List<String> = listOf(
    publicPlaylistGenreQuery(tag),
    when (tag) {
        "metal" -> "heavy metal"
        "rock" -> "rock music"
        "pop" -> "pop music"
        "hip-hop" -> "hip hop music"
        "rnb" -> "rhythm and blues"
        "k-pop" -> "kpop"
        "electronic" -> "electronic music"
        "classical" -> "classical music"
        "jazz" -> "jazz music"
        "folk" -> "folk music"
        "ambient" -> "ambient music"
        "punk" -> "punk rock"
        else -> tag
    },
).distinct()

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
    // Home is a global public-playlist discovery shelf, not a search
    // for playlists literally containing the word "discover". ListenBrainz
    // already ordered the public search hits by query similarity. A genre,
    // unlike Home, still needs exact descriptive evidence.
    val aliases = genreTag?.let(::publicGenreAliases)
    return incoming.asSequence().mapNotNull { node ->
        val envelope = node as? JsonObject ?: return@mapNotNull null
        // ListenBrainz's live /1/playlist/search uses serialize_jspf(),
        // yielding {"playlist":{"identifier":"https://listenbrainz.org/playlist/{uuid}",
        // "title":..., "annotation":..., "extension":{...}}} for EACH row.
        // Previous code only recognized a flat playlist_mbid field, silently
        // rejecting 25/25 valid results per request in the physical smoke.
        // Keep a flat adapter for earlier API representations, but never
        // infer identity from arbitrary titles, track lists or fuzzy matches.
        val jspf = envelope["playlist"] as? JsonObject
        val data = jspf ?: envelope
        val extension = (data["extension"] as? JsonObject)
            ?.get("https://musicbrainz.org/doc/jspf#playlist") as? JsonObject
        // The endpoint is public-only; reject any explicit private claim
        // whether found in the JSPF extension or older flat schema.
        if ((extension?.get("public") as? JsonPrimitive)?.booleanOrNull == false ||
            (data["public"] as? JsonPrimitive)?.booleanOrNull == false ||
            (envelope["public"] as? JsonPrimitive)?.booleanOrNull == false
        ) return@mapNotNull null
        val mbid = if (jspf != null) {
            val id = data.optionalText("identifier") ?: return@mapNotNull null
            if (!id.startsWith(LB_PLAYLIST_PREFIX)) return@mapNotNull null
            checkedPlaylistId(id.removePrefix(LB_PLAYLIST_PREFIX).removeSuffix("/"))
        } else checkedPlaylistId(data.optionalText("playlist_mbid"))
        if (mbid == null) return@mapNotNull null
        val title = data.optionalText("title")?.takeIf { it.length <= 180 }
            ?.let(::publicPlaylistPlainText) ?: return@mapNotNull null
        if (title.any { it.isISOControl() }) return@mapNotNull null
        val creator = data.optionalText("creator")?.takeIf { it.length <= 100 }
            ?.let(::publicPlaylistPlainText) ?: "ListenBrainz"
        val description = (data.optionalText("annotation")
            ?: data.optionalText("description"))?.takeIf { it.length <= 1_000 }
            ?.let(::publicPlaylistPlainText)
        ListenBrainzPublicPlaylist(mbid, title, creator, description)
    }.filter { aliases == null || matchesPlaylistWords(it, aliases) }
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

        val extension = (obj["extension"] as? JsonObject)
            ?.get("https://musicbrainz.org/doc/jspf#track") as? JsonObject
        val metadata = extension?.get("additional_metadata") as? JsonObject
        val releaseId = extension?.optionalText("release_identifier")
            ?.takeIf { it.startsWith(MB_RELEASE_PREFIX) }
            ?.removePrefix(MB_RELEASE_PREFIX)?.removeSuffix("/")
            ?.let(::checkedPlaylistId)
        val caa = metadata?.optionalText("caa_release_mbid")?.let { release ->
            verifiedPublicRecordingArtwork(release, metadata?.optionalText("caa_id"))
        }
        val artwork = caa ?: releaseId?.let { verifiedPublicRecordingArtwork(it) }
        ListenBrainzPublicPlaylistTrack(
            mbid, obj.optionalText("title")?.takeIf { it.length <= 200 }
                ?.let(::publicPlaylistPlainText) ?: "Gravação sem título",
            obj.optionalText("creator")?.takeIf { it.length <= 200 }
                ?.let(::publicPlaylistPlainText) ?: "Crédito não informado",
            artworkUrl = artwork,
        )
    }
    return ListenBrainzPublicPlaylistDetail(selected, rows)
}

internal class ListenBrainzPublicPlaylistClient(
    private val transport: ListenBrainzPublicPlaylistTransport =
        KtorListenBrainzPublicPlaylistTransport(),
    private val limiter: ListenBrainzRateLimiter = ListenBrainzRequestBudget.limiter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Bounded cache; key includes exact verified Recording MBIDs, not titles alone. */
    private data class ArtistPlaylistCache(
        val atMs: Long, val playlists: List<ListenBrainzPublicPlaylist>, val healthy: Boolean,
    )
    private val artistCacheMutex = kotlinx.coroutines.sync.Mutex()
    private val artistCache = linkedMapOf<Pair<String, List<String>>, ArtistPlaylistCache>()

    suspend fun searchGenre(tag: String): List<ListenBrainzPublicPlaylist>? {
        if (tag !in setOf("pop", "rock", "hip-hop", "electronic", "k-pop", "rnb",
                "jazz", "metal", "classical", "folk", "ambient", "punk")) return null
        return search(publicPlaylistGenreQueries(tag), tag)
    }
    /** A typed query is separate from genre discovery and Home curation.
     * Title/annotation must actually include the requested words; fuzzy
     * ListenBrainz results are not enough to claim a user-requested match.
     */
    suspend fun searchPublicQuery(query: String): List<ListenBrainzPublicPlaylist> {
        val trimmed = query.trim()
        if (trimmed.length !in 2..80 || trimmed.any(Char::isISOControl))
            return emptyList()
        return search(listOf(trimmed), null, surface = "search").orEmpty()
            .filter { matchesPlaylistWords(it, listOf(trimmed)) }
    }

    suspend fun searchHome(): List<ListenBrainzPublicPlaylist>? =
        search(listOf("music", "mix", "playlist"), null)


    /**
     * Candidate titles are only search hints. The displayed playlists must
     * also contain a Recording MBID whose MusicBrainz artist credit the caller
     * has already verified for this exact artist. Limit to four remote details.
     * When ranking/credit evidence is absent, hide the section rather than
     * confusing a same-name playlist with an artist's own collection.
     */
    suspend fun searchArtistVerified(
        artistName: String,
        verifiedRecordingMbids: Set<String>,
    ): List<ListenBrainzPublicPlaylist> {
        val name = artistName.trim()
        val approved = verifiedRecordingMbids.mapNotNull(::checkedPlaylistId).toSet()
        if (name.length !in 3..100 || name.any(Char::isISOControl) || approved.isEmpty())
            return emptyList()
        val key = name.lowercase(Locale.ROOT) to approved.sorted()
        // The artist shelf is inside a LazyColumn. Its re-entry must reuse
        // verified membership instead of repeating a 15-90-second fuzzy search.
        // A different Recording MBID set is a different query and must recheck.
        artistCacheMutex.lock()
        try {
            val prior = artistCache[key]
            val now = nowMillis()
            if (prior != null && now >= prior.atMs &&
                now - prior.atMs < when {
                    !prior.healthy -> 90_000L
                    prior.playlists.isEmpty() -> 5L * 60L * 1_000L
                    else -> 30L * 60L * 1_000L
                }
            ) return prior.playlists
            val candidates = search(listOf(name), null, surface = "artist")
            val confirmed = mutableListOf<ListenBrainzPublicPlaylist>()
            var detailsHealthy = candidates != null
            for (candidate in candidates.orEmpty().take(4)) {
                val details = detail(candidate)
                if (details == null) detailsHealthy = false
                else if (details.tracks.any { it.recordingMbid in approved }) confirmed += candidate
                if (confirmed.size >= 3) break
            }
            if (artistCache.size >= 32) artistCache.remove(artistCache.keys.first())
            artistCache[key] = ArtistPlaylistCache(
                atMs = nowMillis(), playlists = confirmed, healthy = detailsHealthy,
            )
            return confirmed
        } finally {
            artistCacheMutex.unlock()
        }
    }

    private suspend fun search(
        queries: List<String>, tag: String?,
        surface: String = if (tag == null) "home" else "genre",
    ): List<ListenBrainzPublicPlaylist>? {
        var completed = false
        for ((attemptIndex, query) in queries.distinct().withIndex()) {
            val response = try { limiter.run { transport.search(query) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val matches = if (response?.status == 200)
                parseListenBrainzPublicPlaylistSearch(response.body, tag) else null
            // The physical smoke previously showed HTTP 200 and an empty
            // shelf, but offered no proof whether the response contained zero
            // candidates or our safety filters had rejected them. Record only
            // low-cardinality counts and surface, never public playlist titles,
            // user names, identifiers, query text or response bodies.
            val candidateCount = response?.body?.let { body ->
                (parsedJson(body, 300_000)?.get("playlists") as? JsonArray)?.size
            } ?: -1
            // Diagnostics are best-effort. Unit tests use a lightweight JVM
            // client without Android's SystemClock; telemetry must never turn
            // a valid upstream playlist into a failed browse request.
            runCatching {
                Diagnostics.event(
                    category = DiagnosticCategory.APP,
                    name = "public_playlist_discovery",
                    metadata = mapOf(
                        "surface" to surface,
                        "attempt" to (attemptIndex + 1).toString(),
                        "http" to when (response?.status) {
                            200 -> "ok"
                            null -> "unavailable"
                            else -> "error"
                        },
                        "candidates" to candidateCount.toString(),
                        "accepted" to (matches?.size ?: 0).toString(),
                        "parse" to if (matches == null) "invalid" else "ok",
                    ),
                )
            }
            if (matches == null) continue
            completed = true
            if (matches.isNotEmpty()) return matches
        }
        // A valid empty upstream response differs from an unavailable API.
        // Never manufacture playlist IDs when every verified query is empty.
        return if (completed) emptyList() else null
    }


    /** Up to 25 IDs per call, strictly scoped to real Recording MBIDs. */
    suspend fun artworkForRecordings(recordingMbids: List<String>): Map<String, String> {
        val validated = recordingMbids.mapNotNull(::checkedPlaylistId).distinct().take(25)
        if (validated.isEmpty()) return emptyMap()
        val response = try { limiter.run { transport.recordingArtwork(validated) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        return if (response?.status == 200)
            parseListenBrainzPublicRecordingArtwork(response.body, validated)
        else emptyMap()
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
