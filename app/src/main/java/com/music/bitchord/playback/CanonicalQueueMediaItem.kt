package com.music.bitchord.playback

import androidx.media3.common.MediaItem

/**
 * Playback-owned sidecar for canonical identity on a live Media3 queue item.
 *
 * MediaItem.mediaId deliberately remains the compatibility/transport id. The
 * preferred lookup is the immutable queue-entry id. A freshly restored item can
 * also recover the identity through the concrete local MediaAsset URI selected
 * by PlaybackResolver; that URI is a rendition bridge, never the identity itself.
 */
internal val MediaItem.canonicalQueueIdentity: CanonicalQueueIdentity?
    get() = queueEntryId?.let(CanonicalQueueIdentityHints::identityForEntry)
        ?: CanonicalQueueIdentityHints.identityForAssetUri(mediaId)

/**
 * Process-local bridge between the canonical resolver and the legacy MediaItem
 * envelope. Nothing here invents identity: every association originates from a
 * Recording/MediaAsset pair already read from the canonical catalog.
 */
internal object CanonicalQueueIdentityHints {
    @Volatile
    private var byEntryId: Map<String, CanonicalQueueIdentity> = emptyMap()

    @Volatile
    private var byAssetUri: Map<String, CanonicalQueueIdentity> = emptyMap()

    @Synchronized
    fun rememberEntry(entryId: String?, identity: CanonicalQueueIdentity?) {
        val key = entryId?.takeIf(String::isNotBlank) ?: return
        val value = identity ?: return
        byEntryId = byEntryId + (key to value)
    }

    @Synchronized
    fun rememberResolved(
        identities: List<CanonicalQueueIdentity?>,
        assets: List<PlayableMediaAsset?>,
    ) {
        if (identities.isEmpty() || assets.isEmpty()) return
        val next = LinkedHashMap(byAssetUri)
        identities.indices.forEach { index ->
            val identity = identities[index] ?: return@forEach
            val asset = assets.getOrNull(index) ?: return@forEach
            if (asset.recordingId == identity.recordingId && asset.uri.isNotBlank()) {
                next[asset.uri] = identity
            }
        }
        byAssetUri = next
    }

    fun identityForEntry(entryId: String): CanonicalQueueIdentity? = byEntryId[entryId]

    fun identityForAssetUri(uri: String): CanonicalQueueIdentity? = byAssetUri[uri]

    @Synchronized
    fun clear() {
        byEntryId = emptyMap()
        byAssetUri = emptyMap()
    }
}
