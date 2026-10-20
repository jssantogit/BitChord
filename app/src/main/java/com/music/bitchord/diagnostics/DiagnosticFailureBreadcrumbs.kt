package com.music.bitchord.diagnostics

/**
 * Strictly observational, low-cardinality diagnostic breadcrumbs. Deliberately
 * accepts no free text, URLs, exception messages, tokens or media IDs.
 */
enum class DiagnosticFailureReason(val wireName: String) {
    PROVIDER_SNAPSHOT_FAILED("provider_snapshot_failed"),
    CATALOG_APPLY_FAILED("catalog_apply_failed"),
    ARTWORK_CACHE_READ_FAILED("artwork_cache_read_failed"),
    ARTWORK_CACHE_WRITE_FAILED("artwork_cache_write_failed"),
    ARTWORK_TRANSPORT_EXCEPTION("artwork_transport_exception"),
    ARTWORK_NETWORK_UNAVAILABLE("artwork_network_unavailable"),
    ARTWORK_AUTH_REJECTED("artwork_auth_rejected"),
    ARTWORK_REQUEST_REJECTED("artwork_request_rejected"),
    NETWORK_TIMEOUT("network_timeout"),
    NETWORK_OFFLINE("network_offline"),
    SOURCE_FALLBACK("source_fallback"),
}

enum class DiagnosticRecoveryOutcome(val wireName: String) {
    RETRYING("retrying"),
    FALLBACK("fallback"),
    DEGRADED("degraded"),
    RECOVERED("recovered"),
    FAILED("failed"),
    SKIPPED("skipped"),
}

data class DiagnosticFailureBreadcrumb(
    val category: DiagnosticCategory,
    val reason: DiagnosticFailureReason,
    val outcome: DiagnosticRecoveryOutcome,
)

fun interface DiagnosticFailureSink {
    fun record(breadcrumb: DiagnosticFailureBreadcrumb)

    companion object {
        val NO_OP = DiagnosticFailureSink { }
    }
}

/**
 * Installed only as a local diagnostics adapter; product error handling must
 * be identical even if Diagnostics fails. No network telemetry or unbounded
 * event buffering is added by this seam.
 */
object DiagnosticFailureBreadcrumbs {
    @Volatile
    var sink: DiagnosticFailureSink = DiagnosticFailureSink.NO_OP

    fun record(
        category: DiagnosticCategory,
        reason: DiagnosticFailureReason,
        outcome: DiagnosticRecoveryOutcome,
    ) {
        try {
            sink.record(DiagnosticFailureBreadcrumb(category, reason, outcome))
        } catch (_: Throwable) {
            // Observability must not alter the source/catalog/playback outcome.
        }
    }
}
