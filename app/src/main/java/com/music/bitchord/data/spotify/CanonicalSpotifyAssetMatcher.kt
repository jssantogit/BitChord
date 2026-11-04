package com.music.bitchord.data.spotify

import com.music.bitchord.data.catalog.SpotifyCanonicalAssetCandidate
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import java.util.Locale

/**
 * A Spotify ID is not a MusicBrainz RecordingId. This conservative matcher
 * only grants playback when one unique, persisted Recording owns a real asset
 * with a concordant title, artist credit and recording duration.
 *
 * This is provisional metadata matching, never an identity merge or an
 * external-identity claim. Ambiguous tracks stay visible but not playable.
 */
internal fun selectSpotifyCanonicalAsset(
    track: SpotifyTrack,
    candidates: List<SpotifyCanonicalAssetCandidate>,
): SpotifyCanonicalAssetCandidate? {
    // A capped query may have omitted a conflicting Recording. Never choose
    // the first row from an incomplete result set.
    if (track.durationMs <= 0 || candidates.size >= 50) return null
    fun normalized(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    if (track.title.isBlank() || track.artist.isBlank()) return null
    val expectedTitle = normalized(track.title)
    val expectedArtist = normalized(track.artist)
    val matches = candidates.filter { row ->
        normalized(row.title) == expectedTitle &&
            normalized(row.artistDisplay) == expectedArtist &&
            row.durationMs != null &&
            kotlin.math.abs(row.durationMs - track.durationMs.toLong()) <= 2_000L &&
            row.recordingId.isNotBlank() && row.mediaAssetId.isNotBlank() &&
            when {
                row.providerKey == "local-mediastore" ->
                    row.uri.startsWith("content://", ignoreCase = true) ||
                        row.uri.startsWith("file://", ignoreCase = true)
                row.providerKey.startsWith("opensubsonic:") ||
                    row.providerKey.startsWith("jellyfin:") ->
                    row.uri.isNotBlank()
                else -> false
            }
    }
    if (matches.map { it.recordingId }.distinct().size != 1) return null
    return matches.sortedWith(
        compareBy<SpotifyCanonicalAssetCandidate> {
            if (it.providerKey == "local-mediastore") 0 else 1
        }.thenBy { it.providerKey }.thenBy { it.mediaAssetId },
    ).firstOrNull()
}

/** Never send a provider URL, token, or Spotify ID through a fake videoId. */
internal fun spotifyCanonicalAssetAsSong(
    track: SpotifyTrack,
    asset: SpotifyCanonicalAssetCandidate,
): Song {
    val local = asset.providerKey == "local-mediastore"
    val safeUri = if (local) asset.uri else CanonicalRemotePlaybackUri.encode(
        CanonicalRemotePlaybackRequest(
            recordingId = asset.recordingId,
            mediaAssetId = asset.mediaAssetId,
            providerKey = asset.providerKey,
        ),
    )
    return Song(
        videoId = safeUri,
        title = track.title,
        artist = track.artist,
        thumbnailUrl = asset.artworkUri ?: track.imageUrl,
        durationText = track.durationMs.takeIf { it > 0 }?.let { ms ->
            "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        },
        albumName = track.album,
        localUri = safeUri,
        localPath = if (local) asset.path else null,
    )
}
