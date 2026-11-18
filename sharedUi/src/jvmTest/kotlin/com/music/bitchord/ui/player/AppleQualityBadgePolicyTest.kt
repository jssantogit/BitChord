package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleQualityBadgePolicyTest {
    @Test fun losslessAndAtmosAreNeverInferredFromRequestedQuality() {
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(null, false, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.DOLBY_ATMOS, appleQualityBadgeKind(true, false, true, false, false, false))
        assertEquals(AppleQualityBadgeKind.HI_RES, appleQualityBadgeKind(true, true, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.LOSSLESS, appleQualityBadgeKind(true, false, false, false, false, false))
    }

    @Test fun measuredLossyIsDescribedWithoutClaimingLossless() {
        assertEquals(AppleQualityBadgeKind.HIGH, appleQualityBadgeKind(false, false, false, true, false, false))
        assertEquals(AppleQualityBadgeKind.MEDIUM, appleQualityBadgeKind(false, false, false, false, true, false))
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(false, false, false, false, false, false))
    }

    @Test fun unavailableStatsRemainsUnknownRatherThanInventingQuality() {
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(null, false, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.LOADING, appleQualityBadgeKind(null, false, false, false, false, true))
    }
}
