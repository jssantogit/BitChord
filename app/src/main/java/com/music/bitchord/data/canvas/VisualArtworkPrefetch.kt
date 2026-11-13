package com.music.bitchord.data.canvas

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit

/**
 * Artwork decoding warmup does not belong to MusicBrainz/Apple/Navidrome.
 * Any future provider can submit verified HTTPS cover URLs and importance.
 */
enum class VisualArtworkPriority { HERO, VISIBLE, NEARBY }

data class VisualArtworkCandidate(
    val url: String?,
    val priority: VisualArtworkPriority,
    val targetPx: Int,
)

fun planVisualArtworkPrefetch(
    candidates: List<VisualArtworkCandidate>,
    maxRequests: Int = 12,
): List<VisualArtworkCandidate> = candidates.asSequence()
    .mapNotNull { candidate ->
        val target = candidate.targetPx.coerceIn(96, 1200)
        // Use the same shared URL-size transformation as native AsyncImage
        // rows. Prewarming w300-h300 while the UI requests w160-h160 wastes
        // bandwidth and does not hit the exact CDN/disk key.
        val url = candidate.url?.artworkAt(target)
            ?.takeIf(::isSafeVisualMediaUrl) ?: return@mapNotNull null
        candidate.copy(url = url, targetPx = target)
    }
    .sortedWith(compareBy<VisualArtworkCandidate> { it.priority.ordinal }
        .thenByDescending { it.targetPx })
    .distinctBy { it.url }
    .take(maxRequests.coerceIn(0, 24))
    .toList()

/**
 * Coil already owns decoded-image memory/disk caching. This gate handles the
 * other expensive part: simultaneous Home/Explore/artist warmups can request
 * the very same HTTPS artwork while it's still in flight. Coalesce only
 * ongoing work and cap *global* speculative concurrency, regardless of which
 * source (MusicBrainz, Apple, Navidrome or a future provider) supplied it.
 *
 * A cancelled page releases its key in finally; a later page may try again.
 * Actual visible AsyncImage loads always remain independent and prioritized.
 */
internal class VisualArtworkFetchGate(
    maxConcurrent: Int = 3,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val successTtlMs: Long = 5 * 60_000L,
    private val successCapacity: Int = 256,
) {
    private val inflight = mutableSetOf<String>()
    // Coil keeps actual pixels; this tiny bounded ledger only prevents
    // repeated speculative decodes when a shelf re-enters the viewport.
    private val warmed = LinkedHashMap<String, Long>()
    private val lock = Mutex()
    private val permits = Semaphore(maxConcurrent)

    suspend fun fetchOnce(key: String, fetch: suspend () -> Unit): Boolean {
        if (!lock.withLock {
            val at = warmed[key]
            if (at != null) {
                val age = nowMillis() - at
                if (age >= 0 && age < successTtlMs) return@withLock false
                warmed.remove(key)
            }
            inflight.add(key)
        }) return false
        try {
            permits.withPermit { fetch() }
            lock.withLock {
                warmed.remove(key)
                warmed[key] = nowMillis()
                while (warmed.size > successCapacity.coerceAtLeast(1)) {
                    warmed.remove(warmed.keys.first())
                }
            }
            return true
        } finally {
            // A timeout or cancellation must never suppress the next attempt.
            withContext(NonCancellable) {
                lock.withLock { inflight.remove(key) }
            }
        }
    }
}

/**
 * Native Home/Explore shelves are lists of artwork URLs regardless of source.
 * Prefer the shelf the listener is viewing, then the one immediately ahead.
 * Keep a strict cap: speculative thumbnails must never saturate the network.
 */
fun planVisualShelfArtwork(
    shelfUrls: List<List<String?>>,
    visibleShelfIndex: Int,
    targetPx: Int,
    maxCandidates: Int = 12,
): List<VisualArtworkCandidate> {
    if (shelfUrls.isEmpty()) return emptyList()
    val visible = visibleShelfIndex.coerceIn(0, shelfUrls.lastIndex)
    val priorityOrder = shelfUrls.indices.sortedWith(
        compareBy<Int> { index ->
            when (index) {
                visible -> 0
                visible + 1 -> 1
                visible - 1 -> 2
                else -> 3
            }
        }.thenBy { kotlin.math.abs(it - visible) },
    )
    return planVisualArtworkPrefetch(
        priorityOrder.asSequence().flatMap { index ->
            shelfUrls[index].asSequence().take(6).map { url ->
                VisualArtworkCandidate(
                    url = url,
                    priority = if (index == visible) VisualArtworkPriority.VISIBLE
                        else VisualArtworkPriority.NEARBY,
                    targetPx = targetPx,
                )
            }
        }.toList(),
        maxRequests = maxCandidates,
    )
}

/** At most three speculative decodes; requests use the global Coil loader,
 * its exact disk cache and network client. No extra decoder or second cache. */
object VisualArtworkPrefetch {
    private val gate = VisualArtworkFetchGate(maxConcurrent = 3)

    suspend fun warm(context: Context, candidates: List<VisualArtworkCandidate>) {
        val planned = planVisualArtworkPrefetch(candidates)
        if (planned.isEmpty()) return
        // Coil's first ImageLoader get can open its disk journal; never do
        // that synchronously during artist page composition on the main thread.
        val loader = withContext(Dispatchers.IO) {
            SingletonImageLoader.get(context)
        }
        coroutineScope {
            planned.forEach { candidate ->
                launch(Dispatchers.IO) {
                    val url = candidate.url ?: return@launch
                    try {
                        gate.fetchOnce(url + "@" + candidate.targetPx) {
                            val request = ImageRequest.Builder(context)
                                .data(url)
                                .size(candidate.targetPx, candidate.targetPx)
                                .build()
                            // A failed Coil result is not a warmed image:
                            // future visible screens must still be able to retry.
                            if (loader.execute(request) !is SuccessResult) {
                                error("Artwork warmup did not produce a decoded image")
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Speculative failures never replace the UI placeholder.
                    }
                }
            }
        }
    }
}
