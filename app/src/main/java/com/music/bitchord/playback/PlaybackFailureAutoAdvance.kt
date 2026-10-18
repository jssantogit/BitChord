package com.music.bitchord.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.QueueTier

/**
 * Decision at the boundary between selected-rendition playback and the legacy
 * resolver/retry path.
 *
 * Canonical provider failures are never handed to legacy resolution. Only
 * entry-scoped I/O/open failures that happened before audio started are
 * eligible for queue auto-advance in this slice. A fatal/internal failure stays
 * terminal and therefore cannot accidentally become provider/source fallback.
 */
internal enum class PlaybackFailureDisposition {
    AUTO_ADVANCE,
    TERMINAL,
    LEGACY_RECOVERY,
}

/** Runtime result after applying [PlaybackFailureDisposition]. */
internal enum class PlaybackFailureHandling {
    AUTO_ADVANCED,
    CASCADE_EXHAUSTED,
    TERMINAL,
    NOT_HANDLED,
}

internal enum class PlaybackFailureDiagnosticOutcome {
    ADVANCE,
    EXHAUSTED,
    TERMINAL,
}

internal fun classifyPlaybackFailure(
    canonicalRemote: Boolean,
    localUriScheme: String?,
    errorCode: Int,
    neverStarted: Boolean,
): PlaybackFailureDisposition {
    if (canonicalRemote) {
        return if (neverStarted && errorCode in PLAYBACK_IO_ERROR_RANGE) {
            PlaybackFailureDisposition.AUTO_ADVANCE
        } else {
            PlaybackFailureDisposition.TERMINAL
        }
    }

    val staleLocal = neverStarted &&
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
        (localUriScheme == "content" || localUriScheme == "file")
    return if (staleLocal) {
        PlaybackFailureDisposition.AUTO_ADVANCE
    } else {
        PlaybackFailureDisposition.LEGACY_RECOVERY
    }
}

internal fun PlaybackFailureCascade.handlePlaybackFailure(
    error: PlaybackException,
    player: ExoPlayer,
    neverStarted: Boolean,
): PlaybackFailureHandling {
    val currentIndex = player.currentMediaItemIndex
    val current = player.currentMediaItem ?: return PlaybackFailureHandling.NOT_HANDLED
    val disposition = classifyPlaybackFailure(
        canonicalRemote = current.isCanonicalRemotePlayback(),
        localUriScheme = current.localConfiguration?.uri?.scheme,
        errorCode = error.errorCode,
        neverStarted = neverStarted,
    )

    if (disposition == PlaybackFailureDisposition.LEGACY_RECOVERY) {
        return PlaybackFailureHandling.NOT_HANDLED
    }
    if (disposition == PlaybackFailureDisposition.TERMINAL) {
        TrackLog.w(
            "BitChord",
            safePlaybackFailureDiagnostic(
                error,
                PlaybackFailureDiagnosticOutcome.TERMINAL,
                failedCount = failedKeys.size,
            ),
            about = failureLogKey(currentIndex),
        )
        return PlaybackFailureHandling.TERMINAL
    }
    if (currentIndex !in 0 until player.mediaItemCount) {
        return PlaybackFailureHandling.TERMINAL
    }

    val queue = List(player.mediaItemCount) { index ->
        FailureQueueEntry(
            key = requireNotNull(failureEntryKeyOrNull(player, index)),
            index = index,
        )
    }
    val currentEntry = queue[currentIndex]
    val repeatMode = when (player.repeatMode) {
        Player.REPEAT_MODE_ALL -> FailureRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> FailureRepeatMode.ONE
        else -> FailureRepeatMode.OFF
    }

    return when (val decision = onFailure(currentEntry, queue, repeatMode)) {
        is PlaybackFailureCascade.Decision.Advance -> {
            TrackLog.w(
                "BitChord",
                safePlaybackFailureDiagnostic(
                    error,
                    PlaybackFailureDiagnosticOutcome.ADVANCE,
                    failedCount = failedKeys.size,
                ),
                about = failureLogKey(currentIndex),
            )
            player.seekTo(decision.mediaItemIndex, 0L)
            player.prepare()
            PlaybackFailureHandling.AUTO_ADVANCED
        }

        PlaybackFailureCascade.Decision.Exhausted -> {
            TrackLog.w(
                "BitChord",
                safePlaybackFailureDiagnostic(
                    error,
                    PlaybackFailureDiagnosticOutcome.EXHAUSTED,
                    failedCount = failedKeys.size,
                ),
                about = failureLogKey(currentIndex),
            )
            player.pause()
            PlaybackFailureHandling.CASCADE_EXHAUSTED
        }
    }
}

/**
 * Intentionally ignores [PlaybackException.message], its cause and any URI.
 * Provider-owned exceptions may originate below authenticated HTTP, so only
 * Media3's finite error-code name and a bounded attempt count are allowed into
 * TrackLog/Diagnostics here.
 */
internal fun safePlaybackFailureDiagnostic(
    error: PlaybackException,
    outcome: PlaybackFailureDiagnosticOutcome,
    failedCount: Int,
): String = when (outcome) {
    PlaybackFailureDiagnosticOutcome.ADVANCE ->
        "playback entry unavailable (${error.errorCodeName}); advancing within existing queue"
    PlaybackFailureDiagnosticOutcome.EXHAUSTED ->
        "playback failure cascade exhausted after $failedCount queue entries " +
            "(${error.errorCodeName}); stopping without changing queue"
    PlaybackFailureDiagnosticOutcome.TERMINAL ->
        "playback failure kept terminal (${error.errorCodeName}); legacy resolver bypassed"
}

internal fun failureEntryKeyOrNull(player: Player, index: Int): String? {
    if (index !in 0 until player.mediaItemCount) return null
    val item = player.getMediaItemAt(index)
    return failureEntryKey(item.queueTier, item.canonicalIndex, item.queueEntryId, index)
}

/**
 * Stable enough for a runtime cascade while deliberately carrying no provider
 * locator. Context uses its logical collection slot so later repeat-all cycles
 * deduplicate the same entry; user/autoplay rows already carry queueEntryId.
 */
internal fun failureEntryKey(
    tier: QueueTier,
    canonicalIndex: Int?,
    queueEntryId: String?,
    physicalIndex: Int,
): String = when {
    tier == QueueTier.CONTEXT && canonicalIndex != null && canonicalIndex >= 0 ->
        "context:$canonicalIndex"
    !queueEntryId.isNullOrBlank() -> "entry:$queueEntryId"
    else -> "slot:$physicalIndex"
}

/** Only safe, synthetic queue information is ever attached to diagnostics. */
private fun failureLogKey(index: Int): String = "queue-entry-$index"

/** Media3 reserves the 2xxx range for I/O/data-source failures. */
private val PLAYBACK_IO_ERROR_RANGE = 2000..2999
