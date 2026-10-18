package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceUiSignalsTest {

    @Test
    fun `feature state changes only on first owner entering and last owner leaving`() {
        val signals = RuntimePerformanceUiSignals()
        val first = Any()
        val second = Any()

        assertTrue(signals.dynamicBlurStateWouldChange(first, active = true))
        signals.setDynamicBlur(first, active = true)
        assertFalse(signals.dynamicBlurStateWouldChange(second, active = true))
        signals.setDynamicBlur(second, active = true)

        assertFalse(signals.dynamicBlurStateWouldChange(first, active = false))
        signals.setDynamicBlur(first, active = false)
        assertTrue(signals.dynamicBlurStateWouldChange(second, active = false))
        signals.setDynamicBlur(second, active = false)

        assertFalse(signals.snapshotAndResetCounters().dynamicBlurActive)
    }

    @Test
    fun `snapshot resets recomposition count but preserves active feature state`() {
        val signals = RuntimePerformanceUiSignals()
        val blurOwner = Any()
        val canvasOwner = Any()

        signals.setDynamicBlur(blurOwner, active = true)
        signals.setCanvasArtwork(canvasOwner, active = true)
        repeat(7) { signals.recordNowPlayingRecomposition() }

        val first = signals.snapshotAndResetCounters()
        val second = signals.snapshotAndResetCounters()

        assertTrue(first.dynamicBlurActive)
        assertTrue(first.canvasArtworkActive)
        assertEquals(7L, first.nowPlayingRecompositions)
        assertTrue(second.dynamicBlurActive)
        assertTrue(second.canvasArtworkActive)
        assertEquals(0L, second.nowPlayingRecompositions)
    }

    @Test
    fun `diagnostic metadata remains fixed and low cardinality`() {
        val signals = RuntimePerformanceUiSignals()
        signals.setDynamicBlur(Any(), active = true)
        signals.recordNowPlayingRecomposition()

        assertEquals(
            linkedMapOf(
                "dynamicBlurActive" to "true",
                "canvasArtworkActive" to "false",
                "nowPlayingRecompositions" to "1",
            ),
            signals.snapshotAndResetCounters().toDiagnosticMetadata(),
        )
    }
}
