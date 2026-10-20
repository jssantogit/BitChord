package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalQueueResolver

/**
 * Bridges a *specific* visible Song to the existing canonical Recording feedback UI.
 *
 * An opaque videoId, provider id, title or artist is never itself a RecordingId.
 * Playback's canonical resolver must identify AND resolve the exact same
 * Recording/MediaAsset/provider tuple before the L4 rating editor is exposed.
 * The editor revalidates Catalog availability again immediately before writes.
 */
internal class CanonicalSongFeedbackTarget(
    private val resolver: CanonicalQueueResolver,
) {
    suspend fun recordingId(song: Song): String? {
        val identity = resolver.identifyAll(listOf(song)).singleOrNull()
            ?: return null
        if (identity.recordingId.isBlank() ||
            identity.mediaAssetId.isNullOrBlank() ||
            identity.providerKey.isNullOrBlank()
        ) return null
        val resolved = resolver.resolveAll(listOf(identity)).singleOrNull()
            ?: return null
        return identity.recordingId.takeIf { resolved.identity == identity }
    }
}
