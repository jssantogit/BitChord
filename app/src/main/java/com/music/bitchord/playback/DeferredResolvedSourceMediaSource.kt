package com.music.bitchord.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Source playback is discovered on IO and typed BEFORE the child MediaSource
 * is created. DefaultMediaSourceFactory chooses Progressive/HLS/DASH from
 * MediaItem during createMediaSource, well before DataSource.open.
 *
 * A deferred child keeps the owning ExoPlayer looper unblocked, isolates the
 * selection per queue item (and therefore per crossfade deck) and prevents a
 * late source resolution from preparing anything after release.
 */
@UnstableApi
internal class DeferredResolvedSourceMediaSource(
    private val logicalItem: MediaItem,
    private val resolveAndBuild: suspend () -> MediaSource,
) : CompositeMediaSource<Int>() {
    private var scope: CoroutineScope? = null
    private var task: Job? = null
    private var child: MediaSource? = null
    private var released = false
    @Volatile private var prepareFailure: IOException? = null

    override fun getMediaItem(): MediaItem = logicalItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released = false
        val playerLooper = Looper.myLooper()
            ?: throw IllegalStateException("MediaSource must prepare on its player looper")
        val playerHandler = Handler(playerLooper)
        val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = preparationScope
        task = preparationScope.launch {
            val result = try {
                Result.success(resolveAndBuild())
            } catch (cancelled: CancellationException) {
                // A per-provider query timeout is a *preparation failure*,
                // not permission to leave the player stuck waiting forever.
                // Only actual parent/job cancellation abandons the callback.
                if (!currentCoroutineContext().isActive) return@launch
                Result.failure(IOException("Provider stream selection timed out", cancelled))
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            playerHandler.post {
                if (released) return@post
                result.fold(
                    onSuccess = { source ->
                        child = source
                        prepareChildSource(0, source)
                    },
                    onFailure = { failure ->
                        prepareFailure = if (failure is IOException) failure else
                            IOException("Provider media selection failed", failure)
                    },
                )
            }
        }
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Int,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        // The child needs a physical manifest URI to resolve its relative
        // segments, but media sessions and the UI must retain only the
        // original logical track and must never expose signed CDN tokens.
        refreshSourceInfo(sourceMediaTimeline(newTimeline, logicalItem))
    }

    override fun maybeThrowSourceInfoRefreshError() {
        prepareFailure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = (child ?: throw IllegalStateException(
        "Source media period requested before async source selection"))
        .createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (child ?: throw IllegalStateException("Source media period has no child"))
            .releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released = true
        task?.cancel()
        task = null
        super.releaseSourceInternal()
        child = null
        scope?.cancel()
        scope = null
    }
}

/**
 * Child HLS/DASH timelines contain the physical MediaItem, whose URL may
 * carry a signed query or request-specific secret. Never disclose it through
 * Player.currentMediaItem, MediaController, Bluetooth or the lock screen.
 */
@UnstableApi
internal fun sourceMediaTimeline(
    physicalTimeline: Timeline,
    logicalItem: MediaItem,
): Timeline = object : ForwardingTimeline(physicalTimeline) {
    override fun getWindow(
        windowIndex: Int,
        window: Timeline.Window,
        defaultPositionProjectionUs: Long,
    ): Timeline.Window = super.getWindow(
        windowIndex, window, defaultPositionProjectionUs,
    ).apply {
        mediaItem = logicalItem
    }
}
