package com.music.bitchord.data.listening

internal enum class SelectionIntent {
    MANUAL,
    AUTOMATIC,
    REPLAY,
    UNKNOWN,
}

internal enum class SelectionOrigin {
    LIBRARY,
    SEARCH,
    PLAYLIST,
    QUEUE,
    RECOMMENDATION,
    UNKNOWN,
}

internal data class ListeningSession(
    val id: String,
    val startedAtEpochMs: Long,
    val lastActivityAtEpochMs: Long,
    val endedAtEpochMs: Long?,
)

internal data class ListeningEvent(
    val id: String,
    val sessionId: String,
    val recordingId: String,
    val mediaAssetId: String?,
    val assetProvider: String?,
    val occurredAtEpochMs: Long,
    val type: ListeningEventType,
    val listenedDurationMs: Long?,
    val recordingDurationMs: Long?,
    val endCause: PlaybackEndCause?,
    val selectionIntent: SelectionIntent?,
    val selectionOrigin: SelectionOrigin?,
    val recommendationOrigin: String?,
) {
    val listenedRatio: Double?
        get() {
            val durationMs = recordingDurationMs?.takeIf { it > 0 } ?: return null
            val listenedMs = listenedDurationMs ?: return null
            return listenedMs.toDouble() / durationMs.toDouble()
        }
}

/** Playback-owned fact. Session identity is deliberately absent. */
internal data class PlaybackStart(
    val recordingId: String,
    val mediaAssetId: String? = null,
    val assetProvider: String? = null,
    val selectionIntent: SelectionIntent = SelectionIntent.UNKNOWN,
    val selectionOrigin: SelectionOrigin = SelectionOrigin.UNKNOWN,
    val recommendationOrigin: String? = null,
) {
    init {
        require(recordingId.isNotBlank())
        require(mediaAssetId == null || mediaAssetId.isNotBlank())
        require(assetProvider == null || assetProvider.isNotBlank())
        require(recommendationOrigin == null || recommendationOrigin.isNotBlank())
    }
}

/**
 * Proof that audio is still actively being listened to. It lets the store keep
 * session liveness durable without emitting noisy periodic progress events.
 */
internal data class PlaybackActivity(
    val recordingId: String,
    val mediaAssetId: String? = null,
    val assetProvider: String? = null,
) {
    init {
        require(recordingId.isNotBlank())
        require(mediaAssetId == null || mediaAssetId.isNotBlank())
        require(assetProvider == null || assetProvider.isNotBlank())
    }
}

/** Playback-owned termination fact. Session identity remains store-owned. */
internal data class PlaybackEnd(
    val recordingId: String,
    val mediaAssetId: String? = null,
    val assetProvider: String? = null,
    val listenedDurationMs: Long,
    val recordingDurationMs: Long?,
    val endCause: PlaybackEndCause,
) {
    init {
        require(recordingId.isNotBlank())
        require(mediaAssetId == null || mediaAssetId.isNotBlank())
        require(assetProvider == null || assetProvider.isNotBlank())
        require(listenedDurationMs >= 0)
        require(recordingDurationMs == null || recordingDurationMs >= 0)
    }
}

internal data class RecordingBehaviorSummary(
    val recordingId: String,
    val starts: Long,
    val completions: Long,
    val earlySkips: Long,
    val lateSkips: Long,
    val interruptions: Long,
    val totalListenedMs: Long,
    val lastEventAtEpochMs: Long?,
)

internal fun interface ListeningClock {
    fun nowEpochMs(): Long
}

internal fun interface ListeningIdGenerator {
    fun nextId(): String
}
