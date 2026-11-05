package com.music.bitchord

import com.music.bitchord.data.scrobbling.ListenBrainzPlaybackClock
import com.music.bitchord.data.scrobbling.listenBrainzQualifiesAsListen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzScrobblePolicyTest {
    @Test fun pauseAndResumeAccumulateOnlyAudibleWallTime() {
        val clock = ListenBrainzPlaybackClock()
        clock.onPlay(1_000L)
        clock.onPause(21_000L) // 20 seconds of audio
        assertEquals(20_000L, clock.listenedMs(600_000L))
        clock.onPlay(600_000L)
        clock.onPause(625_000L) // 25 seconds more; paused time is excluded
        assertEquals(45_000L, clock.listenedMs(625_000L))
        assertFalse(listenBrainzQualifiesAsListen(clock.listenedMs(625_000L), 120_000L))
        clock.onPlay(630_000L)
        assertTrue(listenBrainzQualifiesAsListen(clock.listenedMs(645_000L), 120_000L))
        clock.onPause(645_000L)
        clock.onPause(900_000L) // duplicate callbacks cannot double count
        assertEquals(60_000L, clock.listenedMs(900_000L))
    }

    @Test fun durationThresholdIsHalfTheSongOrFourMinutesWhicheverIsLower() {
        assertFalse(listenBrainzQualifiesAsListen(59_999L, 120_000L))
        assertTrue(listenBrainzQualifiesAsListen(60_000L, 120_000L))
        assertFalse(listenBrainzQualifiesAsListen(239_999L, 900_000L))
        assertTrue(listenBrainzQualifiesAsListen(240_000L, 900_000L))
        assertFalse(listenBrainzQualifiesAsListen(239_999L, null))
        assertTrue(listenBrainzQualifiesAsListen(240_000L, null))
        assertFalse(listenBrainzQualifiesAsListen(-1L, 120_000L))
        assertFalse(listenBrainzQualifiesAsListen(0L, 0L))
    }

    @Test fun aRepeatedTrackStartsANewIndependentListen() {
        val clock = ListenBrainzPlaybackClock()
        clock.onPlay(1_000L)
        assertEquals(70_000L, clock.listenedMs(71_000L))
        clock.reset()
        clock.onPlay(72_000L)
        assertEquals(8_000L, clock.listenedMs(80_000L))
        assertFalse(listenBrainzQualifiesAsListen(clock.listenedMs(80_000L), 120_000L))
    }

    @Test fun negativeOrOutOfOrderMonotonicTicksCannotFakeListeningTime() {
        val clock = ListenBrainzPlaybackClock()
        clock.onPlay(10_000L)
        clock.onPause(9_000L)
        assertEquals(0L, clock.listenedMs(11_000L))
        clock.onPlay(11_000L)
        assertEquals(0L, clock.listenedMs(10_000L))
        clock.onPause(21_000L)
        assertEquals(10_000L, clock.listenedMs(21_000L))
    }
}
