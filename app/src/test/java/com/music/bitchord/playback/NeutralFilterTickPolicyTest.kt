package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralFilterTickPolicyTest {
    @Test
    fun `neutral Android crossfade does not calculate a nonexistent filter ride`() {
        assertFalse(NeutralFilterTickPolicy.shouldCompute(TransitionFilters.None))
    }

    @Test
    fun `legacy filter ports still receive predictive lead and style updates`() {
        val legacy = object : TransitionFilters {
            override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
            override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
        }
        assertTrue(NeutralFilterTickPolicy.shouldCompute(legacy))
    }
}
