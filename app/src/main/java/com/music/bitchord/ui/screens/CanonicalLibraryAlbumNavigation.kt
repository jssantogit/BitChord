package com.music.bitchord.ui.screens

/**
 * Source-owned album details are browsed from verified local/remote media assets,
 * not through MusicBrainz's online release lookup. Both sides must match exact
 * persisted ReleaseIds; title/artist/artwork are never identity evidence.
 *
 * A recording appearing on multiple editions is ambiguous unless the user
 * is already inside a specific album detail.
 */
internal fun uniqueSourceOwnedAlbumReleaseId(
    linkedReleaseIds: List<String>,
    sourceReleaseIds: Set<String>,
    preferredReleaseId: String? = null,
): String? {
    val candidates = linkedReleaseIds.asSequence()
        .filter { it.isNotBlank() && it in sourceReleaseIds }
        .distinct()
        .toList()
    if (preferredReleaseId != null) return preferredReleaseId.takeIf { it in candidates }
    return candidates.singleOrNull()
}
