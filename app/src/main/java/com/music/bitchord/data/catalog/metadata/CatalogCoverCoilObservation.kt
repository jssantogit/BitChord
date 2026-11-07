package com.music.bitchord.data.catalog.metadata

import android.os.SystemClock
import coil3.EventListener
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.net.URI

/**
 * Observe bitmap loading for trusted Cover Art Archive image requests, not
 * the metadata JSON request that merely announces an image URL.
 *
 * Avoid accepting a string containing the host in a query or a hostile URL.
 * Never store the image URL, recording ID, title or other identity in telemetry.
 */
internal fun shouldObserveCaaImageRequest(data: Any?): Boolean =
    isCoverArchiveImageHost(when (data) {
        is String -> data
        is URI -> data.toString()
        else -> null
    })

/**
 * Per-request Coil observer. A successful JSON metadata lookup cannot mark a
 * bitmap successful; only the Coil result callback can. Cancelled offscreen
 * requests are intentionally not classified as failures.
 *
 * This measures decoded image requests, not GPU presentation/pixel accuracy.
 */
internal class CatalogCoverCoilEventListenerFactory(
    private val telemetry: CatalogCoverTelemetry = CatalogCoverTelemetry.process,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : EventListener.Factory {
    override fun create(request: ImageRequest): EventListener {
        if (!shouldObserveCaaImageRequest(request.data)) return EventListener.NONE
        return object : EventListener() {
            private var startedAt: Long? = null

            override fun onStart(request: ImageRequest) {
                startedAt = elapsedMs()
            }

            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                record(success = true, source = result.dataSource.name)
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                record(success = false)
            }

            private fun record(success: Boolean, source: String? = null) {
                val start = startedAt ?: return
                startedAt = null
                telemetry.recordImageResult(
                    success = success,
                    durationMs = (elapsedMs() - start).coerceAtLeast(0L),
                    source = source,
                )
            }
        }
    }
}
