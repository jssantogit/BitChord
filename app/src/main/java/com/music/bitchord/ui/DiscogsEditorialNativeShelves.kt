package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsGroupTypeProof
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsTypeConsensus
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsTypeResolution
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsTypeSource
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialKind
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * Source-scoped overrides of existing native artist shelves. The MBID remains
 * the browse identity; never write this derived classification into MusicBrainz
 * Room tables. Disputes retain the canonical MusicBrainz classification.
 */
internal fun discogsEditorialArtistShelves(
    original: List<HomeShelf>,
    musicBrainzGroups: List<CanonicalCatalogSearchRow>,
    groupProofs: Map<String, List<DiscogsGroupTypeProof>>,
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
): List<HomeShelf> {
    if (groupProofs.isEmpty() || musicBrainzGroups.isEmpty()) return original
    val evidenceByGroup = musicBrainzGroups.filter {
        it.kind == CanonicalSearchKind.RELEASE_GROUP
    }.associateBy { it.musicBrainzId }
    val cards = original.flatMap { it.items }.mapNotNull { card ->
        card.browseId?.let { it to card }
    }.toMap()
    val changes = evidenceByGroup.mapNotNull { (id, row) ->
        val proofs = groupProofs[id].orEmpty()
        if (proofs.isEmpty()) return@mapNotNull null
        val decided = DiscogsTypeConsensus.resolve(row.releaseKind, proofs)
            as? DiscogsTypeResolution.Decided ?: return@mapNotNull null
        if (decided.source !in setOf(DiscogsTypeSource.DISCOGS_CORRECTION,
                DiscogsTypeSource.DISCOGS_FILL)
        ) return@mapNotNull null
        val format = when (decided.kind) {
            DiscogsEditorialKind.ALBUM -> "Album"
            DiscogsEditorialKind.EP -> "EP"
            DiscogsEditorialKind.SINGLE -> "Single"
            else -> return@mapNotNull null
        }
        id to (row to format)
    }.toMap()
    if (changes.isEmpty()) return original

    val affectedBrowseIds = changes.keys.mapTo(HashSet()) {
        "catalog:RELEASE_GROUP:$it"
    }
    val retained = original.flatMap { it.items }.filter { it.browseId !in affectedBrowseIds }
    val moved = changes.map { (id, item) ->
        val row = item.first
        val browseId = "catalog:RELEASE_GROUP:$id"
        val previous = cards[browseId]
        val subtitle = item.second + " · Discogs"
        (previous ?: ShelfItem(row.title, subtitle, row.artworkUri, null, browseId))
            .copy(subtitle = subtitle)
    }
    val byId = evidenceByGroup.values.associateBy {
        "catalog:RELEASE_GROUP:${it.musicBrainzId}"
    }
    val sorted = (retained + moved).distinctBy { it.browseId }
        .sortedWith(compareByDescending<ShelfItem> {
            byId[it.browseId]?.releaseDate.orEmpty()
        }.thenBy { it.browseId.orEmpty() })
    val albums = sorted.filter { card ->
        val id = card.browseId?.removePrefix("catalog:RELEASE_GROUP:")
        val format = changes[id]?.second ?: canonicalEditorialReleaseType(
            evidenceByGroup[id] ?: return@filter false
        )
        format == "Album"
    }
    val singles = sorted.filter { card ->
        val id = card.browseId?.removePrefix("catalog:RELEASE_GROUP:")
        val format = changes[id]?.second ?: canonicalEditorialReleaseType(
            evidenceByGroup[id] ?: return@filter false
        )
        format == "Single" || format == "EP"
    }
    val extras = original.filterNot { it.title in setOf(albumsHeading, singlesHeading) }
    return buildList {
        if (albums.isNotEmpty()) add(
            original.firstOrNull { it.title == albumsHeading }?.copy(items = albums)
                ?: HomeShelf(albumsHeading, albums)
        )
        if (singles.isNotEmpty()) add(
            original.firstOrNull { it.title == singlesHeading }?.copy(items = singles)
                ?: HomeShelf(singlesHeading, singles)
        )
        addAll(extras)
    }
}
