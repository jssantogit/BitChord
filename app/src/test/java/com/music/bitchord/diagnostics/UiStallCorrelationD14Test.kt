package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStallCorrelationD14Test {
    @Test fun latestFrameAggregateIsContextNotProofOfCausation() {
        val evidence = RecentUiWindowCorrelation(maxAgeNanos = 10_000_000_000L)
        evidence.record("now_playing", 120f, 8_333_333L, 200L, 180L, 1_000_000_000L)
        val metadata = evidence.at(2_000_000_000L)
        assertEquals("prior_window", metadata["frameEvidence"])
        assertEquals("unknown", metadata["causeStrength"])
        assertEquals("now_playing", metadata["frameContext"])
        assertEquals("120", metadata["frameRefreshHz"])
        assertEquals("180", metadata["frameOverBudget"])
        assertEquals("1000", metadata["frameAgeMs"])
        assertFalse(metadata.containsKey("rootCause"))
    }

    @Test fun staleAndMissingWindowsNeverProduceAFalseAttribution() {
        val evidence = RecentUiWindowCorrelation(maxAgeNanos = 1_000_000_000L)
        assertEquals("missing", evidence.at(1L)["frameEvidence"])
        evidence.record("now_playing", 120f, 8_333_333L, 10, 8, 1_000_000_000L)
        assertEquals("stale", evidence.at(3_000_000_000L)["frameEvidence"])
        assertFalse(evidence.at(3_000_000_000L).containsKey("frameContext"))
        assertEquals("clock_unknown", evidence.at(100L)["frameEvidence"])
    }

    @Test fun refreshBudgetsAndCardinalityAreBounded() {
        val evidence = RecentUiWindowCorrelation()
        evidence.record("arbitrary raw user title goes here", 60f, 16_666_667L, 30, 100, 20)
        val metadata = evidence.at(21)
        assertEquals("other", metadata["frameContext"])
        assertEquals("60", metadata["frameRefreshHz"])
        assertEquals("30", metadata["frameOverBudget"])
        assertTrue(metadata.values.none { it.contains("user title") })
    }
}
