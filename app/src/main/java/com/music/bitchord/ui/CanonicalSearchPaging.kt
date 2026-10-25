package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
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
        ): CanonicalSearchPageCursors = CanonicalSearchPageCursors(
            query = query,
            recordings = (recordings as? MusicBrainzCatalogSearchResult.Success<*>)?.page?.nextOffset,
            groups = (groups as? MusicBrainzCatalogSearchResult.Success<*>)?.page?.nextOffset,
            releases = (releases as? MusicBrainzCatalogSearchResult.Success<*>)?.page?.nextOffset,
            artists = (artists as? MusicBrainzCatalogSearchResult.Success<*>)?.page?.nextOffset,
        )

        private fun nextOrRetain(
            previous: Int?,
            page: MusicBrainzCatalogSearchResult<*>?,
        ): Int? = if (previous == null) null else when (page) {
            is MusicBrainzCatalogSearchResult.Success<*> -> page.page.nextOffset
            else -> previous
        }
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

