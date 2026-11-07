package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity

/** Catalog ReleaseGroup evidence, not a guess from titles or artist names. */
internal data class CanonicalArtistReleaseEvidence(
    val releaseId: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

internal data class CanonicalArtistReleaseSections(
    val albums: List<ReleaseEntity>,
    val singlesAndEps: List<ReleaseEntity>,
    val unclassified: List<ReleaseEntity>,
    val recentAlbum: ReleaseEntity?,
    val recentRelease: ReleaseEntity? = null,
)

/**
 * Preferred classification comes from a linked, verified ReleaseGroup type.
 *
 * When a user's local/remote album tag created a canonical Release without
 * the MusicBrainz edition edge, present a *browse-only* fallback based on
 * CURRENTLY available distinct release recordings: multiple => Albums,
 * exactly one => Singles e EPs. This is a UI grouping, not evidence that a
 * partially downloaded album is officially a single; never persist it as
 * ReleaseGroup truth or use it for metadata filters.
 *
 * Unknown/explicitly other types stay in "Lançamentos". A typed Album,
 * Single or EP with a verified full release date may become the hero release;
 * browse-only classifications cannot claim a new release.
 */
internal fun canonicalArtistReleaseSections(
    releases: List<ReleaseEntity>,
    evidence: Map<String, CanonicalArtistReleaseEvidence>,
    recordingCountsByRelease: Map<String, Int> = emptyMap(),
): CanonicalArtistReleaseSections {
    fun browseKind(release: ReleaseEntity): String {
        val type = evidence[release.id]?.primaryType?.trim()?.lowercase(java.util.Locale.ROOT)
        return when {
            type == "album" -> "album"
            type == "single" || type == "ep" -> "single"
            type.isNullOrEmpty() -> when (recordingCountsByRelease[release.id]) {
                1 -> "single"
                null, 0 -> "other"
                else -> "album"
            }
            else -> "other"
        }
    }
    val categorized = releases.groupBy(::browseKind)
    val albums = categorized["album"].orEmpty()
    val singles = categorized["single"].orEmpty()
    val other = categorized["other"].orEmpty()
    val validDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    val recent = albums.mapNotNull { album ->
        val proof = evidence[album.id]
        if (proof?.primaryType?.trim()?.equals("Album", ignoreCase = true) != true) {
            null
        } else {
            proof.firstReleaseDate?.takeIf { validDate.matches(it) }?.let { day ->
                day to album
            }
        }
    }.maxWithOrNull(compareBy<Pair<String, ReleaseEntity>> { it.first }
        .thenBy { it.second.id })?.second
    // The latest genuinely dated release may be a Single or EP, not only an
    // Album. Do not infer dates or types from a local track count.
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val latest = releases.mapNotNull { release ->
        val proof = evidence[release.id] ?: return@mapNotNull null
        if (proof.primaryType?.trim()?.lowercase(java.util.Locale.ROOT) !in
            setOf("album", "single", "ep")) return@mapNotNull null
        val date = proof.firstReleaseDate?.takeIf { validDate.matches(it) }
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { !it.isAfter(today) } ?: return@mapNotNull null
        date to release
    }.maxWithOrNull(compareBy<Pair<java.time.LocalDate, ReleaseEntity>> { it.first }
        .thenBy { it.second.id })?.second
    return CanonicalArtistReleaseSections(albums, singles, other, recent, latest)
}

/** Album detail includes all currently eligible recordings in this release,
 * not just recordings credited to the artist that led to this page. */
internal fun canonicalArtistReleaseTrackIds(
    availableRecordingIds: List<String>,
    releaseRecordingIds: Set<String>,
): List<String> = availableRecordingIds.filter { it in releaseRecordingIds }.distinct()

/** A recording or album cover is not evidence of an artist portrait. */
internal fun canonicalArtistHeroPhotoUrl(
    artistPhotoUrl: String?,
    recordingArtworkUrl: String?,
): String? = artistPhotoUrl?.takeIf { it.isNotBlank() }

/** Keep the original DetailScreen contract: search opens from the toolbar. */
internal fun shouldShowNativeArtistSearch(searching: Boolean, query: String): Boolean =
    searching || query.isNotBlank()

/** Artist photo is optional; leave no 60%-height empty canvas when it is missing. */
internal fun shouldCompactNativeArtistHero(artistPhotoUrl: String?): Boolean =
    artistPhotoUrl.isNullOrBlank()
