package com.music.bitchord

import com.music.bitchord.ui.canonicalLoadLatencyBucket
import com.music.bitchord.ui.canonicalLoadCountBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalLoadTimingTest {
    @Test fun latencyBucketsSeparateFastFirstPaintFromSlowNetworkWaits() {
        assertEquals("under_250ms", canonicalLoadLatencyBucket(249))
        assertEquals("250_999ms", canonicalLoadLatencyBucket(999))
        assertEquals("1_3s", canonicalLoadLatencyBucket(1_100))
        assertEquals("3_8s", canonicalLoadLatencyBucket(3_001))
        assertEquals("over_8s", canonicalLoadLatencyBucket(10_000))
    }

    @Test fun countBucketsDoNotExposeCatalogIdentifiers() {
        assertEquals("zero", canonicalLoadCountBucket(0))
        assertEquals("1_5", canonicalLoadCountBucket(3))
        assertEquals("6_25", canonicalLoadCountBucket(25))
        assertEquals("over_25", canonicalLoadCountBucket(100))
    }
}
