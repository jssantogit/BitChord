package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.listening.PlaybackEndCause
import com.music.bitchord.data.listening.SelectionIntent

/**
 * Pure translation from Media3 callback facts into Listening Event Store facts.
 * It deliberately has no session concept; session ownership stays entirely in
 * Listening & Intelligence.
 */
internal object PlaybackListeningCallbackMapper {
    fun endCauseForDiscontinuity(
        reason: Int,
        oldIndex: Int,
        newIndex: Int,
    ): PlaybackEndCause? = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> PlaybackEndCause.ENDED
        Player.DISCONTINUITY_REASON_SEEK -> when {
            newIndex > oldIndex -> PlaybackEndCause.NEXT
            newIndex < oldIndex -> PlaybackEndCause.PREVIOUS
            else -> null
        }
        Player.DISCONTINUITY_REASON_REMOVE -> PlaybackEndCause.MANUAL_CHANGE
        else -> null
    }

    fun endCauseForPlaybackState(state: Int): PlaybackEndCause? = when (state) {
        Player.STATE_ENDED -> PlaybackEndCause.ENDED
        Player.STATE_IDLE -> PlaybackEndCause.STOPPED
        else -> null
    }

    fun playerErrorCause(): PlaybackEndCause = PlaybackEndCause.ERROR

    fun selectionIntentForTransition(reason: Int): SelectionIntent = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> SelectionIntent.AUTOMATIC
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> SelectionIntent.REPLAY
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> SelectionIntent.MANUAL
        else -> SelectionIntent.UNKNOWN
    }

    /**
     * A controller attaching after the sampler already started the same recording
     * is not a second play. Nor is Media3 replacing the active rendition in place.
     * Repeat deliberately remains a boundary even when canonical identity is the
     * same, because it represents another playback of that recording.
     */
    fun keepsCurrentPlayback(transitionReason: Int?, sameRecording: Boolean): Boolean =
        sameRecording && (
            transitionReason == null ||
                transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
}
