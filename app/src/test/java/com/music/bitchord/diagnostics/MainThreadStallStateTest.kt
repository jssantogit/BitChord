package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainThreadStallStateTest {
    @Test
    fun `delay below threshold never emits a stall`() {
        val state = MainThreadStallState(thresholdNanos = 250_000_000L)

        assertNull(state.sample(delayNanos = 20_000_000L))
        assertNull(state.sample(delayNanos = 249_000_000L))
    }

    @Test
    fun `one continuous stall emits once on recovery with worst observed delay`() {
        val state = MainThreadStallState(thresholdNanos = 250_000_000L)

        assertNull(state.sample(300_000_000L))
        assertNull(state.sample(650_000_000L))
        assertNull(state.sample(540_000_000L))

        val recovered = state.sample(30_000_000L)
        assertEquals(650_000_000L, recovered?.durationNanos)
        assertEquals(MainThreadStallSeverity.SEVERE, recovered?.severity)
        assertNull(state.sample(20_000_000L))
    }

    @Test
    fun `recovery rearms detector for a later independent stall`() {
        val state = MainThreadStallState(thresholdNanos = 250_000_000L)

        state.sample(300_000_000L)
        val first = state.sample(10_000_000L)
        state.sample(1_500_000_000L)
        val second = state.sample(10_000_000L)

        assertEquals(MainThreadStallSeverity.MODERATE, first?.severity)
        assertEquals(MainThreadStallSeverity.CRITICAL, second?.severity)
    }

    @Test
    fun `repeated moderate stalls are coalesced without losing worst latency`() {
        val gate=MainThreadStallEventLimiter(moderateCooldownNanos=10_000_000_000L)
        fun observation(ms:Long)=MainThreadStallObservation(
            ms*1_000_000L,MainThreadStallSeverity.MODERATE)
        val first=gate.accept(0L,observation(301))
        assertEquals(301_000_000L,first?.observation?.durationNanos)
        assertEquals(0,first?.suppressedModerates)
        assertNull(gate.accept(800_000_000L,observation(401)))
        assertNull(gate.accept(9_999_999_999L,observation(351)))
        val aggregated=gate.accept(10_000_000_000L,observation(301))
        assertEquals(401_000_000L,aggregated?.observation?.durationNanos)
        assertEquals(2,aggregated?.suppressedModerates)
        assertNull(gate.accept(10_100_000_000L,observation(302)))
    }

    @Test
    fun `severe and critical stalls bypass moderation with independent counts`() {
        val gate=MainThreadStallEventLimiter(moderateCooldownNanos=10_000_000_000L)
        val moderate=MainThreadStallObservation(
            300_000_000L,MainThreadStallSeverity.MODERATE)
        gate.accept(0L,moderate)
        assertNull(gate.accept(200_000_000L,moderate))
        val severe=gate.accept(300_000_000L,
            MainThreadStallObservation(600_000_000L,MainThreadStallSeverity.SEVERE))
        assertEquals(MainThreadStallSeverity.SEVERE,severe?.observation?.severity)
        assertEquals(1,severe?.suppressedModerates)
        val critical=gate.accept(400_000_000L,
            MainThreadStallObservation(1_100_000_000L,MainThreadStallSeverity.CRITICAL))
        assertEquals(MainThreadStallSeverity.CRITICAL,critical?.observation?.severity)
        assertEquals(0,critical?.suppressedModerates)
        assertNull(gate.accept(500_000_000L,moderate))
        assertEquals(1,gate.accept(10_400_000_000L,moderate)?.suppressedModerates)
    }

    @Test
    fun `severity uses fixed low cardinality buckets`() {
        val moderate = MainThreadStallState.severityFor(250_000_000L)
        val severe = MainThreadStallState.severityFor(500_000_000L)
        val critical = MainThreadStallState.severityFor(1_000_000_000L)

        assertEquals("moderate", moderate.wireName)
        assertEquals("severe", severe.wireName)
        assertEquals("critical", critical.wireName)
    }
}
