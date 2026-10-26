package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CatalogCoverTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogCoverTelemetryTest {
    @Test fun boundedPrivacySafeLatencySummaryCountsCacheMissAndUnavailable() {
        val meter = CatalogCoverTelemetry(maxSamples = 4)
        meter.recordCacheHit()
        meter.recordCacheHit()
        meter.recordNegativeCacheHit()
        meter.recordJoinedRequest()
        meter.recordNetwork(200, 100)
        meter.recordNetwork(404, 200)
        meter.recordNetwork(503, 300)
        meter.recordNetwork(-1, 400)
        meter.recordNetwork(200, 500)
        val stats = meter.snapshot()
        assertEquals(2, stats.positiveCacheHits)
        assertEquals(1, stats.negativeCacheHits)
        assertEquals(1, stats.coalescedRequests)
        assertEquals(5, stats.networkRequests)
        assertEquals(2, stats.successfulNetworkRequests)
        assertEquals(1, stats.networkNotFound)
        assertEquals(2, stats.transientFailures)
        assertEquals(300L, stats.p50NetworkMs)
        assertEquals(500L, stats.p95NetworkMs)
        assertEquals(4, stats.latencySampleCount)
    }

    @Test fun imageLoadLatencyIsReportedIndependentlyFromCaaMetadataRtt() {
        val meter = CatalogCoverTelemetry()
        meter.recordImageResult(true, 410L)
        meter.recordImageResult(false, 1600L)
        meter.recordImageResult(true, 950L)
        val snapshot = meter.snapshot()
        assertEquals(2, snapshot.imageRenderSuccesses)
        assertEquals(1, snapshot.imageRenderFailures)
        assertEquals(410L, snapshot.p50ImageLoadMs)
        assertEquals(1600L, snapshot.p95ImageLoadMs)
        assertEquals(0, snapshot.networkRequests)
        meter.reset()
        assertEquals(0, meter.snapshot().imageRenderSuccesses)
        assertNull(meter.snapshot().p95ImageLoadMs)
    }

    @Test fun emptyTelemetryHasNoFakeP95AndDoesNotRecordIdentity() {
        val meter = CatalogCoverTelemetry()
        val stats = meter.snapshot()
        assertEquals(0, stats.networkRequests)
        assertNull(stats.p50NetworkMs)
        assertNull(stats.p95NetworkMs)
    }
}
