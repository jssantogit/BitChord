package com.music.bitchord.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.ListeningEventIngestor
import com.music.bitchord.data.listening.ListeningEventStores
import com.music.bitchord.data.listening.PlaybackActivity
import com.music.bitchord.data.listening.PlaybackEnd
import com.music.bitchord.data.listening.PlaybackEndCause
import com.music.bitchord.data.listening.PlaybackStart
import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Playback-facing adapter for the first-party Listening Event Store.
 *
 * The Media3 service remains the owner of playback facts. This bridge observes
 * those callbacks, resolves the legacy MediaItem/Song to an already-existing
 * canonical catalog identity, and emits facts through [ListeningEventIngestor].
 * It never creates or transports a ListeningSession id.
 *
 * Attachment is lazy: [observeSample] is called by the existing playback
 * sampler only while audio is actually playing. That means telemetry cannot
 * start PlaybackService merely because the app process opened.
 */
internal object PlaybackListeningBridge {
    private const val TAG = "PlaybackListening"
    private const val LOCAL_PROVIDER = "local-mediastore"
    private const val YOUTUBE_PROVIDER = "youtube"
    private const val RECORDING_ENTITY_TYPE = "recording"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = PlaybackListeningCommandQueue(scope) { error ->
        Log.e(TAG, "Listening callback ingestion failed", error)
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var controller: MediaController? = null
    @Volatile
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Accessed only from [commands]. */
    private var ingestor: ListeningEventIngestor? = null

    /** Accessed only from [commands]. */
    private var active: ActivePlayback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Feeds the same wall-clock listening evidence already measured by
     * ListeningRecorder. [listenedDeltaMs] is the actual audio time since the
     * previous sampler tick, not player position and not a per-resume total.
     * This keeps pause/resume additive while seeks cannot inflate listening.
     *
     * Each real audio sample also refreshes the store-owned session heartbeat,
     * so a long song never looks like thirty minutes of inactivity merely
     * because no track boundary occurred.
     */
    fun observeSample(song: Song, recordingDurationMs: Long?, listenedDeltaMs: Long) {
        require(listenedDeltaMs >= 0L)
        ensureAttached()
        commands.submit {
            val identity = resolve(song) ?: return@submit
            val current = active
            if (current == null) {
                begin(identity, SelectionIntent.UNKNOWN, SelectionOrigin.UNKNOWN)
            } else if (current.identity.recordingId != identity.recordingId) {
                end(current, PlaybackEndCause.MANUAL_CHANGE)
                begin(identity, SelectionIntent.UNKNOWN, SelectionOrigin.UNKNOWN)
            }
            active?.takeIf { it.identity.recordingId == identity.recordingId }?.apply {
                listenedDurationMs += listenedDeltaMs
                if (recordingDurationMs != null && recordingDurationMs > 0L) {
                    this.recordingDurationMs = recordingDurationMs
                }
            }
            eventIngestor().recordPlaybackActivity(
                PlaybackActivity(
                    recordingId = identity.recordingId,
                    mediaAssetId = identity.mediaAssetId,
                    assetProvider = identity.provider,
                ),
            )
        }
    }

    private fun ensureAttached() {
        if (controller != null || controllerFuture != null) return
        val context = appContext ?: return
        synchronized(this) {
            if (controller != null || controllerFuture != null) return
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    val connected = runCatching { future.get() }.getOrNull()
                    if (connected == null) {
                        controllerFuture = null
                        return@addListener
                    }
                    controller = connected
                    connected.addListener(listener)
                    // The first sample may have arrived while the controller
                    // connection was being established. Bootstrap from the real
                    // current item instead of waiting for another transition.
                    if (connected.isPlaying) {
                        connected.currentMediaItem?.let { mediaItem ->
                            startFromMediaItem(
                                mediaItem = mediaItem,
                                intent = SelectionIntent.UNKNOWN,
                                origin = SelectionOrigin.UNKNOWN,
                                transitionReason = null,
                            )
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val cause = PlaybackListeningCallbackMapper.endCauseForDiscontinuity(
                reason = reason,
                oldIndex = oldPosition.mediaItemIndex,
                newIndex = newPosition.mediaItemIndex,
            ) ?: return
            endActive(cause)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = mediaItem ?: return
            startFromMediaItem(
                mediaItem = item,
                intent = PlaybackListeningCallbackMapper.selectionIntentForTransition(reason),
                origin = SelectionOrigin.QUEUE,
                transitionReason = reason,
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            PlaybackListeningCallbackMapper.endCauseForPlaybackState(playbackState)
                ?.let(::endActive)
        }

        override fun onPlayerError(error: PlaybackException) {
            endActive(PlaybackListeningCallbackMapper.playerErrorCause())
        }
    }

    private fun startFromMediaItem(
        mediaItem: MediaItem,
        intent: SelectionIntent,
        origin: SelectionOrigin,
        transitionReason: Int?,
    ) {
        commands.submit {
            val identity = resolve(mediaItem.toSong()) ?: return@submit
            val current = active
            if (current != null) {
                val sameRecording = current.identity.recordingId == identity.recordingId
                if (
                    PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                        transitionReason = transitionReason,
                        sameRecording = sameRecording,
                    )
                ) {
                    return@submit
                }

                // Ordinarily the preceding discontinuity callback has already
                // ended the old item and [active] is null here. These fallbacks
                // cover Media3 transitions that arrive without that callback,
                // while keeping repeat/automatic completion semantically exact.
                val fallbackCause = when (transitionReason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> PlaybackEndCause.ENDED
                    else -> PlaybackEndCause.MANUAL_CHANGE
                }
                end(current, fallbackCause)
            }
            begin(identity, intent, origin)
        }
    }

    private fun endActive(cause: PlaybackEndCause) {
        commands.submit {
            active?.let { end(it, cause) }
        }
    }

    private suspend fun begin(
        identity: CanonicalPlaybackIdentity,
        intent: SelectionIntent,
        origin: SelectionOrigin,
    ) {
        eventIngestor().recordPlaybackStarted(
            PlaybackStart(
                recordingId = identity.recordingId,
                mediaAssetId = identity.mediaAssetId,
                assetProvider = identity.provider,
                selectionIntent = intent,
                selectionOrigin = origin,
            ),
        )
        active = ActivePlayback(identity = identity)
    }

    private suspend fun end(playback: ActivePlayback, cause: PlaybackEndCause) {
        eventIngestor().recordPlaybackEnded(
            PlaybackEnd(
                recordingId = playback.identity.recordingId,
                mediaAssetId = playback.identity.mediaAssetId,
                assetProvider = playback.identity.provider,
                listenedDurationMs = playback.listenedDurationMs,
                recordingDurationMs = playback.recordingDurationMs,
                endCause = cause,
            ),
        )
        if (active === playback) active = null
    }

    private suspend fun resolve(song: Song): CanonicalPlaybackIdentity? {
        val context = appContext ?: return null
        val dao = CanonicalCatalogDatabase.get(context).catalogDao()

        song.localUri?.takeIf(String::isNotBlank)?.let { sourceKey ->
            dao.mediaAsset(LOCAL_PROVIDER, sourceKey)?.let { asset ->
                return CanonicalPlaybackIdentity(
                    recordingId = asset.recordingId,
                    mediaAssetId = asset.id,
                    provider = asset.provider,
                )
            }
        }

        val externalId = song.videoId.takeIf(String::isNotBlank) ?: return null
        val recordingId = dao.entityIdForExternalIdentity(
            provider = YOUTUBE_PROVIDER,
            entityType = RECORDING_ENTITY_TYPE,
            externalId = externalId,
        ) ?: return null
        return CanonicalPlaybackIdentity(
            recordingId = recordingId,
            mediaAssetId = null,
            provider = YOUTUBE_PROVIDER,
        )
    }

    private fun eventIngestor(): ListeningEventIngestor {
        ingestor?.let { return it }
        val context = checkNotNull(appContext) { "PlaybackListeningBridge.init must run first" }
        return ListeningEventStores.persistent(context).also { ingestor = it }
    }

    private data class CanonicalPlaybackIdentity(
        val recordingId: String,
        val mediaAssetId: String?,
        val provider: String,
    )

    private data class ActivePlayback(
        val identity: CanonicalPlaybackIdentity,
        var listenedDurationMs: Long = 0L,
        var recordingDurationMs: Long? = null,
    )
}
