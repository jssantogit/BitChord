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

/** Apple CDN covers count separately from CAA. Store badge images are excluded. */
internal fun shouldObserveAppleImageRequest(data: Any?): Boolean =
    safeAppleArtwork(when (data) {
        is String -> data
        is URI -> data.toString()
        else -> null
    }) != null

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
    private val appleTelemetry: AppleArtworkProbeTelemetry = AppleArtworkProbeTelemetry.process,
) : EventListener.Factory {
    override fun create(request: ImageRequest): EventListener {
        val archive = shouldObserveCaaImageRequest(request.data)
        val apple = shouldObserveAppleImageRequest(request.data)
        if (!archive && !apple) return EventListener.NONE
        return object : EventListener() {
            private var startedAt: Long? = null

            override fun onStart(request: ImageRequest) {
                startedAt = elapsedMs()
                if (archive) telemetry.recordImageStart()
            }

            override fun onCancel(request: ImageRequest) {
                if (startedAt != null) {
                    startedAt = null
                    if (archive) telemetry.recordImageCancellation()
                    if (apple) appleTelemetry.recordCoilImageCancellation()
                }
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
                val duration = (elapsedMs() - start).coerceAtLeast(0L)
                if (archive) telemetry.recordImageResult(
                    success = success, durationMs = duration, source = source,
                )
                if (apple) appleTelemetry.recordCoilImageResult(
                    success = success, durationMs = duration, source = source,
                )
            }
        }
    }
}
