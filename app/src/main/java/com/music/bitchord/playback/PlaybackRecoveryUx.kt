package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A finite, credential-free explanation. Never persist a provider URL or exception text. */
internal enum class PlaybackRecoveryReason {
    CONNECTION_OR_SERVER, REMOTE_UNAVAILABLE, REMOTE_OPEN_FAILED,
}
internal enum class PlaybackRecoveryStatus { WAITING_FOR_USER, RETRYING }
internal enum class PlaybackRecoveryRetryResult {
    STARTED, STALE_SELECTION, SOURCE_REMOVED, ALREADY_RETRYING,
}

/** One failed physical occurrence and its immutable canonical selection. */
internal data class PlaybackRecoveryNotice(
    val entryId: String,
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val reason: PlaybackRecoveryReason,
    val status: PlaybackRecoveryStatus = PlaybackRecoveryStatus.WAITING_FOR_USER,
)

/** No network listener, auto retry, or provider fallback is exposed by this state machine. */
internal class PlaybackRecoveryTracker {
    var current: PlaybackRecoveryNotice? = null
        private set

    fun reportFailure(failure: PlaybackRecoveryNotice, beforeAudio: Boolean) {
        if (!beforeAudio || failure.entryId.isBlank()) return
        current = failure.copy(status = PlaybackRecoveryStatus.WAITING_FOR_USER)
    }

    /** Playback of a different track is not evidence that the remote recovered. */
    fun onAudible(entryId: String?) {
        if (current?.entryId == entryId) current = null
    }

    fun beginRetry(
        entryId: String,
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        present: Boolean,
    ): PlaybackRecoveryRetryResult {
        val active = current ?: return PlaybackRecoveryRetryResult.STALE_SELECTION
        if (active.entryId != entryId || active.recordingId != recordingId ||
            active.mediaAssetId != mediaAssetId || active.providerKey != providerKey
        ) return PlaybackRecoveryRetryResult.STALE_SELECTION
        if (!present) {
            current = null
            return PlaybackRecoveryRetryResult.SOURCE_REMOVED
        }
        if (active.status == PlaybackRecoveryStatus.RETRYING) {
            return PlaybackRecoveryRetryResult.ALREADY_RETRYING
        }
        current = active.copy(status = PlaybackRecoveryStatus.RETRYING)
        return PlaybackRecoveryRetryResult.STARTED
    }

    fun onRetryFailed(entryId: String) {
        current?.takeIf { it.entryId == entryId }?.let {
            current = it.copy(status = PlaybackRecoveryStatus.WAITING_FOR_USER)
        }
    }

    fun reconcileQueue(availableEntries: Set<String>) {
        if (current?.entryId !in availableEntries) current = null
    }

    fun dismiss(entryId: String) {
        if (current?.entryId == entryId) current = null
    }
}

/** Session-local, foreground-observable notices. No sensitive diagnostics. */
internal object PlaybackRecoveryNotices {
    private val tracker = PlaybackRecoveryTracker()
    private val _notice = MutableStateFlow<PlaybackRecoveryNotice?>(null)
    val notice: StateFlow<PlaybackRecoveryNotice?> = _notice.asStateFlow()
    private fun publish() { _notice.value = tracker.current }

    @Synchronized fun reportFailure(failure: PlaybackRecoveryNotice, beforeAudio: Boolean) {
        tracker.reportFailure(failure, beforeAudio)
        publish()
    }

    @Synchronized fun onAudible(entryId: String?) {
        tracker.onAudible(entryId)
        publish()
    }

    @Synchronized fun beginRetry(
        entryId: String, recordingId: String, mediaAssetId: String,
        providerKey: String, present: Boolean,
    ): PlaybackRecoveryRetryResult {
        val outcome = tracker.beginRetry(entryId, recordingId, mediaAssetId, providerKey, present)
        publish()
        return outcome
    }

    @Synchronized fun onRetryFailed(entryId: String) {
        tracker.onRetryFailed(entryId)
        publish()
    }

    @Synchronized fun reconcileQueue(entries: Set<String>) {
        tracker.reconcileQueue(entries)
        publish()
    }

    @Synchronized fun dismiss(entryId: String) {
        tracker.dismiss(entryId)
        publish()
    }
}

/**
 * An entry ID is preferable; legacy single-item canonical remote playback may
 * not have one. In that case index+mediaId is deliberately fragile to reorders:
 * a stale action MUST fail instead of being aimed at a different occurrence.
 */
internal fun playbackRecoveryEntryId(item: MediaItem, index: Int): String =
    item.queueEntryId ?: ("legacy-index:" + index + ":" + item.mediaId)

internal const val ACTION_RETRY_CANONICAL_FAILURE =
    "com.music.bitchord.action.RETRY_CANONICAL_FAILURE"
internal const val EXTRA_RETRY_CANONICAL_ENTRY_ID = "bitchord.canonicalRecovery.entryId"
