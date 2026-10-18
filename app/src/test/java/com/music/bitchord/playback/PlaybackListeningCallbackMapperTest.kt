package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.listening.ListeningEventType
import com.music.bitchord.data.listening.ListeningOutcomeClassifier
import com.music.bitchord.data.listening.PlaybackEndCause
import com.music.bitchord.data.listening.SelectionIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackListeningCallbackMapperTest {
    private val classifier = ListeningOutcomeClassifier()

    @Test
    fun automaticTransitionIsNaturalCompletion() {
        val cause = PlaybackListeningCallbackMapper.endCauseForDiscontinuity(
            reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            oldIndex = 3,
            newIndex = 4,
        )
        assertEquals(PlaybackEndCause.ENDED, cause)
        assertEquals(
            ListeningEventType.PLAYBACK_COMPLETED,
            classifier.classify(
                listenedDurationMs = 10_000L,
                recordingDurationMs = 200_000L,
                endCause = requireNotNull(cause),
            ),
        )
        assertEquals(
            SelectionIntent.AUTOMATIC,
            PlaybackListeningCallbackMapper.selectionIntentForTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            ),
        )
    }

    @Test
    fun manualQueueNavigationPreservesDirectionAndProducesSkipClasses() {
        val next = requireNotNull(
            PlaybackListeningCallbackMapper.endCauseForDiscontinuity(
                reason = Player.DISCONTINUITY_REASON_SEEK,
                oldIndex = 3,
                newIndex = 4,
            ),
        )
        val previous = requireNotNull(
            PlaybackListeningCallbackMapper.endCauseForDiscontinuity(
                reason = Player.DISCONTINUITY_REASON_SEEK,
                oldIndex = 3,
                newIndex = 2,
            ),
        )

        assertEquals(PlaybackEndCause.NEXT, next)
        assertEquals(PlaybackEndCause.PREVIOUS, previous)
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_EARLY,
            classifier.classify(20_000L, 200_000L, next),
        )
        assertEquals(
            ListeningEventType.PLAYBACK_SKIPPED_LATE,
            classifier.classify(100_000L, 200_000L, previous),
        )
    }

    @Test
    fun sameItemSeekIsNotAPlaybackEnd() {
        assertNull(
            PlaybackListeningCallbackMapper.endCauseForDiscontinuity(
                reason = Player.DISCONTINUITY_REASON_SEEK,
                oldIndex = 3,
                newIndex = 3,
            ),
        )
    }

    @Test
    fun stoppedAndErrorCallbacksProduceInterruptions() {
        val stopped = requireNotNull(
            PlaybackListeningCallbackMapper.endCauseForPlaybackState(Player.STATE_IDLE),
        )
        val error = PlaybackListeningCallbackMapper.playerErrorCause()

        assertEquals(PlaybackEndCause.STOPPED, stopped)
        assertEquals(
            PlaybackEndCause.ENDED,
            PlaybackListeningCallbackMapper.endCauseForPlaybackState(Player.STATE_ENDED),
        )
        assertEquals(PlaybackEndCause.ERROR, error)
        assertEquals(
            ListeningEventType.PLAYBACK_INTERRUPTED,
            classifier.classify(20_000L, 200_000L, stopped),
        )
        assertEquals(
            ListeningEventType.PLAYBACK_INTERRUPTED,
            classifier.classify(20_000L, 200_000L, error),
        )
    }

    @Test
    fun repeatStartsAReplayInsideTheSameAnalyticalSession() {
        assertEquals(
            SelectionIntent.REPLAY,
            PlaybackListeningCallbackMapper.selectionIntentForTransition(
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            ),
        )
    }

    @Test
    fun bootstrapAndRenditionSwapKeepSamePlaybackButRepeatDoesNot() {
        assertTrue(
            PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                transitionReason = null,
                sameRecording = true,
            ),
        )
        assertTrue(
            PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                sameRecording = true,
            ),
        )
        assertFalse(
            PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                sameRecording = true,
            ),
        )
        assertFalse(
            PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                transitionReason = null,
                sameRecording = false,
            ),
        )
    }
}
