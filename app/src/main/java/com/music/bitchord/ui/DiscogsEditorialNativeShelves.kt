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
 * Metadata-only release cards shared by native Home and Explore. The Discogs
 * Release URL is resolved from an exact temporary source record on click;
 * the browseId namespace cannot be confused with MusicBrainz or audio.
 */
internal fun discogsSourceOnlyCatalogShelves(
    releases: List<com.music.bitchord.data.catalog.metadata.discogs.DiscogsShadowRelease>,
): List<HomeShelf> {
    if (releases.isEmpty()) return emptyList()
    val sections = listOf(
        com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialShelf.ALBUMS to
            "Álbuns · Discogs",
        com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialShelf.EPS to
            "EPs · Discogs",
        com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialShelf.SINGLES to
            "Singles · Discogs",
    )
    return sections.mapNotNull { (kind, title) ->
        val rows = releases.filter { it.shelf == kind }.take(8)
        if (rows.isEmpty()) null else HomeShelf(
            title = title,
            items = rows.map { release ->
                ShelfItem(
                    title = release.title,
                    subtitle = "${release.kind.displayName} · ${release.artist} · só metadados",
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = "discogs:release:${release.discogsReleaseId}",
                )
            },
        )
    }
}

/**
 * Read-only overlay for native Home and Explore release rows. The original
 * MusicBrainz primary type remains untouched in Room and source caches.
 * Callers may recompose when the short-lived group-proof snapshot changes.
 */
internal fun discogsEditorialProjectedRows(
    rows: List<CanonicalCatalogSearchRow>,
    groupProofs: Map<String, List<DiscogsGroupTypeProof>>,
): List<CanonicalCatalogSearchRow> {
    if (groupProofs.isEmpty()) return rows
    var changed = false
    val projected = rows.map { row ->
        if (row.kind != CanonicalSearchKind.RELEASE_GROUP) return@map row
        val proof = groupProofs[row.musicBrainzId] ?: return@map row
        val decided = DiscogsTypeConsensus.resolve(row.releaseKind, proof)
            as? DiscogsTypeResolution.Decided ?: return@map row
        if (decided.source !in setOf(
                DiscogsTypeSource.DISCOGS_CORRECTION,
                DiscogsTypeSource.DISCOGS_FILL,
            )
        ) return@map row
        val type = when (decided.kind) {
            DiscogsEditorialKind.ALBUM -> "Album"
            DiscogsEditorialKind.EP -> "EP"
            DiscogsEditorialKind.SINGLE -> "Single"
            else -> return@map row
        }
        if (row.releaseKind == type) row else {
            changed = true
            row.copy(releaseKind = type)
        }
    }
    return if (changed) projected else rows
}

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
    val cards = original.filter { it.title == albumsHeading || it.title == singlesHeading }
        .flatMap { it.items }.mapNotNull { card ->
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
    val retained = original.filter { it.title == albumsHeading || it.title == singlesHeading }
        .flatMap { it.items }.filter { it.browseId !in affectedBrowseIds }
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
