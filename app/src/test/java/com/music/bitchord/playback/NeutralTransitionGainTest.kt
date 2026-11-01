package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class NeutralTransitionGainTest {
    @Test
    fun `neutral endpoints do not change a playing track`() {
        val first = NeutralTransitionGain.fromFaders(0f, 1f)
        val second = NeutralTransitionGain.fromFaders(1f, 0f)
        assertEquals(0f, first.incoming, 0f)
        assertEquals(1f, first.outgoing, 0f)
        assertEquals(1f, second.incoming, 0f)
        assertEquals(0f, second.outgoing, 0f)
    }

    @Test
    fun `equal power overlap cannot sum two full scale tracks beyond unity`() {
        for (tick in 0..100) {
            val fraction = tick / 100.0
            val incoming = sin(fraction * Math.PI / 2).toFloat()
            val outgoing = cos(fraction * Math.PI / 2).toFloat()
            val gains = NeutralTransitionGain.fromFaders(incoming, outgoing)
            assertTrue("tick=$tick", gains.incoming + gains.outgoing <= 1.000001f)
            assertTrue("tick=$tick", gains.incoming >= 0f && gains.outgoing >= 0f)
        }
    }

    @Test
    fun `gains below unity are never modified`() {
        val result = NeutralTransitionGain.fromFaders(0.2f, 0.3f)
        assertEquals(0.2f, result.incoming, 0f)
        assertEquals(0.3f, result.outgoing, 0f)
    }

    @Test
    fun `overshooting dj faders are bounded without a limiter`() {
        val result = NeutralTransitionGain.fromFaders(1.5f, 0.8f)
        assertEquals(1f, result.incoming + result.outgoing, 1e-6f)
        assertTrue(result.incoming <= 1f && result.outgoing <= 1f)
    }

    @Test
    fun `aligned versions are not amplified at their swap midpoint`() {
        val gains = NeutralTransitionGain.fromFaders(0.70710677f, 0.70710677f)
        assertEquals(1f, gains.incoming + gains.outgoing, 1e-6f)
        assertEquals(gains.incoming, gains.outgoing, 1e-6f)
    }

    @Test
    fun `aborting a fade preserves both current gains without a sudden boost`() {
        for ((survivor, departing) in listOf(
            0.5f to 0.5f,
            0.2f to 0.8f,
            0.8f to 0.1f,
            1f to 0f,
        )) {
            val start = NeutralTransitionGain.recoverSingleTrack(survivor, departing, 0f)
            assertEquals(survivor, start.incoming, 1e-6f)
            assertEquals(departing, start.outgoing, 1e-6f)
            val end = NeutralTransitionGain.recoverSingleTrack(survivor, departing, 1f)
            assertEquals(1f, end.incoming, 0f)
            assertEquals(0f, end.outgoing, 0f)
        }
    }

    @Test
    fun `bail recovers survivor smoothly without exceeding unity overlap`() {
        for ((survivor, departing) in listOf(
            0.5f to 0.5f,
            0.2f to 0.8f,
            0.8f to 0.1f,
            1f to 0f,
        )) {
            var lastSurvivor = 0f
            var lastDeparting = 1f
            for (tick in 0..100) {
                val gains = NeutralTransitionGain.recoverSingleTrack(
                    survivor, departing, tick / 100f,
                )
                assertTrue("tick=$tick", gains.incoming + gains.outgoing <= 1.000001f)
                assertTrue("tick=$tick", gains.incoming + 1e-6f >= lastSurvivor)
                assertTrue("tick=$tick", gains.outgoing <= lastDeparting + 1e-6f)
                lastSurvivor = gains.incoming
                lastDeparting = gains.outgoing
            }
        }
    }

    @Test
    fun `bail ignores invalid progress and clamps to valid endpoints`() {
        val invalid = NeutralTransitionGain.recoverSingleTrack(0.4f, 0.6f, Float.NaN)
        assertEquals(0.4f, invalid.incoming, 1e-6f)
        assertEquals(0.6f, invalid.outgoing, 1e-6f)
        val beyond = NeutralTransitionGain.recoverSingleTrack(0.4f, 0.6f, 10f)
        assertEquals(1f, beyond.incoming, 0f)
        assertEquals(0f, beyond.outgoing, 0f)
    }

    @Test
    fun `nonfinite inputs cannot poison player volumes`() {
        val result = NeutralTransitionGain.fromFaders(Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(0f, result.incoming, 0f)
        assertEquals(0f, result.outgoing, 0f)
    }
}
