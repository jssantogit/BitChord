package com.music.bitchord.ui.screens

/**
 * Native search presentations of Apple commercial products. These are NOT
 * MusicBrainz IDs, Song/videoId assets or a source of playable media.
 * Browse IDs remain storefront-scoped and must be routed by Apple handlers.
 */
/** Only the three public iTunes Search entities displayed in General Search. */
enum class AppleSearchSection { ARTISTS, TRACKS, ALBUMS }

data class AppleSearchPageStatus(
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val retryableError: Boolean = false,
    val coverageUncertain: Boolean = false,
)

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
    /** Exact Apple storefront product link, never inferred from a title. */
    val appleStoreUrl: String? = null,
    /** Unmodified official badge only when appleStoreUrl passed validation. */
    val appleStoreBadgeUrl: String? = null,
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
    /** Verified Discogs edition evidence must always carry its source link. */
    val discogsAttributionText: String? = null,
    val discogsAttributionUrl: String? = null,
    val discogsNonAffiliationNotice: String? = null,
    /** The storefront-owned link accompanying promotional album artwork. */
    val appleStoreUrl: String? = null,
    val appleStoreBadgeUrl: String? = null,
)

data class AppleEditorialArtistDetailPage(
    val artist: AppleEditorialSearchRow,
    val albums: List<AppleEditorialSearchRow>,
    /** Known additional display pages or pending public iTunes probes. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val pageError: Boolean = false,
    val noAdditionalResults: Boolean = false,
    /** A capped endpoint never proves that every Apple edition was found. */
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
    val appleStoreUrl: String? = null,
    val appleStoreBadgeUrl: String? = null,
)
