package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.MusicBrainzCoverArtCacheEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale

internal data class CoverArtArchiveResponse(val status: Int, val body: String?)

internal interface CoverArtArchiveTransport {
    suspend fun get(path: String): CoverArtArchiveResponse
}

/** CAA JSON is looked up first; 307 redirects to binary artwork are left to Coil. */
internal class KtorCoverArtArchiveTransport(
    private val baseUrl: String = "https://coverartarchive.org",
    private val client: HttpClient = HttpClient(OkHttp) { expectSuccess = false },
) : CoverArtArchiveTransport {
    override suspend fun get(path: String): CoverArtArchiveResponse {
        val response = client.get("${baseUrl.trimEnd('/')}/$path") {
            header(HttpHeaders.UserAgent, defaultMusicBrainzUserAgent())
        }
        return CoverArtArchiveResponse(
            status = response.status.value,
            body = if (response.status.value == 200) response.bodyAsText() else null,
        )
    }
}

internal data class MusicBrainzCoverArtCacheEntry(
    val entityType: String,
    val mbid: String,
    val artworkUri: String?,
    val fetchedAtEpochMs: Long,
    val thumbnailUri: String? = null,
)

internal enum class CoverArtSize { THUMBNAIL, HERO }

internal interface MusicBrainzCoverArtStore {
    suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry?
    suspend fun write(value: MusicBrainzCoverArtCacheEntry)
}

/**
 * The 2026-10-26 strict Front/Poster/approval policy uses a new Room cache key.
 * Pre-policy artwork may have been cached at 1200px yet depict a music video.
 * Keep legacy rows intact for migration safety, but never read them again.
 */
internal fun verifiedFrontCacheType(type: String): String =
    when (type) {
        "release", "release-group" -> "$type:front-v2"
        else -> error("Unsupported cover cache entity type")
    }

internal class RoomMusicBrainzCoverArtStore(
    private val dao: CanonicalCatalogDao,
) : MusicBrainzCoverArtStore {
    override suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry? =
        dao.musicBrainzCoverArtCache(verifiedFrontCacheType(type), mbid)?.let {
            MusicBrainzCoverArtCacheEntry(
                type, it.mbid, it.artworkUri, it.fetchedAtEpochMs, it.thumbnailUri,
            )
        }

    override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
        dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
            verifiedFrontCacheType(value.entityType), value.mbid,
            value.artworkUri, value.fetchedAtEpochMs, value.thumbnailUri,
        ))
    }
}

/** Development-safe default; Android injects RoomMusicBrainzCoverArtStore. */
private class MemoryCoverArtStore : MusicBrainzCoverArtStore {
    private val cache = mutableMapOf<Pair<String, String>, MusicBrainzCoverArtCacheEntry>()
    override suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry? =
        synchronized(cache) { cache[type to mbid] }

    override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
        synchronized(cache) { cache[value.entityType to value.mbid] = value }
    }
}

/** Differentiates confirmed absence from temporary failures before fallback. */
private sealed interface CoverLookup {
    data class Found(val uri: String, val thumbnailUri: String? = null) : CoverLookup
    data object Missing : CoverLookup
    data object Unavailable : CoverLookup
}

/**
 * Prefer the actual release's front cover. Fall back exclusively to its known
 * ReleaseGroup; never substitute art based only on same title or artist.
 * Cache 404s briefly to avoid re-probing unavailable images on every visit.
 * Transient network/503 responses are not treated as permanent missing art.
 */
internal class CoverArtArchive(
    private val transport: CoverArtArchiveTransport = KtorCoverArtArchiveTransport(),
    private val store: MusicBrainzCoverArtStore = MemoryCoverArtStore(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMs: Long = 6_000L,
    private val telemetry: CatalogCoverTelemetry = CatalogCoverTelemetry.process,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Different Home/search cards often resolve the same Release concurrently.
    // Share only simultaneous lookups; do not convert transient failures into
    // a long-lived negative cache. Other MBIDs proceed independently.
    private val inFlight = ConcurrentHashMap<Pair<String, String>, CompletableDeferred<CoverLookup>>()

    suspend fun findCover(
        releaseMbid: String?, groupMbid: String?,
        size: CoverArtSize = CoverArtSize.HERO,
    ): String? {
        // Invalid supplied IDs fail closed, including before attempting group art.
        if (releaseMbid != null && !validMbid(releaseMbid)) return null
        if (groupMbid != null && !validMbid(groupMbid)) return null
        if (releaseMbid == null && groupMbid == null) return null

        releaseMbid?.let { mbid ->
            when (val primary = fetch("release", mbid.lowercase(Locale.ROOT))) {
                is CoverLookup.Found -> return primary.urlFor(size)
                CoverLookup.Unavailable -> return null // Never replace an unknown edition cover.
                CoverLookup.Missing -> Unit // Only a confirmed absence permits group fallback.
            }
        }
        groupMbid?.let { mbid ->
            return when (val fallback = fetch("release-group", mbid.lowercase(Locale.ROOT))) {
                is CoverLookup.Found -> fallback.urlFor(size)
                CoverLookup.Missing, CoverLookup.Unavailable -> null
            }
        }
        return null
    }

    private fun CoverLookup.Found.urlFor(size: CoverArtSize): String =
        if (size == CoverArtSize.THUMBNAIL) thumbnailUri ?: uri else uri

    private suspend fun fetch(type: String, mbid: String): CoverLookup {
        val key = type to mbid
        val proposed = CompletableDeferred<CoverLookup>()
        val existing = inFlight.putIfAbsent(key, proposed)
        if (existing != null) {
            telemetry.recordJoinedRequest()
            return existing.await()
        }
        try {
            val result = fetchUnshared(type, mbid)
            proposed.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            proposed.cancel(cancelled)
            throw cancelled
        } catch (_: Exception) {
            proposed.complete(CoverLookup.Unavailable)
            return CoverLookup.Unavailable
        } finally {
            inFlight.remove(key, proposed)
        }
    }

    private suspend fun fetchUnshared(type: String, mbid: String): CoverLookup {
        val now = nowEpochMs()
        val cached = try {
            store.read(type, mbid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // A damaged/unavailable cache must not break discovery.
        }
        if (cached != null &&
            now >= cached.fetchedAtEpochMs &&
            now - cached.fetchedAtEpochMs < if (cached.artworkUri == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        ) {
            // Room cache is a persistence boundary: never trust old or tampered
            // artwork URLs without the same allowlist used for fresh responses.
            if (cached.artworkUri == null) {
                telemetry.recordNegativeCacheHit()
                return CoverLookup.Missing
            }
            // A 250px image can be the ONLY artwork the archive publishes.
            // Re-probe it periodically, not on every card composition/visit.
            val smallArtwork = cached.artworkUri.substringBefore('?').let {
                it.endsWith("-250.jpg") || it.endsWith("-250")
            }
            if (safeImageUrl(cached.artworkUri) &&
                (!smallArtwork || now - cached.fetchedAtEpochMs < SMALL_ART_RECHECK_MS)
            ) {
                telemetry.recordCacheHit()
                return CoverLookup.Found(
                    cached.artworkUri, cached.thumbnailUri?.takeIf(::safeImageUrl),
                )
            }
            // Old small covers get a chance to upgrade; a suspicious cached URL
            // must be revalidated rather than rendered or treated as a 404.
        }

        val started = System.nanoTime()
        fun elapsedMs(): Long = ((System.nanoTime() - started) / 1_000_000L)
            .coerceAtLeast(0L)
        val answer = try {
            // A stalled archive endpoint must not hold a screen's artwork
            // pipeline indefinitely. Timeout is transient, never a cached 404.
            withTimeoutOrNull(requestTimeoutMs) {
                transport.get("$type/$mbid")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (answer == null) {
            telemetry.recordNetwork(-1, elapsedMs())
            return CoverLookup.Unavailable
        }
        telemetry.recordNetwork(answer.status, elapsedMs())
        val art: CoverLookup.Found? = when (answer.status) {
            200 -> try {
                val root = json.parseToJsonElement(answer.body.orEmpty()) as? JsonObject
                    ?: return CoverLookup.Unavailable
                val pictures = root["images"] as? JsonArray ?: return CoverLookup.Unavailable
                val frontImages = pictures.mapNotNull { it as? JsonObject }
                    .filter { entry ->
                        if (entry["front"]?.jsonPrimitive?.booleanOrNull != true ||
                            entry["approved"]?.jsonPrimitive?.booleanOrNull == false
                        ) return@filter false
                        // MusicBrainz CAA has separate Track, Poster and Front types.
                        // A poster marked front is not evidence of an audio sleeve.
                        val types = entry["types"] as? JsonArray
                        val labels = types?.mapNotNull {
                            runCatching { it.jsonPrimitive.contentOrNull?.lowercase(Locale.ROOT) }
                                .getOrNull()
                        }.orEmpty()
                        (labels.isEmpty() || "front" in labels) &&
                            "poster" !in labels && "track" !in labels
                    }
                val front = frontImages.firstNotNullOfOrNull { entry ->
                    val thumbs = entry["thumbnails"] as? JsonObject
                    fun thumb(key: String): String? =
                        thumbs?.get(key)?.jsonPrimitive?.contentOrNull?.let(::trustedImageUrl)
                    // Both URLs are retrieved from the SAME verified CAA
                    // Front entry. Never synthesize a thumbnail by changing an
                    // unrelated image URL, especially for video-poster releases.
                    val hero = thumb("1200") ?: thumb("500") ?:
                        entry["image"]?.jsonPrimitive?.contentOrNull?.let(::trustedImageUrl) ?:
                        thumb("250")
                    hero?.let { verified ->
                        CoverLookup.Found(verified,
                            thumb("500") ?: thumb("250") ?: verified)
                    }
                }
                if (front == null && frontImages.isNotEmpty()) return CoverLookup.Unavailable
                front
            } catch (_: Exception) {
                return CoverLookup.Unavailable
            }
            404 -> null
            else -> return CoverLookup.Unavailable
        }
        try {
            store.write(MusicBrainzCoverArtCacheEntry(
                type, mbid, art?.uri, now, art?.thumbnailUri,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache failure never changes the art already fetched from CAA.
        }
        return art ?: CoverLookup.Missing
    }

    /**
     * CAA metadata sometimes advertises http:// links to its own archive
     * even though the API is fetched over HTTPS. HTTPS-upgrade only a strict
     * allowlist of CAA/archive domains. Never rewrite or admit third-party URLs.
     */
    private fun trustedImageUrl(raw: String): String? {
        val secure = if (raw.startsWith("http://", ignoreCase = true)) {
            "https://" + raw.substring(7)
        } else raw
        return secure.takeIf(::safeImageUrl)
    }

    private fun safeImageUrl(raw: String): Boolean {
        return try {
            val uri = URI(raw)
            val host = uri.host?.lowercase(Locale.ROOT)
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == "coverartarchive.org" || host == "archive.org" ||
                    host?.endsWith(".archive.org") == true) &&
                uri.userInfo == null
        } catch (_: Exception) {
            false
        }
    }

    private fun validMbid(value: String): Boolean = MBID.matches(value.lowercase(Locale.ROOT))

    private companion object {
        const val SMALL_ART_RECHECK_MS = 6L * 60 * 60 * 1_000
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1_000
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
