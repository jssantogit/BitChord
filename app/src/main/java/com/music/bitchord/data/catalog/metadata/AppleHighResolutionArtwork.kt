package com.music.bitchord.data.catalog.metadata

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The public iTunes Search API guarantees artworkUrl100/60, NOT a large image.
 * Its image CDN commonly serves size-suffixed variants, but that path is
 * undocumented. Therefore never publish a guessed variant until we have
 * fetched and checked real decoded dimensions from that same Apple host.
 *
 * This does not crop, upscale or transform the album artwork locally, and it
 * does not alter the verified Apple album/storefront association.
 */
internal fun appleHighResolutionArtworkCandidate(
    original: String?,
    desiredPx: Int,
): String? {
    if (desiredPx !in setOf(600, 720, 1024) ||
        original.isNullOrBlank() || original.length > 2048 ||
        safeAppleArtwork(original) == null
    ) return null
    val uri = try { URI(original) } catch (_: Exception) { return null }
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!Regex("""(?:is|a)\d+(?:-ssl)?\.mzstatic\.com""").matches(host) ||
        uri.rawQuery != null || uri.rawFragment != null ||
        uri.userInfo != null || uri.port != -1 || uri.rawPath.contains('%')
    ) return null
    val match = Regex(
        """([/.])(\d{2,4})x(\d{2,4})(bb|-\d{2,3})\.(jpg|jpeg|png|webp)$""",
        RegexOption.IGNORE_CASE,
    ).find(uri.rawPath) ?: return null
    val originalWidth = match.groupValues[2].toIntOrNull() ?: return null
    val originalHeight = match.groupValues[3].toIntOrNull() ?: return null
    if (originalWidth != originalHeight || originalWidth >= desiredPx) return null
    val newSuffix = "${match.groupValues[1]}${desiredPx}x${desiredPx}${match.groupValues[4]}.${match.groupValues[5]}"
    // Only replace the final size path component, never a catalog/image ID.
    val candidate = original.removeSuffix(match.value) + newSuffix
    return candidate.takeIf { safeAppleArtwork(it) != null }
}

internal fun interface AppleArtworkDimensionSource {
    /** Return actual decoded pixel dimensions, not Content-Length/URL claims. */
    suspend fun read(candidate: String): Pair<Int, Int>?
}

/** One small bounded HTTP range request, without following redirects. */
private class AndroidAppleArtworkDimensionSource : AppleArtworkDimensionSource {
    override suspend fun read(candidate: String): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            // Verify again at the boundary: a URL cannot ask us to fetch an
            // arbitrary host, no redirect to tracking/internal endpoints.
            val uri = try { URI(candidate) } catch (_: Exception) { return@withContext null }
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@withContext null
            if (uri.scheme != "https" ||
                !Regex("""(?:is|a)\d+(?:-ssl)?\.mzstatic\.com""").matches(host) ||
                uri.userInfo != null || uri.port != -1 ||
                uri.rawQuery != null || uri.rawFragment != null
            ) return@withContext null
            val connection = try {
                (URL(candidate).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    instanceFollowRedirects = false
                    setRequestProperty("Range", "bytes=0-262143")
                    setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { return@withContext null }
            try {
                if (connection.responseCode !in setOf(200, 206) ||
                    connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
                        !in setOf("image/jpeg", "image/png", "image/webp") ||
                    (connection.responseCode == 200 && connection.contentLengthLong > 8_000_000L)
                ) return@withContext null

                // Bounds decode never allocates a full-size bitmap. Limit the
                // bytes even if the server ignores our Range request.
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (bytes.size() < 262_144) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 262_144 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                }
                val payload = bytes.toByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
                bounds.outWidth.takeIf { it > 0 }?.let { width ->
                    bounds.outHeight.takeIf { it > 0 }?.let { height -> width to height }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            finally { connection.disconnect() }
        }
}

/**
 * Successful probes live for 12h; failures retry after 30m. No unbounded
 * negatives and no extra public iTunes API requests (only the image host).
 */
internal class AppleHighResolutionArtwork(
    private val dimensions: AppleArtworkDimensionSource = AndroidAppleArtworkDimensionSource(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val valid: Boolean, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    // Striped single-flight locks: at most one network check per URL while
    // unrelated covers can load in parallel. A bounded stripe array avoids
    // an unbounded per-album Mutex map and survives cache eviction.
    private val probeLocks = Array(32) { Mutex() }

    suspend fun upgrade(artworkUrl: String?, desiredPx: Int = 1024): String? {
        val original = safeAppleArtwork(artworkUrl) ?: return artworkUrl
        // Hero: aim for 1024; fall back directly to 720 then 600.
        // Avoid 1000px as a redundant fourth network probe that would slow
        // broken CDN responses. Small cards request 600 only.
        val sizes = when (desiredPx) {
            1024 -> listOf(1024, 720, 600)
            720 -> listOf(720, 600)
            else -> listOf(desiredPx)
        }
        for (size in sizes) {
            val candidate = appleHighResolutionArtworkCandidate(original, size)
                ?: continue
            val stripe = (candidate.hashCode() and Int.MAX_VALUE) % probeLocks.size
            val verified = probeLocks[stripe].withLock {
                val now = clockMs()
                val prior = cache[candidate]
                if (prior != null && prior.expiresAt > now)
                    return@withLock prior.valid
                val measured = try {
                    dimensions.read(candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                val accepted = measured?.let { (width, height) ->
                    width >= size && height >= size && width == height
                } == true
                if (cache.size > 192) cache.clear()
                cache[candidate] = Cached(
                    accepted,
                    now + if (accepted) 12 * 60 * 60_000L else 30 * 60_000L,
                )
                accepted
            }
            if (verified) return candidate
        }
        return original
    }

    suspend fun upgrade(
        edition: AppleVerifiedEditionArtwork,
        desiredPx: Int = 1024,
    ): AppleVerifiedEditionArtwork {
        val selected = upgrade(edition.artworkUrl, desiredPx)
        return if (selected == edition.artworkUrl || selected == null) edition
            else edition.copy(artworkUrl = selected)
    }
}
