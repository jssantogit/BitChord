package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * Catalog metadata is data for the existing Android Home card layout, not a
 * new provider-specific Home screen. A browseId is strictly navigation-only;
 * a public MusicBrainz MBID is NEVER a stream videoId.
 *
 * Cached artists may include credited guests, producers and collaborators.
 * Without a trusted primary artist / ownership edge, do not recommend them
 * as if they were the user's main library artists.
 */
internal fun canonicalBrowseCardId(row: CanonicalCatalogSearchRow): String? =
    when (row.kind) {
        CanonicalSearchKind.RELEASE, CanonicalSearchKind.RELEASE_GROUP ->
            "catalog:${row.kind}:${row.musicBrainzId}"
        // The click is gated again by the playback planner; this is a
        // navigation/action key, NEVER a videoId or a streaming URL.
        CanonicalSearchKind.RECORDING ->
            if (row.hasCatalogedAsset) "catalog:RECORDING:${row.musicBrainzId}" else null
        CanonicalSearchKind.ARTIST -> null
    }

internal fun canonicalExistingHomeShelves(
    downloaded: List<ShelfItem>,
    playlists: List<ShelfItem>,
    metadata: List<CanonicalCatalogSearchRow>,
    downloadedHeading: String,
    playlistHeading: String,
    cachedHeading: String,
): List<HomeShelf> = buildList {
    if (downloaded.isNotEmpty()) add(HomeShelf(downloadedHeading, downloaded))
    if (playlists.isNotEmpty()) add(HomeShelf(playlistHeading, playlists))
    val cachedBrowseCards = metadata.asSequence()
        .filter { it.fromOfflineCache }
        .mapNotNull { row ->
            canonicalBrowseCardId(row)?.let { browse ->
                ShelfItem(
                    title = row.title,
                    subtitle = row.subtitle.orEmpty(),
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = browse,
                )
            }
        }
        .distinctBy { it.browseId }
        .take(24)
        .toList()
    if (cachedBrowseCards.isNotEmpty()) add(HomeShelf(cachedHeading, cachedBrowseCards))
}
