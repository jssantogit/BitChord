package com.music.bitchord.diagnostics

import com.music.bitchord.data.catalog.metadata.CatalogCoverTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatalogCoverDiagnosticsTest {
    @Test fun exportNamesNetworkOnlyEvidenceAndShowsUnknownForNoSamples() {
        val telemetry = CatalogCoverTelemetry()
        val lines = CatalogCoverDiagnostics.lines(telemetry.snapshot())
        assertEquals("scope=caa_metadata_http_only", lines.first())
        assertEquals("percentiles=insufficient_samples", lines.last())
        assertFalse(lines.joinToString().contains("null"))
    }

    @Test fun exportCountsNoIdentityAndNewCaptureResetsItsCounters() {
        val meter = CatalogCoverTelemetry()
        meter.recordCacheHit()
        meter.recordNegativeCacheHit()
        meter.recordJoinedRequest()
        meter.recordVerifiedGroupCachedBatch(5)
        meter.recordRecoveredCoalescedOwnerCancellation()
        meter.recordDirectFrontCandidate()
        meter.recordDirectFrontCandidate()
        meter.recordNetwork(200, 120L)
        meter.recordNetwork(503, 300L)
        meter.recordImageResult(true, 100L, "DISK")
        meter.recordImageResult(false, 900L)
        meter.recordCoilDiskCacheInitialization(1024L, 100L * 1024 * 1024)
        meter.recordImageDiskCachePreservedOnUpgrade()
        val lines = CatalogCoverDiagnostics.lines(meter.snapshot()).joinToString("\n")
        assertEquals(true, lines.contains("p50_network_ms=120"))
        assertEquals(true, lines.contains("p95_network_ms=300"))
        assertEquals(true, lines.contains("cache_hits=1"))
        assertEquals(true, lines.contains("verified_group_batch_reads=1 verified_group_rows_reused=5"))
        assertEquals(true, lines.contains("recovered_join_owner_cancellations=1"))
        assertEquals(true, lines.contains("direct_front_candidates=2"))
        assertEquals(true, lines.contains("coil_load_success=1 coil_load_errors=1"))
        assertEquals(true, lines.contains("coil_sources memory=0 disk=1 network=0 other=0"))
        assertEquals(true, lines.contains(
            "coil_disk_bytes_on_loader_init=1024 coil_disk_capacity_bytes=104857600 app_update_disk_retained=true",
        ))
        assertEquals(true, lines.contains("p50_coil_load_ms=100 p95_coil_load_ms=900"))
        assertFalse(lines.contains("musicbrainz"))
        meter.reset()
        val fresh = meter.snapshot()
        assertEquals(0, fresh.networkRequests)
        assertEquals(0, fresh.directFrontCandidates)
        assertEquals(0, fresh.positiveCacheHits)
        assertEquals(null, fresh.p95NetworkMs)
    }
}
