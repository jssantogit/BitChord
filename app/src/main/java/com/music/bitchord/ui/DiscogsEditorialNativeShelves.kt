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
 * The MusicBrainz site explicitly links the LE SSERAFIM HOT title-track
 * Single release-group to its parent HOT EP as "single/EP taken from".
 * These are DIFFERENT canonical MBIDs; both remain available in Search,
 * release detail, Library and the canonical graph. The artist's featured
 * discography, however, must not display the derivative identically named
 * Single as a second main work beside its parent EP.
 *
 * This is intentionally an exact-MBID, source-evidenced association,
 * not a same-title heuristic. In particular, HOT (English ver.) EP and
 * HOT (English ver.) Single are distinct products and must stay visible.
 * See https://musicbrainz.org/release-group/44adb7df-66bd-4ebe-a43a-3ea45a93529d
 * and https://musicbrainz.org/release-group/50bcea80-692f-4e36-a7a5-825a3233cb13
 */
private val musicBrainzLinkedTitleTrackSingles = mapOf(
    "44adb7df-66bd-4ebe-a43a-3ea45a93529d" to
        "50bcea80-692f-4e36-a7a5-825a3233cb13",
)

internal fun canonicalArtistCollapseLinkedTitleTrackSingles(
    shelves: List<HomeShelf>,
    groups: List<CanonicalCatalogSearchRow>,
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
): List<HomeShelf> {
    if (shelves.isEmpty() || groups.isEmpty()) return shelves
    val typedHeadings = setOf(albumsHeading, singlesHeading)
    val groupsById = groups.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .groupBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val visible = shelves.filter { it.title in typedHeadings }.flatMap { it.items }
        .mapNotNull { it.browseId?.removePrefix("catalog:RELEASE_GROUP:")
            ?.takeIf { id -> it.browseId == "catalog:RELEASE_GROUP:$id" }
            ?.lowercase(java.util.Locale.ROOT) }
        .toSet()
    val duplicateIds = musicBrainzLinkedTitleTrackSingles.mapNotNull { (singleId, epId) ->
        val single = groupsById[singleId]?.singleOrNull() ?: return@mapNotNull null
        val ep = groupsById[epId]?.singleOrNull() ?: return@mapNotNull null
        // The linked parent must actually be on the screen. Progressive
        // paging/cached partial results may not yet include the parent.
        if (singleId !in visible || epId !in visible ||
            !single.releaseKind.equals("Single", ignoreCase = true) ||
            !ep.releaseKind.equals("EP", ignoreCase = true) ||
            single.title.trim() != ep.title.trim() ||
            single.subtitle.isNullOrBlank() ||
            !single.subtitle.equals(ep.subtitle, ignoreCase = true)
        ) null else "catalog:RELEASE_GROUP:$singleId"
    }.toSet()
    if (duplicateIds.isEmpty()) return shelves
    return shelves.map { shelf ->
        if (shelf.title !in typedHeadings) return@map shelf
        val retained = shelf.items.filterNot { it.browseId in duplicateIds }
        if (retained.size == shelf.items.size) shelf else shelf.copy(items = retained)
    }.filter { it.items.isNotEmpty() }
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
    if (groupProofs.isEmpty() || musicBrainzGroups.isEmpty()) {
        return canonicalArtistCollapseLinkedTitleTrackSingles(
            original, musicBrainzGroups, albumsHeading, singlesHeading,
        )
    }
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
    if (changes.isEmpty()) {
        return canonicalArtistCollapseLinkedTitleTrackSingles(
            original, musicBrainzGroups, albumsHeading, singlesHeading,
        )
    }

    val affectedBrowseIds = changes.keys.mapTo(HashSet()) {
        "catalog:RELEASE_GROUP:$it"
    }
    // Retain the already loaded native cards in their ORIGINAL shelf when
    // this artist browse page does not contain the corresponding MB group.
    // An incremental browse must not silently delete cards on a proof update.
    fun retained(title: String) = original.filter { it.title == title }
        .flatMap { it.items }.filter { it.browseId !in affectedBrowseIds }

    val moved = changes.map { (id, item) ->
        val row = item.first
        val browseId = "catalog:RELEASE_GROUP:$id"
        val previous = cards[browseId]
        val subtitle = item.second + " · Discogs"
        val card = (previous ?: ShelfItem(row.title, subtitle, row.artworkUri, null, browseId))
            .copy(subtitle = subtitle)
        Triple(item.second, row.releaseDate.orEmpty(), card)
    }.sortedWith(compareByDescending<Triple<String, String, ShelfItem>> { it.second }
        .thenBy { it.third.browseId.orEmpty() })

    val albums = (retained(albumsHeading) +
        moved.filter { it.first == "Album" }.map { it.third })
        .distinctBy { it.browseId }
    val singles = (retained(singlesHeading) +
        moved.filter { it.first == "EP" || it.first == "Single" }.map { it.third })
        .distinctBy { it.browseId }
    val extras = original.filterNot { it.title in setOf(albumsHeading, singlesHeading) }
    return canonicalArtistCollapseLinkedTitleTrackSingles(buildList {
        if (albums.isNotEmpty()) add(
            original.firstOrNull { it.title == albumsHeading }?.copy(items = albums)
                ?: HomeShelf(albumsHeading, albums)
        )
        if (singles.isNotEmpty()) add(
            original.firstOrNull { it.title == singlesHeading }?.copy(items = singles)
                ?: HomeShelf(singlesHeading, singles)
        )
        addAll(extras)
    }, musicBrainzGroups, albumsHeading, singlesHeading)
}
