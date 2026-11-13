package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzReleaseGroup
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * The cached first-paint and typeahead path must obey the SAME editorial
 * gate as live General Search. Raw Room MusicBrainz data stays intact; only
 * the public search projection is curated.
 */
internal fun canonicalCuratedCachedMusicBrainzGroups(
    groups: List<MusicBrainzReleaseGroupHit>,
): List<MusicBrainzReleaseGroupHit> =
    groups.filter(::isCuratedMusicBrainzReleaseGroup)

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

/**
 * Group cards represent published works; Release cards represent editions.
 * Show one mainstream card when an exact Release -> Group MBID and identical
 * display title prove a redundant edition. Do NOT deduplicate by title alone:
 * LE SSERAFIM's EASY EP and EASY (English ver.) Single have different Group
 * identities, and differently named physical/territorial editions remain
 * discoverable.
 *
 * Unknown membership is never a reason to suppress an edition. This changes
 * search presentation only, not Room release/edition identities.
 */
internal fun canonicalSearchRowsWithoutRedundantEditions(
    rows: List<CanonicalCatalogSearchRow>,
): List<CanonicalCatalogSearchRow> {
    val groups = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .associateBy { it.musicBrainzId.trim().lowercase(java.util.Locale.ROOT) }
    if (groups.isEmpty()) return rows
    fun normalizedTitle(value: String): String =
        value.trim().replace(Regex("""\s+"""), " ").lowercase(java.util.Locale.ROOT)
    // The search subtitle is presentation text, not a typed credit: some
    // release rows include only territory/date. Compare artist names only
    // when both rows actually expose them; absent evidence is inconclusive.
    fun visibleArtistCredit(subtitle: String?): String? {
        val first = subtitle?.trim()?.substringBefore(" · ")?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        if (first.matches(Regex("""[A-Z]{2}""")) ||
            first.matches(Regex("""\d{4}(-\d{2}){0,2}"""))) return null
        return first
    }
    // Consolidation must not throw away a cover already verified for the
    // exact Release. A group often arrives AFTER its release in progressive
    // search and may not have resolved its own CAA cover yet. Reuse only the
    // artwork of a redundant edition whose Group MBID is explicit and whose
    // display title equals this Group. Never borrow from a similarly named
    // edition belonging to another Group or override existing group art.
    val suppressed = mutableSetOf<Int>()
    val editionCoversByGroup = mutableMapOf<String, String>()
    rows.forEachIndexed { index, row ->
        if (row.kind != CanonicalSearchKind.RELEASE) return@forEachIndexed
        val groupId = row.coverGroupMbid
            ?.trim()?.lowercase(java.util.Locale.ROOT) ?: return@forEachIndexed
        val group = groups[groupId] ?: return@forEachIndexed
        if (normalizedTitle(row.title) != normalizedTitle(group.title))
            return@forEachIndexed
        // Same work MBID and title do not make independently credited
        // editions indistinguishable. If BOTH artist credits are visible,
        // keep a differently credited edition and its sleeve separate.
        val groupArtist = visibleArtistCredit(group.subtitle)
            ?.takeUnless { it.equals(group.releaseKind, ignoreCase = true) }
        val releaseArtist = visibleArtistCredit(row.subtitle)
        if (groupArtist != null && releaseArtist != null &&
            normalizedTitle(groupArtist) != normalizedTitle(releaseArtist))
            return@forEachIndexed
        suppressed += index
        row.artworkUri?.takeUnless(String::isBlank)?.let {
            editionCoversByGroup.putIfAbsent(groupId, it)
        }
    }
    return rows.mapIndexedNotNull { index, row ->
        if (index in suppressed) return@mapIndexedNotNull null
        if (row.kind != CanonicalSearchKind.RELEASE_GROUP ||
            !row.artworkUri.isNullOrBlank()) return@mapIndexedNotNull row
        val groupId = row.musicBrainzId.trim().lowercase(java.util.Locale.ROOT)
        val cover = editionCoversByGroup[groupId] ?: return@mapIndexedNotNull row
        row.copy(artworkUri = cover)
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
): List<CanonicalCatalogSearchRow> {
    val priorById = existing.associateBy {
        it.kind to it.musicBrainzId.lowercase(java.util.Locale.ROOT)
    }
    val enriched = fresh.map { row ->
        val old = priorById[row.kind to row.musicBrainzId.lowercase(java.util.Locale.ROOT)]
        if (row.kind != CanonicalSearchKind.RELEASE_GROUP || old == null) row
        else {
            // A later partial page can repeat the raw Group after an
            // Official edition has already supplied the exact Group's
            // display title and sleeve. Never downgrade those two fields
            // just because the later duplicate omits enrichment.
            val leading = row.title.substringBefore(" & ").trim()
            val retainsOfficialTitle = row.title.contains(" & ") &&
                leading.equals(old.title.trim(), ignoreCase = true)
            row.copy(
                title = if (retainsOfficialTitle) old.title else row.title,
                artworkUri = row.artworkUri?.takeIf(String::isNotBlank) ?: old.artworkUri,
                releaseKind = row.releaseKind ?: old.releaseKind,
            )
        }
    }
    return canonicalSearchRowsWithoutRedundantEditions(
        (enriched + existing).distinctBy {
            it.kind to it.musicBrainzId.lowercase(java.util.Locale.ROOT)
        },
    )
}
