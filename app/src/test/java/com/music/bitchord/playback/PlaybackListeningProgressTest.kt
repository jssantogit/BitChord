package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackListeningProgressTest {
    @Test
    fun listenedDurationUsesOnlyRealAudioDeltasAndCannotBeInflatedBySeekPosition() {
        val progress = PlaybackListeningProgress()

        progress.observeAudioDelta(deltaMs = 5_000L, recordingDurationMs = 200_000L)
        // A seek changes player position, but position is intentionally not an
        // input to this accumulator. Only a later real-audio delta can add time.
        progress.observeAudioDelta(deltaMs = 0L, recordingDurationMs = 200_000L)

        assertEquals(5_000L, progress.listenedDurationMs)
        assertEquals(200_000L, progress.recordingDurationMs)
    }

    @Test
    fun pauseResumeIsAdditiveWithoutCountingPausedWallTime() {
        val progress = PlaybackListeningProgress()

        progress.observeAudioDelta(deltaMs = 6_000L, recordingDurationMs = 180_000L)
        // Pause: sampler emits no positive delta.
        progress.observeAudioDelta(deltaMs = 0L, recordingDurationMs = 180_000L)
        // Resume: only actual audio after the resume is added.
        progress.observeAudioDelta(deltaMs = 4_000L, recordingDurationMs = 180_000L)

        assertEquals(10_000L, progress.listenedDurationMs)
        assertEquals(180_000L, progress.recordingDurationMs)
    }
}
