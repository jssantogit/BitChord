package com.music.bitchord.ui

import com.music.bitchord.data.catalog.CanonicalMusicBrainzLocalAssetClaim

/**
 * A decorative downloaded badge is a statement of observed local possession:
 * strong MusicBrainz Recording ID -> persisted local MediaAsset -> readable URI.
 * Never derive it from title/artist, a remote provider, or hasCatalogedAsset.
 */
internal fun canonicalVerifiedLocalBadgeMbids(
    claims: List<CanonicalMusicBrainzLocalAssetClaim>,
    canReadLocalUri: (String) -> Boolean,
): Set<String> = claims.asSequence()
    .filter {
        it.provider == "local-mediastore" &&
            it.musicBrainzId.isNotBlank() &&
            it.uri.startsWith("content://")
    }
    .filter { canReadLocalUri(it.uri) }
    .map { it.musicBrainzId }
    .toSet()
