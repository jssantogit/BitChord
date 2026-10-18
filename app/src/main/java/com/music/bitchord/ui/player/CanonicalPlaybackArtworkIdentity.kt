package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalRemotePlaybackUri

/**
 * The artwork selection follows the *selected* canonical playback rendition.
 * A Recording alone cannot select cover art; provider routing is not identity.
 * No provider-native locator or authenticated URL belongs in this type.
 */
internal data class CanonicalPlaybackArtworkIdentity(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
) {
    init {
        require(recordingId.isNotBlank())
        require(mediaAssetId.isNotBlank())
        require(providerKey.isNotBlank())
    }

    val key: CanonicalPlaybackArtworkKey
        get() = CanonicalPlaybackArtworkKey(recordingId, mediaAssetId)

    companion object {
        /**
         * Song is a legacy display adapter, not an artwork identity carrier.
         * Only the existing credential-free canonical logical URI can supply
         * the selected MediaAsset. Never infer a source from videoId/title.
         */
        fun fromSong(song: Song?): CanonicalPlaybackArtworkIdentity? {
            val selected = song?.localUri?.let(CanonicalRemotePlaybackUri::decode)
                ?: return null
            // Direct-play MediaItems may expose recordingId as mediaId, while
            // restored queue Song adapters use the logical URI as videoId.
            // Neither variant is identity evidence; the safe selected URI is.
            if (!selected.providerKey.startsWith("opensubsonic:")) return null
            return CanonicalPlaybackArtworkIdentity(
                recordingId = selected.recordingId,
                mediaAssetId = selected.mediaAssetId,
                providerKey = selected.providerKey,
            )
        }
    }
}

internal data class CanonicalPlaybackArtworkKey(
    val recordingId: String,
    val mediaAssetId: String,
)
