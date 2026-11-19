package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/** Storefront-scoped catalog resources, not MBIDs, personal media, or streams. */
internal enum class AppleMusicWebKind(val apiType: String, val identityType: String) {
    SONG("songs", "song"),
    ALBUM("albums", "album"),
    ARTIST("artists", "artist"),
    PLAYLIST("playlists", "playlist"),
    MUSIC_VIDEO("music-videos", "music_video"),
    STATION("stations", "station"),
    CURATOR("curators", "curator"),
    LABEL("record-labels", "label"),
}

internal data class AppleMusicWebItem(
    val id: String,
    val kind: AppleMusicWebKind,
    val storefront: String,
    val title: String,
    val artistName: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    val storeUrl: String? = null,
    val isrc: String? = null,
    val upc: String? = null,
) {
    val scopedId: String get() = "apple_music_web:${storefront}:${kind.identityType}:$id"
    val playable: Boolean get() = false
    val musicBrainzId: String? get() = null
}

internal data class AppleMusicWebPage(
    val items: List<AppleMusicWebItem>, val page: Int, val limit: Int, val hasMore: Boolean,
)

internal enum class AppleMusicWebArtistShelf(val endpoint: String) {
    ALL_ALBUMS("albums"),
    STUDIO_ALBUMS("view/full-albums"),
    SINGLES_AND_EPS("view/singles"),
    LIVE_ALBUMS("view/live-albums"),
    COMPILATIONS("view/compilation-albums"),
    APPEARS_ON("view/appears-on-albums"),
    ESSENTIAL_ALBUMS("view/featured-albums"),
    TOP_SONGS("view/top-songs"),
    MUSIC_VIDEOS("view/music-videos"),
    ARTIST_PLAYLISTS("view/playlists"),
    FEATURED_PLAYLISTS("view/featured-playlists"),
    SIMILAR_ARTISTS("view/similar-artists"),
}

internal data class AppleMusicWebArtist(
    val identity: AppleMusicWebItem,
    val topSongs: List<AppleMusicWebItem>,
    val albums: List<AppleMusicWebItem>,
    val singlesAndEps: List<AppleMusicWebItem>,
    val similarArtists: List<AppleMusicWebItem>,
    val featuredAlbums: List<AppleMusicWebItem> = emptyList(),
    val artistPlaylists: List<AppleMusicWebItem> = emptyList(),
)

internal data class AppleMusicWebAlbum(
    val identity: AppleMusicWebItem,
    val tracks: List<AppleMusicWebItem>,
    val artists: List<AppleMusicWebItem>,
    val possiblyTruncated: Boolean,
)

internal class AppleMusicWebProtocolException(message: String) : IllegalStateException(message)

internal fun interface AppleMusicWebTransport {
    suspend fun get(capability: ProviderCapability, path: String, params: Map<String, String>): String
}

internal data class AppleMusicWebToken(val value: String, val expiresAtEpochSeconds: Long)

/**
 * Parse the public web bundle's AMPWebPlay JWT by claims; this is NOT
 * signature validation or a claim that the API is guaranteed for third parties.
 */
internal object AppleMusicWebTokenParser {
    private val candidates = Regex("""eyJ[A-Za-z0-9_-]{12,}\.eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{16,}""")
    fun pick(bundle: String, nowEpochSeconds: Long): AppleMusicWebToken? =
        candidates.findAll(bundle).mapNotNull { match ->
            val jwt = match.value
            val payload = runCatching {
                Json.parseToJsonElement(String(
                    Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8,
                )) as? JsonObject
            }.getOrNull() ?: return@mapNotNull null
            val issuer = (payload["iss"] as? JsonPrimitive)?.contentOrNull
            val expiry = (payload["exp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            if (issuer == "AMPWebPlay" && expiry > nowEpochSeconds + 86400L)
                AppleMusicWebToken(jwt, expiry) else null
        }.firstOrNull()
}

/** Native, no-python catalog client. Upstream network is a separate gated boundary. */
internal class AppleMusicWebCatalog(private val transport: AppleMusicWebTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun storefront(value: String): String =
        value.trim().lowercase(Locale.ROOT).also {
            require(Regex("[a-z]{2}").matches(it)) { "Invalid storefront" }
        }

    private fun resourceId(value: String, kind: AppleMusicWebKind): String {
        val valid = when (kind) {
            AppleMusicWebKind.PLAYLIST -> Regex("pl\\.[A-Za-z0-9-]{6,}").matches(value)
            AppleMusicWebKind.STATION -> Regex("ra\\.[A-Za-z0-9.-]{3,}").matches(value)
            else -> Regex("[0-9]{1,12}").matches(value)
        }
        require(valid) { "Invalid Apple ID" }
        return value
    }

    private fun parse(body: String): JsonObject {
        if (body.length > 2_000_000) throw AppleMusicWebProtocolException("Oversized response")
        return runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw AppleMusicWebProtocolException("Invalid catalog JSON")
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.objects(name: String): List<JsonObject> {
        val el = this[name] ?: return emptyList()
        if (el.toString() == "null") return emptyList()
        val values = el as? JsonArray ?: throw AppleMusicWebProtocolException("Invalid array")
        return values.map {
            it as? JsonObject ?: throw AppleMusicWebProtocolException("Invalid catalog item")
        }
    }

    private fun convert(raw: JsonObject, sf: String): AppleMusicWebItem? {
        val kind = AppleMusicWebKind.entries.firstOrNull { it.apiType == raw.str("type") }
            ?: return null
        val id = raw.str("id") ?: throw AppleMusicWebProtocolException("Missing ID")
        val attr = raw.obj("attributes")
            ?: throw AppleMusicWebProtocolException("Missing attributes")
        val name = attr.str("name") ?: attr.str("title")
            ?: throw AppleMusicWebProtocolException("Missing title")
        return AppleMusicWebItem(
            id = id, kind = kind, storefront = sf, title = name,
            artistName = attr.str("artistName"), artistId = attr.str("artistId"),
            albumId = attr.str("albumId"),
            artworkUrl = attr.obj("artwork")?.str("url")?.takeIf { it.startsWith("https://") },
            storeUrl = attr.str("url")?.takeIf { it.startsWith("https://music.apple.com/") },
            isrc = attr.str("isrc"), upc = attr.str("upc"),
        )
    }

    private fun items(block: JsonObject?, sf: String): List<AppleMusicWebItem> =
        block?.objects("data").orEmpty().mapNotNull { convert(it, sf) }

    /** Only Apple's same-route, same-storefront next links are allowed. */
    private fun follow(cursor: String, path: String,
                       term: String? = null, kind: AppleMusicWebKind? = null): Map<String, String> {
        if (cursor.length > 4096) throw AppleMusicWebProtocolException("Oversized cursor")
        val uri = runCatching { URI(cursor) }.getOrNull()
            ?: throw AppleMusicWebProtocolException("Malformed cursor")
        if (uri.path != path || (uri.scheme != null && uri.scheme != "https") ||
            (uri.host != null && uri.host != "amp-api.music.apple.com") ||
            (uri.isAbsolute && uri.host == null) || uri.userInfo != null ||
            uri.fragment != null || (uri.port != -1 && uri.port != 443)
        ) throw AppleMusicWebProtocolException("Untrusted Apple cursor")
        val pairs = linkedMapOf<String, String>()
        for (part in (uri.rawQuery ?: "").split('&')) {
            if (part.isBlank()) continue
            val tokens = part.split('=', limit = 2)
            val key = URLDecoder.decode(tokens[0], "UTF-8")
            val value = URLDecoder.decode(tokens.getOrElse(1) { "" }, "UTF-8")
            if (key in pairs) throw AppleMusicWebProtocolException("Duplicate cursor field")
            pairs[key] = value
        }
        if (pairs.isEmpty() || (term != null && pairs["term"]?.let { it != term } == true) ||
            (kind != null && pairs["types"]?.let { it != kind.apiType } == true)
        ) throw AppleMusicWebProtocolException("Changed cursor query")
        return pairs
    }

    /** Next offsets are opaque: NEVER calculate page*limit for Apple's web search. */
    suspend fun search(
        kind: AppleMusicWebKind, query: String, country: String,
        page: Int = 1, limit: Int = 25,
    ): AppleMusicWebPage {
        require(page in 1..20 && limit in 1..100)
        require(query.isNotBlank() && query.length <= 120 && query.none(Char::isISOControl))
        val sf = storefront(country)
        val path = "/v1/catalog/$sf/search"
        val base = mapOf("term" to query, "types" to kind.apiType, "limit" to limit.toString())
        var block = parse(transport.get(ProviderCapability.CATALOG_SEARCH, path, base))
            .obj("results")?.obj(kind.apiType)
            ?: throw AppleMusicWebProtocolException("Missing search results")
        val seen = mutableSetOf<String>()
        for (index in 1 until page) {
            val cursor = block.str("next") ?: return AppleMusicWebPage(
                emptyList(), page, limit, false,
            )
            if (!seen.add(cursor)) throw AppleMusicWebProtocolException("Cyclic cursor")
            val args = follow(cursor, path, query, kind) + base
            block = parse(transport.get(ProviderCapability.CATALOG_SEARCH, path, args))
                .obj("results")?.obj(kind.apiType)
                ?: throw AppleMusicWebProtocolException("Missing search next page")
        }
        return AppleMusicWebPage(items(block, sf), page, limit, block.str("next") != null)
    }

    private suspend fun detail(
        sf: String, kind: AppleMusicWebKind, id: String, args: Map<String, String>,
    ): JsonObject {
        val path = "/v1/catalog/$sf/${kind.apiType}/$id"
        val results = parse(transport.get(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, path, args,
        )).objects("data")
        val resource = results.singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing or ambiguous Apple detail")
        if (resource.str("id") != id || resource.str("type") != kind.apiType)
            throw AppleMusicWebProtocolException("Wrong Apple resource identity")
        return resource
    }

    suspend fun artist(id: String, country: String): AppleMusicWebArtist {
        val sf = storefront(country)
        val artistId = resourceId(id, AppleMusicWebKind.ARTIST)
        val raw = detail(sf, AppleMusicWebKind.ARTIST, artistId, mapOf(
            "views" to listOf("latest-release", "top-songs", "featured-albums",
                "full-albums", "singles", "similar-artists", "playlists").joinToString(","),
            "extend" to "artistBio,editorialArtwork,plainEditorialNotes",
            "include" to "genres,station",
        ))
        val identity = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid artist")
        val views = raw.obj("views")
        fun shelf(name: String) = items(views?.obj(name), sf)
        return AppleMusicWebArtist(
            identity, shelf("top-songs"), shelf("full-albums"), shelf("singles"),
            shelf("similar-artists"), shelf("featured-albums"), shelf("playlists"),
        )
    }

    /**
     * Direct Apple artist relationship/view pagination (including Singles/EPs).
     * Reusing the search offset would be incorrect: follow the upstream cursor.
     */
    suspend fun artistCollection(
        id: String, country: String, shelf: AppleMusicWebArtistShelf,
        page: Int = 1, limit: Int = 20,
    ): AppleMusicWebPage {
        require(page in 1..20 && limit in 1..100)
        val sf = storefront(country)
        val artistId = resourceId(id, AppleMusicWebKind.ARTIST)
        val path = "/v1/catalog/$sf/artists/$artistId/${shelf.endpoint}"
        val firstParams = mapOf("limit" to limit.toString())
        var block = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            path, firstParams))
        val visited = mutableSetOf<String>()
        for (index in 1 until page) {
            val cursor = block.str("next")
                ?: return AppleMusicWebPage(emptyList(), page, limit, false)
            if (!visited.add(cursor)) throw AppleMusicWebProtocolException("Cyclic artist cursor")
            val params = follow(cursor, path) + firstParams
            block = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path, params))
        }
        return AppleMusicWebPage(items(block, sf), page, limit, block.str("next") != null)
    }

    suspend fun album(id: String, country: String): AppleMusicWebAlbum =
        when (val outcome = albumOutcome(id, country)) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial ->
                throw AppleMusicWebProtocolException("Incomplete Apple album relationship")
            is AppleMusicWebOutcome.Unavailable ->
                throw AppleMusicWebProtocolException("Album unavailable")
        }

    /** Exposes truncated/partial albums explicitly to future canonical adapters. */
    suspend fun albumOutcome(
        id: String,
        country: String,
    ): AppleMusicWebOutcome<AppleMusicWebAlbum> {
        val sf = storefront(country)
        val albumId = resourceId(id, AppleMusicWebKind.ALBUM)
        val raw = detail(sf, AppleMusicWebKind.ALBUM, albumId, mapOf(
            "include" to "tracks,artists,genres,record-labels",
            "extend" to "editorialArtwork,plainEditorialNotes",
        ))
        val identity = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid album")
        val rel = raw.obj("relationships")
        val first = rel?.obj("tracks")
        // A missing or unexpanded relationship is not a proven empty album.
        val missingTracklist = first == null ||
            first["data"] == null || first["data"].toString() == "null"
        val expectedTracks = (raw.obj("attributes")?.get("trackCount")
            as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val tracks = ArrayList(items(first, sf))
        val allowedTrackPaths = setOf(
            "/v1/catalog/$sf/albums/$albumId/tracks",
            "/v1/catalog/$sf/albums/$albumId/relationships/tracks",
        )
        val seen = mutableSetOf<String>()
        var cursor = first?.str("next")
        var interrupted = false
        val itemIds = tracks.mapTo(mutableSetOf()) { it.scopedId }
        while (cursor != null && tracks.size < 500) {
            if (!seen.add(cursor)) throw AppleMusicWebProtocolException("Cyclic album cursor")
            val path = runCatching { URI(cursor).path }.getOrNull()
                ?: throw AppleMusicWebProtocolException("Malformed album cursor")
            if (path !in allowedTrackPaths)
                throw AppleMusicWebProtocolException("Cross-album track cursor")
            val args = follow(cursor, path) +
                ("limit" to minOf(100, 500 - tracks.size).toString())
            val block = try {
                parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path, args))
            } catch (blocked: AppleMusicWebHttpException) {
                // A denied or throttled capability must not publish cached
                // metadata as if the request succeeded.
                if (blocked.status in listOf(401, 403, 429)) throw blocked
                interrupted = true
                break
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                interrupted = true
                break
            }
            val additional = items(block, sf)
            if (additional.isEmpty()) {
                interrupted = true
                break
            }
            if (additional.any { !itemIds.add(it.scopedId) }) {
                throw AppleMusicWebProtocolException("Duplicate Apple album track across pages")
            }
            tracks.addAll(additional)
            cursor = block.str("next")
        }
        val countMismatch = expectedTracks != null && tracks.size != expectedTracks
        val truncated = cursor != null || interrupted || missingTracklist || countMismatch
        val result = AppleMusicWebAlbum(
            identity, tracks.take(500), items(rel?.obj("artists"), sf),
            possiblyTruncated = truncated,
        )
        return when {
            missingTracklist -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.MISSING_TRACKLIST,
            )
            interrupted -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED,
            )
            countMismatch -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.TRACK_COUNT_MISMATCH,
            )
            cursor != null -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.PAGE_LIMIT,
            )
            else -> AppleMusicWebOutcome.Complete(result)
        }
    }
}
