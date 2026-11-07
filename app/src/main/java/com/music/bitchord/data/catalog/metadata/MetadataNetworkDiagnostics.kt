package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException

/**
 * Only bounded, low-cardinality metadata is emitted. Never record an MBID,
 * typed query, URL, account, token, response body, or stack trace here.
 * A real-device diagnostic capture can measure API response latency without
 * collecting the listener's catalog or playback history.
 */
internal fun metadataEndpointLabel(path: String): String =
    when (path.substringBefore('/').substringBefore('?')) {
        "artist" -> "artist"
        "release" -> "release"
        "release-group" -> "release_group"
        "recording" -> "recording"
        "work" -> "work"
        else -> "other"
    }

internal fun metadataHttpOutcome(status: Int?): String = when (status) {
    null -> "network_error"
    in 200..203, in 205..299 -> "ok"
    204 -> "empty"
    404 -> "not_found"
    429 -> "rate_limited"
    in 400..499 -> "request_error"
    in 500..599 -> "server_error"
    else -> "unexpected"
}

/** Retain only the numeric code, never a server body, URL, query or account. */
internal fun metadataHttpStatusLabel(status: Int?): String =
    status?.takeIf { it in 100..599 }?.toString() ?: "unknown"

/**
 * Instrument only real Ktor HTTP transports, not cache hits or parsers. The
 * result and cancellation contracts are unaffected when diagnostics is off.
 */
internal suspend fun <T> measureMetadataHttp(
    provider: String,
    endpoint: String,
    status: (T) -> Int?,
    call: suspend () -> T,
): T {
    val started = System.nanoTime()
    var outcome = "network_error"
    var httpStatus: Int? = null
    try {
        val result = call()
        httpStatus = status(result)
        outcome = metadataHttpOutcome(httpStatus)
        return result
    } catch (cancelled: CancellationException) {
        outcome = "cancelled"
        throw cancelled
    } finally {
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.APP,
                name = "metadata_http",
                metadata = mapOf(
                    "provider" to provider,
                    "endpoint" to endpoint,
                    "outcome" to outcome,
                    "httpStatus" to metadataHttpStatusLabel(httpStatus),
                ),
                durationNanos = elapsed,
            )
        }
    }
}
