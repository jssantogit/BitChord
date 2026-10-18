package com.music.bitchord.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Thin Media3 boundary that measures the synchronous work performed while a
 * playback command is dispatched through the session-owned player.
 *
 * It deliberately delegates every behavior decision to the wrapped player.
 * The probe therefore observes the existing party/cast/crossfade/queue logic
 * without becoming part of that logic. Completion of asynchronous playback
 * work is intentionally outside this slice.
 */
internal class PlaybackPerformancePlayer(
    player: Player,
) : ForwardingPlayer(player) {

    override fun prepare() = measured(PlaybackPerformanceOperation.PREPARE) {
        super.prepare()
    }

    override fun seekTo(positionMs: Long) = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekTo(positionMs)
    }

    override fun seekBack() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekBack()
    }

    override fun seekForward() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekForward()
    }

    override fun seekToPrevious() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToPrevious()
    }

    override fun seekToDefaultPosition() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToPreviousMediaItem() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToNextMediaItem()
    }

    override fun seekToNext() = measured(PlaybackPerformanceOperation.SEEK) {
        super.seekToNext()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        measured(PlaybackPerformanceOperation.QUEUE_REBUILD) {
            super.setMediaItems(mediaItems, resetPosition)
        }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = measured(PlaybackPerformanceOperation.QUEUE_REBUILD) {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) =
        measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
            super.addMediaItems(index, mediaItems)
        }

    override fun addMediaItems(mediaItems: List<MediaItem>) =
        measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
            super.addMediaItems(mediaItems)
        }

    override fun removeMediaItem(index: Int) = measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
        super.removeMediaItem(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) =
        measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
            super.removeMediaItems(fromIndex, toIndex)
        }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) =
        measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
            super.moveMediaItem(currentIndex, newIndex)
        }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) =
        measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
            super.moveMediaItems(fromIndex, toIndex, newIndex)
        }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ) = measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
        super.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    override fun clearMediaItems() = measured(PlaybackPerformanceOperation.QUEUE_MUTATION) {
        super.clearMediaItems()
    }

    private inline fun <T> measured(
        operation: PlaybackPerformanceOperation,
        block: () -> T,
    ): T = PlaybackPerformance.measure(operation, block)
}

internal fun Player.withPlaybackPerformanceProbes(): Player = PlaybackPerformancePlayer(this)
