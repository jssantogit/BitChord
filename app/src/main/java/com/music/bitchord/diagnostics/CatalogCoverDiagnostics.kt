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
        add("not_measured=gpu_draw_latency_wrong_cover_rate")
        add("image_scope=coil_load_caa_urls_only")
        add("cache_hits=${s.positiveCacheHits} negative_cache_hits=${s.negativeCacheHits}" +
            " coalesced_requests=${s.coalescedRequests}")
        add("direct_front_candidates=${s.directFrontCandidates} source=legacy_front_presence_counter")
        // Separate the metadata-stage bottleneck from album-cache rehydration.
        // Only counts are exported; no artist, album, URL or MBID is retained.
        add("artist_cache_batch_reads=${s.artistCachedBatchReads}" +
            " artist_rows_reused=${s.artistCachedRowsReused}")
        add("recovered_join_owner_cancellations=${s.recoveredCoalescedOwnerCancellations}")
        add("network_requests=${s.networkRequests} success=${s.successfulNetworkRequests}" +
            " not_found=${s.networkNotFound} transient_failures=${s.transientFailures}")
        add("coil_load_success=${s.imageRenderSuccesses} coil_load_errors=${s.imageRenderFailures}")
        // Coil's actual result dataSource distinguishes bitmap cache reuse
        // from disk reads and network downloads, without recording URLs.
        add("coil_sources memory=${s.coilMemoryHits} disk=${s.coilDiskHits}" +
            " network=${s.coilNetworkFetches} other=${s.coilOtherSources}")
        if (s.imageLoadSampleCount > 0 &&
            s.p50ImageLoadMs != null && s.p95ImageLoadMs != null
        ) {
            add("p50_coil_load_ms=${s.p50ImageLoadMs} p95_coil_load_ms=${s.p95ImageLoadMs}" +
                " samples=${s.imageLoadSampleCount}")
        } else {
            add("coil_percentiles=insufficient_samples")
        }
        if (s.latencySampleCount > 0 && s.p50NetworkMs != null && s.p95NetworkMs != null) {
            add("p50_network_ms=${s.p50NetworkMs} p95_network_ms=${s.p95NetworkMs}" +
                " samples=${s.latencySampleCount}")
        } else {
            add("percentiles=insufficient_samples")
        }
    }
}
