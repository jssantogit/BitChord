package com.music.bitchord.data.catalog.metadata.discogs

/**
 * Optional credentials broaden discovery, but must not be a prerequisite
 * when an exact MusicBrainz Artist -> Discogs Artist relation already exists.
 * Select one source per visit to avoid redundant provider requests.
 */
internal enum class DiscogsArtistDiscoveryMode {
    DISABLED,
    PUBLIC_ARTIST_LINK,
    AUTHENTICATED_SEARCH,
}

internal fun discogsArtistDiscoveryMode(
    enabled: Boolean,
    tokenConfigured: Boolean,
): DiscogsArtistDiscoveryMode = when {
    !enabled -> DiscogsArtistDiscoveryMode.DISABLED
    tokenConfigured -> DiscogsArtistDiscoveryMode.AUTHENTICATED_SEARCH
    else -> DiscogsArtistDiscoveryMode.PUBLIC_ARTIST_LINK
}
