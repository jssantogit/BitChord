package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleArtworkProbeTelemetry
import org.junit.Assert.*
import org.junit.Test

class AppleArtworkProbeTelemetryTest {
    @Test fun recordsRealCdnSizeResultsAndLatencyWithoutIdentities() {
        val meter = AppleArtworkProbeTelemetry()
        meter.recordNetwork(1024, true, 50)
        meter.recordNetwork(1024, false, 300)
        meter.recordNetwork(720, true, 150)
        meter.recordNetwork(600, true, 120)
        meter.recordCacheHit(valid = true)
        meter.recordCacheHit(valid = false)
        val snapshot = meter.snapshot()
        assertEquals(2, snapshot.attempts1024)
        assertEquals(1, snapshot.verified1024)
        assertEquals(1, snapshot.verified720)
        assertEquals(1, snapshot.verified600)
        assertEquals(1, snapshot.positiveCacheHits)
        assertEquals(1, snapshot.negativeCacheHits)
        assertEquals(4, snapshot.networkSampleCount)
        assertEquals(120L, snapshot.p50NetworkMs)
        assertEquals(300L, snapshot.p95NetworkMs)
        meter.reset()
        assertEquals(0, meter.snapshot().attempts1024)
        assertNull(meter.snapshot().p95NetworkMs)
    }

    @Test fun ignoresUnexpectedSizesAndBoundsLatency() {
        val meter = AppleArtworkProbeTelemetry(maxSamples = 2)
        meter.recordNetwork(2048, true, 1)
        meter.recordNetwork(1024, true, -10)
        meter.recordNetwork(1024, false, 1200)
        meter.recordNetwork(1024, true, 500)
        val result = meter.snapshot()
        assertEquals(3, result.attempts1024)
        assertEquals(2, result.verified1024)
        assertEquals(2, result.networkSampleCount)
        assertEquals(500L, result.p50NetworkMs)
        assertEquals(1200L, result.p95NetworkMs)
    }
}
