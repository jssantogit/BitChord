package com.music.bitchord.ui.screens

/**
 * Native search presentations of Apple commercial products. These are NOT
 * MusicBrainz IDs, Song/videoId assets or a source of playable media.
 * Browse IDs remain storefront-scoped and must be routed by Apple handlers.
 */
data class AppleEditorialSearchRow(
    val browseId: String,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val releaseDate: String?,
)

data class AppleEditorialTrackRow(
    val key: String,
    val title: String,
    val artistName: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
)

data class AppleEditorialAlbumDetailPage(
    val album: AppleEditorialSearchRow,
    val tracks: List<AppleEditorialTrackRow>,
    val partialTracklist: Boolean,
)
