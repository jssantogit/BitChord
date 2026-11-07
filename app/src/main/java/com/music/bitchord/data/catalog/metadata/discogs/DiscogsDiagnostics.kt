package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics

/**
 * Stable, bounded diagnostics for the personal release-evidence path.
 * Never include release IDs, API URLs, artists, track names, tokens,
 * payloads or other user/context data in the event metadata.
 */
internal object DiscogsDiagnosticStatus {
    fun of(result: DiscogsEditionEnrichment): String = when (result) {
        is DiscogsEditionEnrichment.Verified -> "verified"
        DiscogsEditionEnrichment.Disabled -> "disabled"
        DiscogsEditionEnrichment.NoLink -> "no_link"
        DiscogsEditionEnrichment.Ambiguous -> "ambiguous"
        DiscogsEditionEnrichment.Candidate -> "candidate"
        DiscogsEditionEnrichment.Conflict -> "conflict"
        DiscogsEditionEnrichment.Invalid -> "invalid"
        DiscogsEditionEnrichment.Unavailable -> "unavailable"
    }

    fun forHttp(code: Int): String = when (code) {
        200 -> "ok"
        401, 403 -> "auth_required"
        404 -> "missing"
        429 -> "throttled"
        in 500..599 -> "server_error"
        else -> "other_http"
    }

    fun releaseResult(result: DiscogsEditionEnrichment) {
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "discogs_edition_evidence_v1",
                metadata = mapOf("result" to of(result)),
            )
        }
    }

    fun httpResult(kind: DiscogsKind, status: Int) {
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "discogs_api_http_v1",
                metadata = mapOf(
                    "kind" to kind.name,
                    "status" to forHttp(status),
                ),
            )
        }
    }
}
