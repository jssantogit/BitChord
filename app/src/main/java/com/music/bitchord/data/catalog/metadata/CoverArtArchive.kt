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
)

internal interface MusicBrainzCoverArtStore {
    suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry?
    suspend fun write(value: MusicBrainzCoverArtCacheEntry)
}

internal class RoomMusicBrainzCoverArtStore(
    private val dao: CanonicalCatalogDao,
) : MusicBrainzCoverArtStore {
    override suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry? =
        dao.musicBrainzCoverArtCache(type, mbid)?.let {
            MusicBrainzCoverArtCacheEntry(it.entityType, it.mbid, it.artworkUri, it.fetchedAtEpochMs)
        }

    override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
        dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
            value.entityType, value.mbid, value.artworkUri, value.fetchedAtEpochMs,
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
    data class Found(val uri: String) : CoverLookup
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
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun findCover(releaseMbid: String?, groupMbid: String?): String? {
        // Invalid supplied IDs fail closed, including before attempting group art.
        if (releaseMbid != null && !validMbid(releaseMbid)) return null
        if (groupMbid != null && !validMbid(groupMbid)) return null
        if (releaseMbid == null && groupMbid == null) return null

        releaseMbid?.let { mbid ->
            when (val primary = fetch("release", mbid.lowercase(Locale.ROOT))) {
                is CoverLookup.Found -> return primary.uri
                CoverLookup.Unavailable -> return null // Never replace an unknown edition cover.
                CoverLookup.Missing -> Unit // Only a confirmed absence permits group fallback.
            }
        }
        groupMbid?.let { mbid ->
            return when (val fallback = fetch("release-group", mbid.lowercase(Locale.ROOT))) {
                is CoverLookup.Found -> fallback.uri
                CoverLookup.Missing, CoverLookup.Unavailable -> null
            }
        }
        return null
    }

    private suspend fun fetch(type: String, mbid: String): CoverLookup {
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
            if (cached.artworkUri == null) return CoverLookup.Missing
            if (safeImageUrl(cached.artworkUri)) return CoverLookup.Found(cached.artworkUri)
            // Invalid positive entries must be refreshed, not rendered or
            // downgraded into cached "missing" evidence.
        }

        val answer = try {
            transport.get("$type/$mbid")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CoverLookup.Unavailable
        }
        val uri = when (answer.status) {
            200 -> try {
                val root = json.parseToJsonElement(answer.body.orEmpty()) as? JsonObject
                    ?: return CoverLookup.Unavailable
                val pictures = root["images"] as? JsonArray ?: return CoverLookup.Unavailable
                val frontImages = pictures.mapNotNull { it as? JsonObject }
                    .filter { it["front"]?.jsonPrimitive?.booleanOrNull == true }
                val front = frontImages.firstNotNullOfOrNull { entry ->
                    val thumbs = entry["thumbnails"] as? JsonObject
                    listOf("250", "500", "1200").firstNotNullOfOrNull { size ->
                        thumbs?.get(size)?.jsonPrimitive?.contentOrNull?.takeIf(::safeImageUrl)
                    } ?: entry["image"]?.jsonPrimitive?.contentOrNull?.takeIf(::safeImageUrl)
                }
                // A front cover was advertised but its URL cannot be trusted.
                // This is NOT proof that an edition has no front cover. Do not
                // persist a 404-like miss or silently switch to group artwork.
                if (front == null && frontImages.isNotEmpty()) return CoverLookup.Unavailable
                front
            } catch (_: Exception) {
                return CoverLookup.Unavailable
            }
            404 -> null
            else -> return CoverLookup.Unavailable
        }
        try {
            store.write(MusicBrainzCoverArtCacheEntry(type, mbid, uri, now))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache failure never changes the art already fetched from CAA.
        }
        return uri?.let { CoverLookup.Found(it) } ?: CoverLookup.Missing
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
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1_000
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
