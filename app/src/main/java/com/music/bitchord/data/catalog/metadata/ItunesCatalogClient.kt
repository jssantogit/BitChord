package com.music.bitchord.data.catalog.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Public iTunes Search API only. This is an independent, storefront-scoped
 * metadata catalog, NOT Apple Music playback or an entitlement to promo assets.
 *
 * No external result is a MusicBrainz identity, a playable MediaAsset, an
 * official MusicBrainz classification, or permission to republish cover art.
 */
internal enum class ItunesCatalogEntity(val apiValue: String) {
    ARTIST("musicArtist"),
    ALBUM("album"),
    SONG("song"),
}

internal enum class ItunesCatalogItemType(val key: String) {
    ARTIST("artist"),
    ALBUM("album"),
    TRACK("track"),
}

internal data class ItunesCatalogItem(
    val type: ItunesCatalogItemType,
    val id: Long,
    val storefront: String,
    val title: String,
    val artistName: String?,
    val artistId: Long?,
    val collectionId: Long?,
    val storeUrl: String?,
    val releaseDate: String?,
    val genre: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    /** Edition-scoped remote artwork URL, for private experimental presentation. */
    val artworkUrl: String? = null,
    val upc: String? = null,
    val trackCount: Int? = null,
) {
    val scopedId: String get() = "itunes:$storefront:${type.key}:$id"

    // Deliberately not derived from titles, ISRC, UPC, artwork or a store ID.
    val musicBrainzId: String? get() = null
    val isPlayable: Boolean get() = false

    // No audio asset or preview is ever created from storefront metadata.
    val previewUrl: String? get() = null
}

internal enum class ItunesCatalogFailure {
    INVALID_QUERY,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
}

internal sealed interface ItunesCatalogResult {
    data class Success(val items: List<ItunesCatalogItem>) : ItunesCatalogResult
    data class Failure(val reason: ItunesCatalogFailure) : ItunesCatalogResult
}

/** Injected boundary: fake transport tests never send requests or consume rate budget. */
internal interface ItunesCatalogTransport {
    suspend fun get(path: String, parameters: Map<String, String>): String
}

/**
 * Apple documents approximately 20 search/lookup requests per minute. A single
 * process-wide budget serializes starts at >=3.1s spacing, including across
 * independent clients and both endpoints. No automatic retries on HTTP 429.
 */
internal class ItunesCatalogRateLimiter(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMs: Long = 3_100L,
) {
    private val mutex = Mutex()
    private var lastStartedMs: Long? = null

    init { require(minimumIntervalMs >= 3_000L) }

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            lastStartedMs?.let { last ->
                val remaining = minimumIntervalMs - (clockMs() - last)
                if (remaining > 0L) sleep(remaining)
            }
            lastStartedMs = clockMs()
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

internal object ItunesCatalogRequestBudget {
    val limiter = ItunesCatalogRateLimiter()
}

internal class KtorItunesCatalogTransport(
    private val client: HttpClient = defaultItunesHttpClient,
    private val limiter: ItunesCatalogRateLimiter = ItunesCatalogRequestBudget.limiter,
) : ItunesCatalogTransport {
    override suspend fun get(path: String, parameters: Map<String, String>): String {
        require(path == "search" || path == "lookup")
        return limiter.run {
            val response = client.get("https://itunes.apple.com/$path") {
                parameters.forEach { (name, value) -> parameter(name, value) }
            }
            if (response.status.value != 200) {
                response.bodyAsChannel().cancel(IOException("iTunes HTTP ${response.status.value}"))
                throw IOException("iTunes metadata endpoint unavailable")
            }
            val body = response.bodyAsChannel()
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_ITUNES_JSON_BYTES) {
                body.cancel(IOException("Oversized iTunes response"))
                throw IOException("Oversized iTunes response")
            }
            val buffer = ByteArray(8_192)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = body.readAvailable(
                    buffer, 0, minOf(buffer.size, MAX_ITUNES_JSON_BYTES - total + 1)
                )
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (total > MAX_ITUNES_JSON_BYTES) {
                    body.cancel(IOException("Oversized iTunes response"))
                    throw IOException("Oversized iTunes response")
                }
                output.write(buffer, 0, read)
            }
            if (declared != null && total.toLong() != declared) {
                throw IOException("Incomplete iTunes response")
            }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
    }
}

private const val MAX_ITUNES_JSON_BYTES = 512_000

private val defaultItunesHttpClient: HttpClient by lazy {
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    }
}

/**
 * The Search API has neither offset pagination nor authoritative Album/EP/Single
 * types. Never synthesize missing identity/classification from a track count or
 * title; expose independently sourced rows for deliberate human comparison.
 */
internal class ItunesCatalogClient(
    private val transport: ItunesCatalogTransport = KtorItunesCatalogTransport(),
) {
    suspend fun search(
        term: String,
        entity: ItunesCatalogEntity,
        country: String = "br",
        limit: Int = 20,
    ): ItunesCatalogResult {
        val query = term.trim()
        val storefront = normalizedStorefront(country)
        if (query.isEmpty() || query.length > 120 || query.any(Char::isISOControl) ||
            storefront == null || limit !in 1..30
        ) return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return request("search", linkedMapOf(
            "term" to query, "country" to storefront, "media" to "music",
            "entity" to entity.apiValue, "limit" to limit.toString(),
        ), storefront, expectedType = when (entity) {
            ItunesCatalogEntity.ARTIST -> ItunesCatalogItemType.ARTIST
            ItunesCatalogEntity.ALBUM -> ItunesCatalogItemType.ALBUM
            ItunesCatalogEntity.SONG -> ItunesCatalogItemType.TRACK
        })
    }

    suspend fun lookupAlbum(
        collectionId: Long,
        country: String = "br",
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (collectionId <= 0L || storefront == null) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "id" to collectionId.toString(), "country" to storefront,
            "entity" to "song", "limit" to "200",
        ), storefront, collectionId = collectionId)
    }

    suspend fun lookupArtistAlbums(
        artistId: Long,
        country: String = "br",
        limit: Int = 30,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (artistId <= 0L || storefront == null || limit !in 1..30) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "id" to artistId.toString(), "country" to storefront,
            "entity" to "album", "limit" to limit.toString(),
        ), storefront, artistId = artistId)
    }

    /** UPC is only a candidate identity signal; never silently claim an MBID. */
    suspend fun lookupUpc(upc: String, country: String = "br"): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (storefront == null || !Regex("[0-9]{8,14}").matches(upc)) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "upc" to upc, "country" to storefront, "entity" to "song", "limit" to "200",
        ), storefront)
    }

    private suspend fun request(
        path: String,
        params: Map<String, String>,
        storefront: String,
        expectedType: ItunesCatalogItemType? = null,
        collectionId: Long? = null,
        artistId: Long? = null,
    ): ItunesCatalogResult {
        val json = try {
            transport.get(path, params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)
        }
        return try {
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: return ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
            val count = (root["resultCount"] as? JsonPrimitive)?.intOrNull
            val rows = root["results"] as? JsonArray
            if (count == null || count < 0 || rows == null || rows.size > 200 ||
                count < rows.size
            ) return ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
            val items = rows.mapNotNull { (it as? JsonObject)?.toCatalogItem(storefront) }
                .filter { expectedType == null || it.type == expectedType }
                .filter { collectionId == null ||
                    (it.type == ItunesCatalogItemType.ALBUM && it.id == collectionId) ||
                    (it.type == ItunesCatalogItemType.TRACK && it.collectionId == collectionId)
                }
                .filter { artistId == null ||
                    (it.type == ItunesCatalogItemType.ARTIST && it.id == artistId) ||
                    (it.type == ItunesCatalogItemType.ALBUM && it.artistId == artistId)
                }
                .distinctBy { it.scopedId }
            ItunesCatalogResult.Success(items)
        } catch (_: Exception) {
            ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
        }
    }
}

private fun normalizedStorefront(country: String): String? =
    country.trim().lowercase(Locale.ROOT).takeIf { Regex("[a-z]{2}").matches(it) }

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.positiveId(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

private fun JsonObject.positiveNumber(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun safeStoreLink(raw: String?): String? {
    if (raw == null) return null
    return try {
        val uri = URI(raw)
        if (uri.scheme == "https" && uri.userInfo == null &&
            uri.host?.lowercase(Locale.ROOT) in setOf("music.apple.com", "itunes.apple.com")
        ) raw else null
    } catch (_: Exception) { null }
}

private fun safeAppleArtwork(raw: String?): String? {
    if (raw == null) return null
    return try {
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme == "https" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host != null && host.endsWith(".mzstatic.com") &&
            host.length > ".mzstatic.com".length &&
            uri.path?.let { path ->
                listOf(".jpg", ".jpeg", ".png", ".webp").any {
                    path.lowercase(Locale.ROOT).endsWith(it)
                }
            } == true
        ) raw else null
    } catch (_: Exception) { null }
}

private fun JsonObject.toCatalogItem(storefront: String): ItunesCatalogItem? {
    val wrapper = text("wrapperType")
    val kind = when (wrapper) {
        "artist" -> ItunesCatalogItemType.ARTIST
        "collection" -> if (text("collectionType")?.equals("Album", true) != false)
            ItunesCatalogItemType.ALBUM else return null
        "track" -> if (text("kind") == "song") ItunesCatalogItemType.TRACK
            else return null
        else -> return null
    }
    val id = positiveId(when (kind) {
        ItunesCatalogItemType.ARTIST -> "artistId"
        ItunesCatalogItemType.ALBUM -> "collectionId"
        ItunesCatalogItemType.TRACK -> "trackId"
    }) ?: return null
    val artist = text("artistName")
    val title = when (kind) {
        ItunesCatalogItemType.ARTIST -> artist
        ItunesCatalogItemType.ALBUM -> text("collectionName")
        ItunesCatalogItemType.TRACK -> text("trackName")
    }?.takeIf { it.length <= 300 } ?: return null

    return ItunesCatalogItem(
        type = kind, id = id, storefront = storefront, title = title,
        artistName = artist, artistId = positiveId("artistId"),
        collectionId = positiveId("collectionId"),
        storeUrl = safeStoreLink(when (kind) {
            ItunesCatalogItemType.ARTIST -> text("artistLinkUrl") ?: text("artistViewUrl")
            ItunesCatalogItemType.ALBUM -> text("collectionViewUrl")
            ItunesCatalogItemType.TRACK -> text("trackViewUrl")
        }),
        releaseDate = text("releaseDate")?.takeIf { it.length <= 40 },
        genre = text("primaryGenreName")?.takeIf { it.length <= 100 },
        durationMs = if (kind == ItunesCatalogItemType.TRACK)
            positiveId("trackTimeMillis") else null,
        trackNumber = positiveNumber("trackNumber"),
        discNumber = positiveNumber("discNumber"),
        artworkUrl = safeAppleArtwork(text("artworkUrl100"))
            ?: safeAppleArtwork(text("artworkUrl60"))
            ?: safeAppleArtwork(text("artworkUrl30")),
        upc = text("upc")?.takeIf { Regex("[0-9]{8,14}").matches(it) },
        trackCount = positiveNumber("trackCount"),
    )
}
