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
    fun `severity uses fixed low cardinality buckets`() {
        val moderate = MainThreadStallState.severityFor(250_000_000L)
        val severe = MainThreadStallState.severityFor(500_000_000L)
        val critical = MainThreadStallState.severityFor(1_000_000_000L)

        assertEquals("moderate", moderate.wireName)
        assertEquals("severe", severe.wireName)
        assertEquals("critical", critical.wireName)
    }
}
