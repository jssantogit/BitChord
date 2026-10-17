package com.music.bitchord.data.catalog

import com.music.bitchord.data.model.Song

/**
 * Read model for one playable local asset joined to its canonical recording.
 *
 * This is deliberately not [Song]. Canonical ids belong to our catalog while
 * [Song.videoId] remains a legacy/provider compatibility slot until the player
 * identity seam is migrated in Slice 2.
 */
internal data class CanonicalLocalRow(
    val recordingId: String,
    val mediaAssetId: String,
    val sourceKey: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val albumTitle: String?,
    val artworkUri: String?,
    val uri: String,
    val path: String?,
    val dateAddedSeconds: Long?,
    val dateModifiedSeconds: Long?,
    val trackNumber: Int?,
    val youtubeVideoId: String?,
)

/**
 * Transitional edge adapter into BitChord's existing UI/playback DTO.
 *
 * Never put [CanonicalLocalRow.recordingId] or [CanonicalLocalRow.mediaAssetId]
 * into [Song.videoId]. The current player still expects that field to be a
 * provider id or a directly playable local URI. Canonical identity stays on
 * the catalog side of this boundary until Slice 2 migrates queue/playback ids.
 */
internal fun CanonicalLocalRow.toLegacySong(): Song = Song(
    videoId = youtubeVideoId ?: uri,
    title = title,
    artist = artistDisplay,
    thumbnailUrl = artworkUri,
    durationText = durationMs?.takeIf { it > 0 }?.toDurationText(),
    albumId = null,
    albumName = albumTitle,
    localUri = uri,
    localPath = path,
    localDateAddedSeconds = dateAddedSeconds,
    localDateModifiedSeconds = dateModifiedSeconds,
    trackNumber = trackNumber,
)

private fun Long.toDurationText(): String {
    val totalSeconds = this / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    return if (totalMinutes >= 60L) {
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(totalMinutes, seconds)
    }
}
