package com.music.bitchord.playback

import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin

/**
 * Runtime-only gate between a Media3 selection and a factual playback start.
 *
 * A queue/media-item transition only selects a possible occurrence. Listening
 * receives PLAYBACK_STARTED only when Playback later observes actual audio for
 * that occurrence. This keeps failure-before-start auto-advance invisible to
 * Listening while still allowing repeat/retry to become a new occurrence once
 * it really plays.
 */
internal class PlaybackListeningStartGate {
    internal data class AudibleStart(
        val occurrenceId: Long,
        val identity: CanonicalPlaybackIdentity,
        val intent: SelectionIntent,
        val origin: SelectionOrigin,
    )

    private data class SelectedOccurrence(
        val occurrenceId: Long,
        var identity: CanonicalPlaybackIdentity,
        val intent: SelectionIntent,
        val origin: SelectionOrigin,
        var started: Boolean = false,
    )

    private var nextOccurrenceId = 0L
    private var selected: SelectedOccurrence? = null
    private var lastStart: AudibleStart? = null

    fun onSelection(
        identity: CanonicalPlaybackIdentity,
        intent: SelectionIntent,
        origin: SelectionOrigin,
    ): Long {
        val occurrenceId = ++nextOccurrenceId
        selected = SelectedOccurrence(
            occurrenceId = occurrenceId,
            identity = identity,
            intent = intent,
            origin = origin,
        )
        return occurrenceId
    }

    /**
     * Returns a start exactly once for the selected occurrence, and only after
     * audible evidence exists. If the sampler attaches after playback already
     * began and no selection callback was observed, an implicit UNKNOWN
     * occurrence is created from the canonical identity carried by that sample.
     */
    fun onAudible(identity: CanonicalPlaybackIdentity): AudibleStart? {
        var occurrence = selected
        if (occurrence == null || occurrence.identity.recordingId != identity.recordingId) {
            occurrence = SelectedOccurrence(
                occurrenceId = ++nextOccurrenceId,
                identity = identity,
                intent = SelectionIntent.UNKNOWN,
                origin = SelectionOrigin.UNKNOWN,
            )
            selected = occurrence
        } else {
            // Keep current asset/provenance if a rendition was updated before
            // the first sampler tick, without creating a second occurrence.
            occurrence.identity = identity
        }

        if (occurrence.started) return null
        occurrence.started = true
        return AudibleStart(
            occurrenceId = occurrence.occurrenceId,
            identity = occurrence.identity,
            intent = occurrence.intent,
            origin = occurrence.origin,
        ).also { lastStart = it }
    }

    /**
     * Clear only the occurrence that actually ended. A delayed callback from an
     * old repeat must not erase a newer selection of the same Recording.
     */
    fun onPlaybackEnded(occurrenceId: Long) {
        if (selected?.occurrenceId == occurrenceId) {
            selected = null
        }
    }

    internal fun lastStartForTest(): AudibleStart? = lastStart
}
