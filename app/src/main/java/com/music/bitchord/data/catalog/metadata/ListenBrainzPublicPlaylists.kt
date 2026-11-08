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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    /** Text search needs a larger trigram candidate window than discovery.
     * The default preserves existing injected transport mocks. */
    suspend fun searchWide(query: String): ListenBrainzPublicPlaylistHttp = search(query)
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
        getPublicPlaylists(query, count = 25)

    override suspend fun searchWide(query: String): ListenBrainzPublicPlaylistHttp =
        getPublicPlaylists(query, count = 75)

    private suspend fun getPublicPlaylists(
        query: String, count: Int,
    ): ListenBrainzPublicPlaylistHttp =
        measureMetadataHttp(
            provider = "listenbrainz", endpoint = "public_playlist_search",
            status = { r: ListenBrainzPublicPlaylistHttp -> r.status },
        ) {
            val response = client.get("https://api.listenbrainz.org/1/playlist/search") {
                parameter("query", query)
                parameter("count", count)
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

/** Editorial shelf quality belongs to playlist metadata, not the UI.
 * Search and detail still allow legitimate one-word titles. */
internal fun isDescriptivePublicPlaylistTitle(title: String): Boolean {
    val clean = title.trim()
    return clean.length >= 5 && clean.split(Regex("""\s+"""))
        .count { it.any(Char::isLetterOrDigit) } >= 2
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
        .distinctBy { it.mbid }.take(24).toList()
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
    // Contains only active lookups; keys are removed even on cancellation.
    private val artistInFlight = mutableMapOf<Pair<String, List<String>>,
        CompletableDeferred<List<ListenBrainzPublicPlaylist>>>()
    // Reopening an already inspected public playlist must not pay another
    // upstream HTTP + rate-limit slot. Only successful exact-MBID JSPF data
    // is cached; transient failures are always retryable.
    private data class CachedDetail(val atMs: Long, val detail: ListenBrainzPublicPlaylistDetail)
    private val detailCacheMutex = kotlinx.coroutines.sync.Mutex()
    private val detailCache = linkedMapOf<String, CachedDetail>()
    private val detailInFlight = mutableMapOf<String,
        CompletableDeferred<ListenBrainzPublicPlaylistDetail?>>()
    private data class TextSearchCache(
        val atMs: Long,
        val playlists: List<ListenBrainzPublicPlaylist>,
    )
    private val textSearchMutex = kotlinx.coroutines.sync.Mutex()
    private val textSearchCache = linkedMapOf<String, TextSearchCache>()
    // Concurrent requests must reuse the same upstream rate-limit slot.
    private val textSearchInFlight = mutableMapOf<String,
        CompletableDeferred<List<ListenBrainzPublicPlaylist>>>()

    // The Home and genre shelves can be reopened repeatedly while an upstream
    // public trigram search takes tens of seconds. Cache only completed,
    // verified discovery, and share active requests by surface + genre.
    private data class CachedDiscovery(
        val atMs: Long, val playlists: List<ListenBrainzPublicPlaylist>,
    )
    private val discoveryMutex = kotlinx.coroutines.sync.Mutex()
    private val discoveryCache = linkedMapOf<String, CachedDiscovery>()
    private val discoveryInFlight = mutableMapOf<String,
        CompletableDeferred<List<ListenBrainzPublicPlaylist>?>>()
    // Recording artwork enrichment is metadata-only; keep a small positive
    // cache by the exact batch of MusicBrainz IDs, not by titles or artist.
    private data class CachedRecordingArtwork(
        val atMs: Long, val covers: Map<String, String>,
    )
    private val recordingArtworkMutex = kotlinx.coroutines.sync.Mutex()
    private val recordingArtworkCache = linkedMapOf<List<String>, CachedRecordingArtwork>()
    private val recordingArtworkInFlight = mutableMapOf<List<String>,
        CompletableDeferred<Map<String, String>>>()


    private suspend fun cachedDiscovery(
        key: String, load: suspend () -> List<ListenBrainzPublicPlaylist>?,
    ): List<ListenBrainzPublicPlaylist>? {
        var cached: List<ListenBrainzPublicPlaylist>? = null
        var pending: CompletableDeferred<List<ListenBrainzPublicPlaylist>?>? = null
        var owner = false
        discoveryMutex.lock()
        try {
            val previous = discoveryCache[key]
            if (previous != null) {
                val age = nowMillis() - previous.atMs
                val ttl = if (previous.playlists.isEmpty()) 60_000L else 5 * 60_000L
                if (age >= 0 && age < ttl) cached = previous.playlists
                else discoveryCache.remove(key)
            }
            if (cached == null) {
                val running = discoveryInFlight[key]
                if (running != null) pending = running
                else {
                    pending = CompletableDeferred()
                    discoveryInFlight[key] = pending!!
                    owner = true
                }
            }
        } finally {
            discoveryMutex.unlock()
        }
        cached?.let { return it }
        val request = pending ?: return null
        if (!owner) return request.await()
        try {
            val result = load()
            if (result != null) {
                discoveryMutex.lock()
                try {
                    discoveryCache.remove(key)
                    discoveryCache[key] = CachedDiscovery(nowMillis(), result)
                    while (discoveryCache.size > 16)
                        discoveryCache.remove(discoveryCache.keys.first())
                } finally {
                    discoveryMutex.unlock()
                }
            }
            request.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            request.complete(null)
            return null
        } finally {
            withContext(NonCancellable) {
                discoveryMutex.lock()
                try {
                    if (discoveryInFlight[key] === request) discoveryInFlight.remove(key)
                } finally {
                    discoveryMutex.unlock()
                }
            }
        }
    }

    suspend fun searchGenre(tag: String): List<ListenBrainzPublicPlaylist>? {
        if (tag !in setOf("pop", "rock", "hip-hop", "electronic", "k-pop", "rnb",
                "jazz", "metal", "classical", "folk", "ambient", "punk")) return null
        return cachedDiscovery("genre:$tag") {
            search(publicPlaylistGenreQueries(tag), tag)
        }
    }
    /** A typed query is separate from genre discovery and Home curation.
     * Title/annotation must actually include the requested words; fuzzy
     * ListenBrainz results are not enough to claim a user-requested match.
     */
    suspend fun searchPublicQuery(query: String): List<ListenBrainzPublicPlaylist> {
        val trimmed = query.trim()
        if (trimmed.length !in 2..80 || trimmed.any(Char::isISOControl))
            return emptyList()
        val key = trimmed.lowercase(Locale.ROOT)
        var cached: List<ListenBrainzPublicPlaylist>? = null
        var pending: CompletableDeferred<List<ListenBrainzPublicPlaylist>>? = null
        var owner = false
        textSearchMutex.lock()
        try {
            val previous = textSearchCache[key]
            if (previous != null) {
                val age = nowMillis() - previous.atMs
                val ttl = if (previous.playlists.isEmpty()) 2 * 60_000L else 30 * 60_000L
                if (age >= 0 && age < ttl) cached = previous.playlists
                else textSearchCache.remove(key)
            }
            if (cached == null) {
                val running = textSearchInFlight[key]
                if (running != null) pending = running
                else {
                    pending = CompletableDeferred()
                    textSearchInFlight[key] = pending!!
                    owner = true
                }
            }
        } finally {
            textSearchMutex.unlock()
        }
        cached?.let { return it }
        val request = pending ?: return emptyList()
        // Join only the exact normalized query. Other queries can still
        // observe cache hits while the slow public trigram endpoint runs.
        if (!owner) return request.await()
        try {
            // Filter by evidence before capping; a remote failure stays retryable.
            val verified = search(listOf(trimmed), trimmed, surface = "search")
            if (verified != null) {
                textSearchMutex.lock()
                try {
                    textSearchCache.remove(key)
                    textSearchCache[key] = TextSearchCache(nowMillis(), verified)
                    while (textSearchCache.size > 24)
                        textSearchCache.remove(textSearchCache.keys.first())
                } finally {
                    textSearchMutex.unlock()
                }
            }
            val result = verified.orEmpty()
            request.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            request.complete(emptyList())
            return emptyList()
        } finally {
            withContext(NonCancellable) {
                textSearchMutex.lock()
                try {
                    if (textSearchInFlight[key] === request) textSearchInFlight.remove(key)
                } finally {
                    textSearchMutex.unlock()
                }
            }
        }
    }

    suspend fun searchHome(): List<ListenBrainzPublicPlaylist>? =
        cachedDiscovery("home") {
            // Avoid repeated 15-90s upstream searches on Home re-entry.
            // Existing preference for descriptive candidates is unchanged.
            search(listOf("essentials", "favorites", "music"), null)
        }


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

        // Only the exact-key cache and in-flight bookkeeping hold this lock.
        // Public trigram searches can take 15-90s: holding a global cache
        // mutex across them blocks even an unrelated artist's cache HIT.
        var cached: List<ListenBrainzPublicPlaylist>? = null
        var pending: CompletableDeferred<List<ListenBrainzPublicPlaylist>>? = null
        var owner = false
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
            ) {
                cached = prior.playlists
            } else {
                val running = artistInFlight[key]
                if (running != null) {
                    pending = running
                } else {
                    pending = CompletableDeferred()
                    artistInFlight[key] = pending!!
                    owner = true
                }
            }
        } finally {
            artistCacheMutex.unlock()
        }
        cached?.let { return it }
        val request = pending ?: return emptyList()
        // Multiple screens requesting the SAME artist and recording evidence
        // share one network request; different artists can read cached facts
        // while the upstream limiter coordinates their remote requests.
        if (!owner) return request.await()
        try {
            val candidates = search(listOf(name), null, surface = "artist")
            val confirmed = mutableListOf<ListenBrainzPublicPlaylist>()
            var detailsHealthy = candidates != null
            for (candidate in candidates.orEmpty().take(4)) {
                val details = detail(candidate)
                if (details == null) detailsHealthy = false
                else if (details.tracks.any { it.recordingMbid in approved }) confirmed += candidate
                if (confirmed.size >= 3) break
            }
            artistCacheMutex.lock()
            try {
                if (artistCache.size >= 32) artistCache.remove(artistCache.keys.first())
                artistCache[key] = ArtistPlaylistCache(
                    atMs = nowMillis(), playlists = confirmed, healthy = detailsHealthy,
                )
            } finally {
                artistCacheMutex.unlock()
            }
            request.complete(confirmed)
            return confirmed
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            // An unexpected parse/cache exception must not turn into a
            // long-lived negative cache or permanently strand other callers.
            request.complete(emptyList())
            return emptyList()
        } finally {
            // Cancellation cannot leave the in-flight map poisoned.
            withContext(NonCancellable) {
                artistCacheMutex.lock()
                try {
                    if (artistInFlight[key] === request) artistInFlight.remove(key)
                } finally {
                    artistCacheMutex.unlock()
                }
            }
        }
    }

    private suspend fun search(
        queries: List<String>, tag: String?,
        surface: String = if (tag == null) "home" else "genre",
    ): List<ListenBrainzPublicPlaylist>? {
        var completed = false
        for ((attemptIndex, query) in queries.distinct().withIndex()) {
            val response = try {
                limiter.run {
                    if (surface == "search") transport.searchWide(query)
                    else transport.search(query)
                }
            }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val parsed = if (response?.status == 200)
                parseListenBrainzPublicPlaylistSearch(response.body, tag) else null
            // Upstream public search ranks by trigram similarity, so "music"
            // frequently yields only one-word placeholders. Skip that batch
            // and try a bounded descriptive query before declaring Home empty.
            val matches = if (surface == "home")
                parsed?.filter { isDescriptivePublicPlaylistTitle(it.title) }
            else parsed
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


    /** Up to 25 verified Recording MBIDs per request. Many playlists reuse
     * the same songs and may open concurrently: avoid paying repeated
     * ListenBrainz rate-limit slots for the exact same metadata batch. */
    suspend fun artworkForRecordings(recordingMbids: List<String>): Map<String, String> {
        val key = recordingMbids.mapNotNull(::checkedPlaylistId)
            .distinct().take(25).sorted()
        if (key.isEmpty()) return emptyMap()
        var cached: Map<String, String>? = null
        var pending: CompletableDeferred<Map<String, String>>? = null
        var owner = false
        recordingArtworkMutex.lock()
        try {
            val previous = recordingArtworkCache[key]
            if (previous != null) {
                val age = nowMillis() - previous.atMs
                val ttl = if (previous.covers.isEmpty()) 60_000L else 10 * 60_000L
                if (age >= 0 && age < ttl) cached = previous.covers
                else recordingArtworkCache.remove(key)
            }
            if (cached == null) {
                val running = recordingArtworkInFlight[key]
                if (running != null) pending = running
                else {
                    pending = CompletableDeferred()
                    recordingArtworkInFlight[key] = pending!!
                    owner = true
                }
            }
        } finally {
            recordingArtworkMutex.unlock()
        }
        cached?.let { return it }
        val request = pending ?: return emptyMap()
        if (!owner) return request.await()
        try {
            val response = try { limiter.run { transport.recordingArtwork(key) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            // A timeout/503 is NOT negative cover evidence. It must be retried
            // on the next page opening instead of cached as permanent absence.
            val verified = if (response?.status == 200)
                parseListenBrainzPublicRecordingArtwork(response.body, key) else null
            if (verified != null) {
                recordingArtworkMutex.lock()
                try {
                    recordingArtworkCache.remove(key)
                    recordingArtworkCache[key] = CachedRecordingArtwork(nowMillis(), verified)
                    while (recordingArtworkCache.size > 32)
                        recordingArtworkCache.remove(recordingArtworkCache.keys.first())
                } finally {
                    recordingArtworkMutex.unlock()
                }
            }
            val result = verified.orEmpty()
            request.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            request.complete(emptyMap())
            return emptyMap()
        } finally {
            withContext(NonCancellable) {
                recordingArtworkMutex.lock()
                try {
                    if (recordingArtworkInFlight[key] === request)
                        recordingArtworkInFlight.remove(key)
                } finally {
                    recordingArtworkMutex.unlock()
                }
            }
        }
    }

    suspend fun detail(selected: ListenBrainzPublicPlaylist): ListenBrainzPublicPlaylistDetail? {
        val id = checkedPlaylistId(selected.mbid) ?: return null
        var cached: ListenBrainzPublicPlaylistDetail? = null
        var pending: CompletableDeferred<ListenBrainzPublicPlaylistDetail?>? = null
        var owner = false
        detailCacheMutex.lock()
        try {
            val previous = detailCache[id]
            val now = nowMillis()
            if (previous != null && now >= previous.atMs &&
                now - previous.atMs < 10 * 60_000L
            ) {
                cached = previous.detail
            } else {
                detailCache.remove(id)
                val running = detailInFlight[id]
                if (running != null) pending = running
                else {
                    pending = CompletableDeferred()
                    detailInFlight[id] = pending!!
                    owner = true
                }
            }
        } finally {
            detailCacheMutex.unlock()
        }
        cached?.let { return it }
        val request = pending ?: return null
        if (!owner) return request.await()
        try {
            val response = try { limiter.run { transport.detail(id) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val parsed = if (response?.status == 200)
                parseListenBrainzPublicPlaylistDetail(response.body, selected) else null
            // Only a verified JSPF becomes a durable cache entry. Failures
            // unblock all waiters, then permit a fresh retry on next access.
            if (parsed != null) {
                detailCacheMutex.lock()
                try {
                    detailCache.remove(id)
                    detailCache[id] = CachedDetail(nowMillis(), parsed)
                    while (detailCache.size > 24)
                        detailCache.remove(detailCache.keys.first())
                } finally {
                    detailCacheMutex.unlock()
                }
            }
            request.complete(parsed)
            return parsed
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            request.complete(null)
            return null
        } finally {
            withContext(NonCancellable) {
                detailCacheMutex.lock()
                try {
                    if (detailInFlight[id] === request) detailInFlight.remove(id)
                } finally {
                    detailCacheMutex.unlock()
                }
            }
        }
    }
}
