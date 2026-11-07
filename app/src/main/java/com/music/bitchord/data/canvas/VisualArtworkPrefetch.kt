package com.music.bitchord.data.canvas

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
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
        val url = candidate.url?.takeIf(::isSafeVisualMediaUrl)
            ?: return@mapNotNull null
        candidate.copy(url = url, targetPx = candidate.targetPx.coerceIn(96, 1200))
    }
    .sortedWith(compareBy<VisualArtworkCandidate> { it.priority.ordinal }
        .thenByDescending { it.targetPx })
    .distinctBy { it.url }
    .take(maxRequests.coerceIn(0, 24))
    .toList()

/** At most three speculative decodes; requests use the global Coil loader,
 * its exact disk cache and network client. No extra decoder or second cache. */
object VisualArtworkPrefetch {
    suspend fun warm(context: Context, candidates: List<VisualArtworkCandidate>) {
        val planned = planVisualArtworkPrefetch(candidates)
        if (planned.isEmpty()) return
        // Coil's first ImageLoader get can open its disk journal; never do
        // that synchronously during artist page composition on the main thread.
        val loader = withContext(Dispatchers.IO) {
            SingletonImageLoader.get(context)
        }
        val limit = Semaphore(3)
        coroutineScope {
            planned.forEach { candidate ->
                launch(Dispatchers.IO) {
                    limit.withPermit {
                        val request = ImageRequest.Builder(context)
                            .data(candidate.url)
                            .size(candidate.targetPx, candidate.targetPx)
                            .build()
                        try {
                            loader.execute(request)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A speculative image can fail without affecting
                            // the native shelf's existing placeholder.
                        }
                    }
                }
            }
        }
    }
}
