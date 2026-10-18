package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningOutcomeClassifierTest {
    private val classifier = ListeningOutcomeClassifier()

    @Test
    fun naturalEndIsCompletion() {
        assertEquals(
            ListeningEventType.PLAYBACK_COMPLETED,
            classifier.classify(
                listenedDurationMs = 42_000,
                recordingDurationMs = 180_000,
                endCause = PlaybackEndCause.ENDED,
            ),
        )
    }

    @Test
    fun explicitSkipAfterNinetyPercentStillCountsAsCompletion() {
        assertEquals(
            ListeningEventType.PLAYBACK_COMPLETED,
            classifier.classify(
                listenedDurationMs = 180_000,
                recordingDurationMs = 200_000,
                endCause = PlaybackEndCause.NEXT,
            ),
        )
    }

    @Test
    fun explicitSkipBeforeQuarterIsEarlySkip() {
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_EARLY,
            classifier.classify(
                listenedDurationMs = 40_000,
                recordingDurationMs = 200_000,
                endCause = PlaybackEndCause.NEXT,
            ),
        )
    }

    @Test
    fun explicitSkipBetweenQuarterAndCompletionThresholdIsLateSkip() {
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_LATE,
            classifier.classify(
                listenedDurationMs = 100_000,
                recordingDurationMs = 200_000,
                endCause = PlaybackEndCause.PREVIOUS,
            ),
        )
    }

    @Test
    fun unknownDurationUsesAbsoluteEarlySkipFallback() {
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_EARLY,
            classifier.classify(
                listenedDurationMs = 20_000,
                recordingDurationMs = null,
                endCause = PlaybackEndCause.MANUAL_CHANGE,
            ),
        )
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_LATE,
            classifier.classify(
                listenedDurationMs = 45_000,
                recordingDurationMs = null,
                endCause = PlaybackEndCause.MANUAL_CHANGE,
            ),
        )
    }

    @Test
    fun errorsAndNonIntentionalStopsAreInterruptedNotSkips() {
        assertEquals(
            ListeningEventType.PLAYBACK_INTERRUPTED,
            classifier.classify(
                listenedDurationMs = 5_000,
                recordingDurationMs = 200_000,
                endCause = PlaybackEndCause.ERROR,
            ),
        )
        assertEquals(
            ListeningEventType.PLAYBACK_INTERRUPTED,
            classifier.classify(
                listenedDurationMs = 150_000,
                recordingDurationMs = 200_000,
                endCause = PlaybackEndCause.STOPPED,
            ),
        )
    }
}
