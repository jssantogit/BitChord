package com.music.bitchord

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * Simple source-scoped artist catalog checkpoint. This function stays outside
 * BitChordApp's monolithic Compose method (which hit JVM's 64KiB code limit).
 */
internal data class DiscogsArtistCatalogContext(
    val complete: Boolean,
    val titles: Set<String>,
)

internal fun discogsArtistCatalogContext(
    currentBrowseId: String?,
    pageBrowseId: String,
    artistMbid: String?,
    rows: List<CanonicalCatalogSearchRow>?,
    nextOffset: Int?,
    totalCount: Int?,
): DiscogsArtistCatalogContext {
    if (artistMbid == null ||
        currentBrowseId != pageBrowseId ||
        rows.isNullOrEmpty() ||
        rows.any { it.kind != CanonicalSearchKind.RELEASE_GROUP || it.fromOfflineCache }
    ) return DiscogsArtistCatalogContext(false, emptySet())
    val complete = nextOffset == null && totalCount != null && rows.size >= totalCount
    return DiscogsArtistCatalogContext(
        complete = complete,
        titles = rows.mapTo(LinkedHashSet()) { it.title },
    )
}
