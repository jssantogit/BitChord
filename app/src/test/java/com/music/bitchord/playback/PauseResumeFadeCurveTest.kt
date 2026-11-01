package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseResumeFadeCurveTest {
    @Test fun `fade out starts at unity and ends in silence`() {
        assertEquals(1f, PauseResumeFadeCurve.gain(1f, 0f, 0, 200), 0.00001f)
        assertEquals(0.5f, PauseResumeFadeCurve.gain(1f, 0f, 100, 200), 0.00001f)
        assertEquals(0f, PauseResumeFadeCurve.gain(1f, 0f, 200, 200), 0.00001f)
    }

    @Test fun `fade in starts silent and reaches unity`() {
        assertEquals(0f, PauseResumeFadeCurve.gain(0f, 1f, 0, 200), 0.00001f)
        assertEquals(0.5f, PauseResumeFadeCurve.gain(0f, 1f, 100, 200), 0.00001f)
        assertEquals(1f, PauseResumeFadeCurve.gain(0f, 1f, 500, 200), 0.00001f)
    }

    @Test fun `rapid reversal starts at current gain without a discontinuity`() {
        val halfway = PauseResumeFadeCurve.gain(1f, 0f, 75, 200)
        assertEquals(halfway, PauseResumeFadeCurve.gain(halfway, 1f, 0, 200), 0.00001f)
        assertTrue(PauseResumeFadeCurve.gain(halfway, 1f, 100, 200) > halfway)
    }

    @Test fun `fade cannot amplify or escape zero to one`() {
        for (millisecond in 0L..700L step 7) {
            val gain = PauseResumeFadeCurve.gain(1f, 0f, millisecond, 200)
            assertTrue(gain >= 0f && gain <= 1f)
        }
        assertEquals(0f, PauseResumeFadeCurve.gain(1f, 0f, 10, 0), 0f)
        assertEquals(1f, PauseResumeFadeCurve.gain(Float.NaN, Float.POSITIVE_INFINITY, 100, 200), 0f)
    }

    @Test fun `duration is bounded and disabled at zero`() {
        assertEquals(0, PauseResumeFadeCurve.normalizeDuration(-50))
        assertEquals(200, PauseResumeFadeCurve.normalizeDuration(200))
        assertEquals(600, PauseResumeFadeCurve.normalizeDuration(900))
    }
}
