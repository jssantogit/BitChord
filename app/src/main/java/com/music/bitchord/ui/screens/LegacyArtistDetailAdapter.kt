package com.music.bitchord.ui.screens

/**
 * Compatibility-only adapter for the existing UC artist route, whose
 * YtMusicRepository.artistPage already reports a ranked list of songs.
 *
 * Never put this browseId policy in NativeArtistDetailFrame or reuse it for
 * MusicBrainz, Navidrome or a future online provider. Those sources must
 * furnish explicit ranking evidence and independent canonical identities.
 */
internal fun nativeArtistLegacyRankedFeed(browseId: String): Boolean =
    browseId.startsWith("UC")
