package com.music.bitchord.data.listening

internal class ListeningOutcomeClassifier(
    private val completionRatio: Double = DEFAULT_COMPLETION_RATIO,
    private val earlySkipRatio: Double = DEFAULT_EARLY_SKIP_RATIO,
    private val unknownDurationEarlyThresholdMs: Long = DEFAULT_UNKNOWN_DURATION_EARLY_THRESHOLD_MS,
) {
    init {
        require(completionRatio in 0.0..1.0)
        require(earlySkipRatio in 0.0..<completionRatio)
        require(unknownDurationEarlyThresholdMs >= 0)
    }

    fun classify(
        listenedDurationMs: Long,
        recordingDurationMs: Long?,
        endCause: PlaybackEndCause,
    ): ListeningEventType {
        val listenedMs = listenedDurationMs.coerceAtLeast(0)

        return when (endCause) {
            PlaybackEndCause.ENDED -> ListeningEventType.PLAYBACK_COMPLETED
            PlaybackEndCause.NEXT,
            PlaybackEndCause.PREVIOUS,
            PlaybackEndCause.MANUAL_CHANGE,
            -> classifyExplicitNavigation(listenedMs, recordingDurationMs)

            PlaybackEndCause.STOPPED,
            PlaybackEndCause.ERROR,
            PlaybackEndCause.UNKNOWN,
            -> ListeningEventType.PLAYBACK_INTERRUPTED
        }
    }

    private fun classifyExplicitNavigation(
        listenedDurationMs: Long,
        recordingDurationMs: Long?,
    ): ListeningEventType {
        val durationMs = recordingDurationMs?.takeIf { it > 0 }
        if (durationMs == null) {
            return if (listenedDurationMs < unknownDurationEarlyThresholdMs) {
                ListeningEventType.PLAYBACK_SKIPPED_EARLY
            } else {
                ListeningEventType.PLAYBACK_SKIPPED_LATE
            }
        }

        val listenedRatio = listenedDurationMs.toDouble() / durationMs.toDouble()
        return when {
            listenedRatio >= completionRatio -> ListeningEventType.PLAYBACK_COMPLETED
            listenedRatio < earlySkipRatio -> ListeningEventType.PLAYBACK_SKIPPED_EARLY
            else -> ListeningEventType.PLAYBACK_SKIPPED_LATE
        }
    }

    private companion object {
        const val DEFAULT_COMPLETION_RATIO = 0.90
        const val DEFAULT_EARLY_SKIP_RATIO = 0.25
        const val DEFAULT_UNKNOWN_DURATION_EARLY_THRESHOLD_MS = 30_000L
    }
}
