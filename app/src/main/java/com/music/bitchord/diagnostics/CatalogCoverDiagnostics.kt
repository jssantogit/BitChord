package com.music.bitchord.diagnostics

import com.music.bitchord.data.catalog.metadata.CatalogCoverTelemetry

/**
 * Conservative export bridge. Counts describe ONLY Cover Art Archive metadata
 * requests, never completed Coil bitmap rendering or verified visual quality.
 * No MBIDs, titles, URLs, device IDs, network peer names, or search queries.
 */
internal object CatalogCoverDiagnostics {
    fun lines(s: CatalogCoverTelemetry.Snapshot): List<String> = buildList {
        add("scope=caa_metadata_http_only")
        add("window=since_last_capture_start_or_process_start")
        add("not_measured=bitmap_render_duration_wrong_cover_rate")
        add("cache_hits=${s.positiveCacheHits} negative_cache_hits=${s.negativeCacheHits}" +
            " coalesced_requests=${s.coalescedRequests}")
        add("direct_front_candidates=${s.directFrontCandidates} source=musicbrainz_verified_release_front")
        add("network_requests=${s.networkRequests} success=${s.successfulNetworkRequests}" +
            " not_found=${s.networkNotFound} transient_failures=${s.transientFailures}")
        if (s.latencySampleCount > 0 && s.p50NetworkMs != null && s.p95NetworkMs != null) {
            add("p50_network_ms=${s.p50NetworkMs} p95_network_ms=${s.p95NetworkMs}" +
                " samples=${s.latencySampleCount}")
        } else {
            add("percentiles=insufficient_samples")
        }
    }
}
