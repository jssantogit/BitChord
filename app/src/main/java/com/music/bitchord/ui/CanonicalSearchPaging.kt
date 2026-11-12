package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * Cursor ownership is per upstream entity type, never based on the number of
 * visible MusicBrainz rows (which may be filtered or deduplicated).
 *
 * A failed continuation leaves that entity type at its previous offset so
 * the user can retry without skipping any MusicBrainz results.
 */
internal data class CanonicalSearchPageCursors(
    val query: String,
    val recordings: Int?,
    val groups: Int?,
    val releases: Int?,
    val artists: Int?,
    /** Null for text search; stable MusicBrainz artist MBID for scoped works. */
    val artistScopeMbid: String? = null,
) {
    val hasMore: Boolean get() = listOf(recordings, groups, releases, artists).any { it != null }

    fun advance(
        recordings: MusicBrainzCatalogSearchResult<*>? = null,
        groups: MusicBrainzCatalogSearchResult<*>? = null,
        releases: MusicBrainzCatalogSearchResult<*>? = null,
        artists: MusicBrainzCatalogSearchResult<*>? = null,
    ): CanonicalSearchPageCursors = copy(
        recordings = nextOrRetain(this.recordings, recordings),
        groups = nextOrRetain(this.groups, groups),
        releases = nextOrRetain(this.releases, releases),
        artists = nextOrRetain(this.artists, artists),
    )

    companion object {
        fun initial(
            query: String,
            recordings: MusicBrainzCatalogSearchResult<*>,
            groups: MusicBrainzCatalogSearchResult<*>,
            releases: MusicBrainzCatalogSearchResult<*>,
            artists: MusicBrainzCatalogSearchResult<*>,
            artistScopeMbid: String? = null,
        ): CanonicalSearchPageCursors = CanonicalSearchPageCursors(
            query = query,
            recordings = initialOffset(recordings),
            groups = initialOffset(groups),
            releases = initialOffset(releases),
            artists = initialOffset(artists),
            artistScopeMbid = artistScopeMbid,
        )

        private fun initialOffset(page: MusicBrainzCatalogSearchResult<*>): Int? =
            when (page) {
                is MusicBrainzCatalogSearchResult.Success<*> -> page.page.nextOffset
                is MusicBrainzCatalogSearchResult.Failure -> 0
            }

        private fun nextOrRetain(
            previous: Int?,
            page: MusicBrainzCatalogSearchResult<*>?,
        ): Int? = if (previous == null) null else when (page) {
            is MusicBrainzCatalogSearchResult.Success<*> -> page.page.nextOffset
            else -> previous
        }
    }
}

/**
 * Progressive artist browse pages ADD new ReleaseGroups. They must never
 * replace earlier pages, artwork, or an edition-verified editorial display
 * title (e.g. NewJeans: How Sweet instead of the raw composite Group label).
 * Exact MBIDs, not displayed titles, define whether a work was already shown.
 *
 * A full authoritative browse can still reconcile obsolete membership only
 * after its completeness gate; this is strictly a presentation merge.
 */
internal fun canonicalArtistContinuationRows(
    previous: List<CanonicalCatalogSearchRow>,
    incoming: List<CanonicalCatalogSearchRow>,
): List<CanonicalCatalogSearchRow> =
    (previous + incoming).distinctBy {
        it.kind to it.musicBrainzId.lowercase(java.util.Locale.ROOT)
    }

/**
 * Search already fetched exact Release -> ReleaseGroup MBIDs. Reuse those
 * Official, audio-only editions to resolve composite user-facing labels
 * immediately, without another HTTP request. Keep the ReleaseGroup MBID,
 * raw Room title, classification, and all unrelated search rows untouched.
 *
 * Example: NewJeans' editorial Group "How Sweet & Bubble Gum" contains
 * Official editions titled "How Sweet". A matching MBID and the existing
 * strictly leading-title rule are required; free-text matching is forbidden.
 */
internal fun canonicalSearchRowsWithOfficialEditionTitles(
    rows: List<CanonicalCatalogSearchRow>,
    editions: List<MusicBrainzReleaseHit>,
): List<CanonicalCatalogSearchRow> {
    if (rows.none { it.kind == CanonicalSearchKind.RELEASE_GROUP && it.title.contains(" & ") } ||
        editions.isEmpty()) return rows
    val byGroup = editions.filter {
        it.releaseGroupMbid != null && isCuratedMusicBrainzRelease(it)
    }.groupBy { requireNotNull(it.releaseGroupMbid).lowercase(java.util.Locale.ROOT) }
    return rows.map { row ->
        if (row.kind != CanonicalSearchKind.RELEASE_GROUP || !row.title.contains(" & "))
            return@map row
        val official = byGroup[row.musicBrainzId.lowercase(java.util.Locale.ROOT)].orEmpty()
        val title = canonicalOfficialGroupDisplayTitle(row.title, official)
        if (title == row.title) row else row.copy(title = title)
    }
}

/** Live facts win over cache. Only failed entity kinds may use stale evidence. */
internal fun mergeCanonicalSearchRows(
    live: List<CanonicalCatalogSearchRow>,
    cached: List<CanonicalCatalogSearchRow>,
    failedKinds: Set<CanonicalSearchKind>,
): List<CanonicalCatalogSearchRow> =
    (live + cached.filter { it.kind in failedKinds })
        .distinctBy { it.kind to it.musicBrainzId }


/**
 * Fast first paint: the first rate-limited MusicBrainz response should not
 * hide already indexed results while the remaining three endpoints load.
 * A fresh typed identity replaces only its own cached counterpart.
 * The complete search still publishes its authoritative final ordering.
 */
internal fun canonicalProgressiveSearchPreview(
    existing: List<CanonicalCatalogSearchRow>,
    fresh: List<CanonicalCatalogSearchRow>,
): List<CanonicalCatalogSearchRow> =
    (fresh + existing).distinctBy { it.kind to it.musicBrainzId }
