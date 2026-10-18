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
import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Playback-facing adapter for the first-party Listening Event Store.
 *
 * Playback remains the owner of live Media3 facts. Listening owns analytical
 * sessions and classification. This bridge never creates or transports a
 * ListeningSession id and never resolves provider-owned transport/auth data.
 *
 * Attachment is lazy: [observeSample] is called by the existing playback
 * sampler only while audio is actually playing. Telemetry therefore cannot
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
    private var resolver: PlaybackListeningIdentityResolver? = null

    /** Accessed only from [commands]. */
    private var active: ActivePlayback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Feeds the same wall-clock listening evidence already measured by
     * ListeningRecorder. [listenedDeltaMs] is actual audio time since the
     * previous sampler tick, not player position and not a per-resume total.
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
            active?.takeIf { it.identity.recordingId == identity.recordingId }?.progress
                ?.observeAudioDelta(
                    deltaMs = listenedDeltaMs,
                    recordingDurationMs = recordingDurationMs,
                )
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
            // Canonical remote identity is read directly from the live logical
            // MediaItem before any Song/legacy conversion. No provider lookup is
            // involved and provider-owned transport never reaches Listening.
            val identity = resolve(mediaItem) ?: return@submit
            val current = active
            if (current != null) {
                val sameRecording = current.identity.recordingId == identity.recordingId
                if (
                    PlaybackListeningCallbackMapper.keepsCurrentPlayback(
                        transitionReason = transitionReason,
                        sameRecording = sameRecording,
                    )
                ) {
                    // A rendition swap may update which asset/provenance is live
                    // without constituting another listen of the Recording.
                    current.identity = identity
                    return@submit
                }

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
            identity.toPlaybackStart(
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
                listenedDurationMs = playback.progress.listenedDurationMs,
                recordingDurationMs = playback.progress.recordingDurationMs,
                endCause = cause,
            ),
        )
        if (active === playback) active = null
    }

    private suspend fun resolve(mediaItem: MediaItem): CanonicalPlaybackIdentity? {
        val liveLogicalUri = mediaItem.localConfiguration?.uri?.toString()
            ?: mediaItem.mediaMetadata.extras?.getString(EXTRA_LOCAL_URI)
        val song = mediaItem.toSong()
        return identityResolver().resolve(
            canonicalUri = liveLogicalUri,
            localSourceKey = song.localUri,
            legacyYoutubeId = song.videoId,
        )
    }

    private suspend fun resolve(song: Song): CanonicalPlaybackIdentity? =
        identityResolver().resolve(
            canonicalUri = song.localUri,
            localSourceKey = song.localUri,
            legacyYoutubeId = song.videoId,
        )

    private fun identityResolver(): PlaybackListeningIdentityResolver {
        resolver?.let { return it }
        val context = checkNotNull(appContext) { "PlaybackListeningBridge.init must run first" }
        val dao = CanonicalCatalogDatabase.get(context).catalogDao()
        return PlaybackListeningIdentityResolver(
            resolveLocalAsset = { sourceKey ->
                dao.mediaAsset(LOCAL_PROVIDER, sourceKey)?.let { asset ->
                    CanonicalPlaybackIdentity(
                        recordingId = asset.recordingId,
                        mediaAssetId = asset.id,
                        provider = asset.provider,
                    )
                }
            },
            resolveLegacyYoutubeAlias = { externalId ->
                dao.entityIdForExternalIdentity(
                    provider = YOUTUBE_PROVIDER,
                    entityType = RECORDING_ENTITY_TYPE,
                    externalId = externalId,
                )?.let { recordingId ->
                    CanonicalPlaybackIdentity(
                        recordingId = recordingId,
                        mediaAssetId = null,
                        provider = YOUTUBE_PROVIDER,
                    )
                }
            },
        ).also { resolver = it }
    }

    private fun eventIngestor(): ListeningEventIngestor {
        ingestor?.let { return it }
        val context = checkNotNull(appContext) { "PlaybackListeningBridge.init must run first" }
        return ListeningEventStores.persistent(context).also { ingestor = it }
    }

    private data class ActivePlayback(
        var identity: CanonicalPlaybackIdentity,
        val progress: PlaybackListeningProgress = PlaybackListeningProgress(),
    )
}
