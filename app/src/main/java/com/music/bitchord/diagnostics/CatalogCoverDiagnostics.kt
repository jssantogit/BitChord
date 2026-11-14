package com.music.bitchord.diagnostics

import com.music.bitchord.data.catalog.metadata.CatalogCoverTelemetry
import com.music.bitchord.data.catalog.metadata.AppleArtworkProbeTelemetry

/**
 * Privacy-safe export bridge. CAA metadata HTTP outcomes and completed Coil
 * image loads are counted separately. Neither proves GPU drawing or correct
 * visual content.
 * No MBIDs, titles, URLs, device IDs, network peer names, or search queries.
 */
internal object CatalogCoverDiagnostics {
    fun lines(
        s: CatalogCoverTelemetry.Snapshot,
        apple: AppleArtworkProbeTelemetry.Snapshot =
            AppleArtworkProbeTelemetry.process.snapshot(),
    ): List<String> = buildList {
        add("scope=caa_metadata_and_coil_image_loads")
        add("window=since_last_capture_start_or_process_start")
        add("not_measured=gpu_draw_latency_wrong_cover_rate")
        add("image_scope=coil_load_caa_urls_only")
        // 1024px is an undocumented CDN rendition. A valid decoded bounds
        // response proves the dimensions offered by its URL, NOT that Coil
        // subsequently loaded or drew it. Keep those counters separate.
        add("apple_cdn_evidence_is_not_coil_render_success=true")
        add("apple_cdn_window=since_app_process_start")
        add("apple_cdn_1024 attempts=${apple.attempts1024}" +
            " dimension_verified=${apple.verified1024}")
        add("apple_cdn_720 attempts=${apple.attempts720}" +
            " dimension_verified=${apple.verified720}")
        add("apple_cdn_600 attempts=${apple.attempts600}" +
            " dimension_verified=${apple.verified600}")
        add("apple_cdn_cache_positive_hits=${apple.positiveCacheHits}" +
            " negative_hits=${apple.negativeCacheHits}")
        add("apple_cdn_network_samples=${apple.networkSampleCount}" +
            " apple_cdn_network_p50_ms=${apple.p50NetworkMs ?: "unknown"}" +
            " apple_cdn_network_p95_ms=${apple.p95NetworkMs ?: "unknown"}")
        add("apple_coil_load_success=${apple.coilSuccess}" +
            " errors=${apple.coilFailure}" +
            " cancelled=${apple.coilCancelled}")
        add("apple_coil_sources memory=${apple.coilMemory}" +
            " disk=${apple.coilDisk} network=${apple.coilNetwork}")
        add("apple_coil_p95_load_ms=${apple.coilP95Ms ?: "unknown"}")
        add("cache_hits=${s.positiveCacheHits} negative_cache_hits=${s.negativeCacheHits}" +
            " coalesced_requests=${s.coalescedRequests}")
        add("direct_front_candidates=${s.directFrontCandidates} source=legacy_front_presence_counter")
        // Separate the metadata-stage bottleneck from album-cache rehydration.
        // Only counts are exported; no artist, album, URL or MBID is retained.
        add("verified_group_batch_reads=${s.verifiedGroupCachedBatchReads}" +
            " verified_group_rows_reused=${s.verifiedGroupCachedRowsReused}")
        add("recording_edition_to_group_reconciliations=${s.reconciledRecordingEditionCovers}" +
            " rejected_group_art_downgrades=${s.rejectedGroupDowngrades}")
        add("recovered_join_owner_cancellations=${s.recoveredCoalescedOwnerCancellations}")
        add("network_requests=${s.networkRequests} success=${s.successfulNetworkRequests}" +
            " not_found=${s.networkNotFound} transient_failures=${s.transientFailures}")
        add("coil_load_success=${s.imageRenderSuccesses} coil_load_errors=${s.imageRenderFailures}")
        add("coil_load_started=${s.imageLoadStarts} coil_load_cancellations=${s.imageLoadCancels}")
        // Coil's actual result dataSource distinguishes bitmap cache reuse
        // from disk reads and network downloads, without recording URLs.
        add("coil_sources memory=${s.coilMemoryHits} disk=${s.coilDiskHits}" +
            " network=${s.coilNetworkFetches} other=${s.coilOtherSources}")
        // A new APK may preserve on-disk bytes even when this capture starts
        // before the ImageLoader has been created. Never interpret unknown as 0.
        add("coil_disk_bytes_on_loader_init=${s.coilDiskBytesAtInitialization ?: "unknown"}" +
            " coil_disk_capacity_bytes=${s.coilDiskCapacityBytes ?: "unknown"}" +
            " app_update_disk_retained=${s.coilDiskPreservedOnVersionUpgrade}")
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
