package com.music.bitchord.ui.screens

/**
 * Native search presentations of Apple commercial products. These are NOT
 * MusicBrainz IDs, Song/videoId assets or a source of playable media.
 * Browse IDs remain storefront-scoped and must be routed by Apple handlers.
 */
enum class AppleEditorialSearchKind { ARTIST, ALBUM }

/** Editorial label, not the API entity type or MusicBrainz identity. */
enum class AppleEditorialReleaseCategory { ALBUM, EP, SINGLE, UNCLASSIFIED }

data class AppleEditorialSearchRow(
    val browseId: String,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val releaseDate: String?,
    val kind: AppleEditorialSearchKind = AppleEditorialSearchKind.ALBUM,
    val category: AppleEditorialReleaseCategory = AppleEditorialReleaseCategory.UNCLASSIFIED,
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

data class AppleEditorialArtistDetailPage(
    val artist: AppleEditorialSearchRow,
    val albums: List<AppleEditorialSearchRow>,
    val possiblyTruncated: Boolean,
)

/** Apple song search is catalog metadata only, not a playable Song or MediaAsset. */
data class AppleEditorialTrackSearchRow(
    val key: String,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val albumBrowseId: String?,
    val durationMs: Long?,
)
