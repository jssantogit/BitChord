package com.music.bitchord.playback

import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the actual Android choice of the neutral Automix planner. */
class NeutralAutomixPlannerTest {
    private fun analyzed(id: String, bpm: Double): TrackAnalysis {
        val interval = 60.0 / bpm
        val beats = generateSequence(0.5) { it + 4 * interval }
            .takeWhile { it < 190.0 }
            .toList()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 200.0,
            bpm = bpm,
            beatInterval = interval,
            beatConfidence = 0.9,
            downbeats = beats,
            firstBeat = 0.5,
            audibleStartTime = 0.5,
            contentEndTime = 198.0,
            mixInTime = beats[4],
        )
    }

    @Test
    fun `Android Automix keeps analysis timing without a hidden speed change`() {
        val outgoing = analyzed("a", 120.0)
        val incoming = analyzed("b", 130.0)
        val neutral = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = false,
            neutralPlayback = true,
        )
        assertFalse(neutral.blocked)
        assertTrue(neutral.fadeSeconds > 0.0)
        assertEquals(1.0, neutral.incomingPlaybackRate, 0.0)
        assertEquals(1.0, neutral.outgoingPlaybackRate, 0.0)
        assertEquals(TransitionStyle.EQUAL_POWER, neutral.transitionStyle)
        assertFalse(neutral.bassSwap)
        assertEquals(0.0, neutral.filterSweep, 0.0)
        assertEquals(0.0, neutral.echoSeconds, 0.0)
    }

    @Test
    fun `advanced neutral Automix keeps source speed and effect outputs inactive`() {
        val outgoing = analyzed("advanced-a", 120.0)
        val incoming = analyzed("advanced-b", 129.0)
        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = TransitionTrackInfo("advanced-a", 200_000),
            nextTrack = TransitionTrackInfo("advanced-b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
            neutralPlayback = true,
        )
        assertFalse(plan.blocked)
        assertTrue(plan.fadeSeconds > 0)
        assertEquals(1.0, plan.incomingPlaybackRate, 0.0)
        assertEquals(1.0, plan.outgoingPlaybackRate, 0.0)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertFalse(plan.bassSwap)
        assertFalse(plan.phaseLock)
        assertEquals(0.0, plan.filterSweep, 0.0)
        assertEquals(0.0, plan.echoSeconds, 0.0)
    }

    @Test
    fun `regular shared planner is unchanged for non Android consumers`() {
        val outgoing = analyzed("a", 120.0)
        val incoming = analyzed("b", 130.0)
        val regular = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = false,
        )
        assertTrue("Legacy rate decision must remain unchanged", regular.incomingPlaybackRate < 1.0)
    }
}
