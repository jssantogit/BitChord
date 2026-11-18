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

/** Strict per-asset catalog evidence, never a provider album title/URL. */
internal data class SourceOwnedAlbumEvidence(
    val releaseId: String,
    val title: String,
    val artist: String,
    val providerKey: String,
)

/** A queue item must have a concrete asset from the SAME provider as its album. */
internal fun verifiedSourceOwnedPlayerAlbum(
    recordingId: String,
    providerKey: String,
    linkedReleaseIds: List<String>,
    providerReleases: List<SourceOwnedAlbumEvidence>,
    preferredReleaseId: String? = null,
): SourceOwnedAlbumEvidence? {
    if (recordingId.isBlank() || providerKey.isBlank()) return null
    val owned = providerReleases.filter { it.providerKey == providerKey }
    val exact = uniqueSourceOwnedAlbumReleaseId(
        linkedReleaseIds = linkedReleaseIds,
        sourceReleaseIds = owned.mapTo(HashSet()) { it.releaseId },
        preferredReleaseId = preferredReleaseId,
    ) ?: return null
    return owned.firstOrNull { it.releaseId == exact && it.title.isNotBlank() }
}
