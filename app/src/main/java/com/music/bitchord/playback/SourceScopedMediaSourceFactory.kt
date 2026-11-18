package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Media3's DefaultMediaSourceFactory shares one DataSource.Factory across
 * every queue item. That is correct for local/progressive media but cannot
 * preserve a source provider's authorization through separate HLS/DASH
 * manifest and segment requests.
 *
 * Every logical bitchord://source item owns a new underlying factory and
 * [ProviderPlaybackMediaSession]. Items and sibling providers never inherit
 * a process-global "last resolved" provider. All other sources retain the
 * existing factory, policies and canonical remote transport.
 */
@UnstableApi
internal class SourceScopedMediaSourceFactory(
    private val defaultFactory: MediaSource.Factory,
    private val createSourceFactory: () -> MediaSource.Factory,
    private val preflightSource: (suspend (MediaItem) -> PreparedSourceMediaSelection)? = null,
    private val createPreparedFactory: ((PreparedSourceMediaSelection) -> MediaSource.Factory)? = null,
) : MediaSource.Factory {
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null
    private var drmProvider: DrmSessionManagerProvider? = null

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val isSource = uri?.scheme.equals("bitchord", ignoreCase = true) &&
            uri?.authority == "source"
        if (isSource && preflightSource != null) {
            // Selecting a source can require network/module I/O. Do not start
            // that work from createMediaSource: Media3 invokes it on its
            // playback looper. The deferred child selects the winning stream
            // on IO, then constructs the right HLS/DASH/progressive factory.
            return DeferredResolvedSourceMediaSource(mediaItem) {
                val selected = preflightSource.invoke(mediaItem)
                val factory = if (selected.playbackLease == null) {
                    // SMB and other non-extension sources retain the existing
                    // local/default DataSource and caching behaviour.
                    defaultFactory
                } else {
                    createPreparedFactory?.invoke(selected) ?: createSourceFactory()
                }
                loadErrorPolicy?.let(factory::setLoadErrorHandlingPolicy)
                drmProvider?.let(factory::setDrmSessionManagerProvider)
                factory.createMediaSource(selected.item)
            }
        }
        val factory = if (isSource) createSourceFactory() else defaultFactory
        loadErrorPolicy?.let(factory::setLoadErrorHandlingPolicy)
        drmProvider?.let(factory::setDrmSessionManagerProvider)
        return factory.createMediaSource(mediaItem)
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        loadErrorPolicy = loadErrorHandlingPolicy
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        drmProvider = drmSessionManagerProvider
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }
}
