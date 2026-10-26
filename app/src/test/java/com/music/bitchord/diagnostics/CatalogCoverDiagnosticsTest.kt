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
        meter.recordDirectFrontCandidate()
        meter.recordDirectFrontCandidate()
        meter.recordNetwork(200, 120L)
        meter.recordNetwork(503, 300L)
        meter.recordImageResult(true, 100L)
        meter.recordImageResult(false, 900L)
        val lines = CatalogCoverDiagnostics.lines(meter.snapshot()).joinToString("\n")
        assertEquals(true, lines.contains("p50_network_ms=120"))
        assertEquals(true, lines.contains("p95_network_ms=300"))
        assertEquals(true, lines.contains("cache_hits=1"))
        assertEquals(true, lines.contains("direct_front_candidates=2"))
        assertEquals(true, lines.contains("coil_load_success=1 coil_load_errors=1"))
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
