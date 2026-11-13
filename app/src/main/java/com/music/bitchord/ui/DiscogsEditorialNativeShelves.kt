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
    if (shelves.isEmpty()) return shelves
    val typedHeadings = setOf(albumsHeading, singlesHeading)
    val groupsById = groups.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .groupBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val visible = shelves.filter { it.title in typedHeadings }.flatMap { it.items }
        .mapNotNull { card ->
            val id = card.browseId?.removePrefix("catalog:RELEASE_GROUP:")
                ?.takeIf { "catalog:RELEASE_GROUP:$it" == card.browseId }
                ?.lowercase(java.util.Locale.ROOT) ?: return@mapNotNull null
            id to card
        }.groupBy({ it.first }, { it.second })

    val redundantIds = musicBrainzLinkedTitleTrackSingles.mapNotNull { (singleId, epId) ->
        val singleCard = visible[singleId]?.singleOrNull() ?: return@mapNotNull null
        val parentCard = visible[epId]?.singleOrNull() ?: return@mapNotNull null
        // The group browse is independent and can lag the native typed
        // shelf. Never require BOTH lookups merely to recognize two exact
        // source-linked MBIDs that are already present as native cards.
        // A conflicting decoded group or an unrelated title still vetoes it.
        val singleRows = groupsById[singleId].orEmpty()
        val parentRows = groupsById[epId].orEmpty()
        if (singleRows.size > 1 || parentRows.size > 1) return@mapNotNull null
        val single = singleRows.singleOrNull()
        val parent = parentRows.singleOrNull()
        val singleTitle = single?.title ?: singleCard.title
        val parentTitle = parent?.title ?: parentCard.title
        val singleKind = single?.releaseKind ?:
            singleCard.subtitle.substringBefore("·").trim()
        val parentKind = parent?.releaseKind ?:
            parentCard.subtitle.substringBefore("·").trim()
        if (!singleKind.equals("Single", ignoreCase = true) ||
            !parentKind.equals("EP", ignoreCase = true) ||
            !singleCard.title.trim().equals(parentCard.title.trim(), ignoreCase = true) ||
            !singleTitle.trim().equals(parentTitle.trim(), ignoreCase = true) ||
            !singleTitle.trim().equals(singleCard.title.trim(), ignoreCase = true) ||
            !parentTitle.trim().equals(parentCard.title.trim(), ignoreCase = true) ||
            (single != null && parent != null &&
                (single.subtitle.isNullOrBlank() ||
                    !single.subtitle.equals(parent.subtitle, ignoreCase = true)))
        ) return@mapNotNull null
        "catalog:RELEASE_GROUP:$singleId"
    }.toSet()

    if (redundantIds.isEmpty()) return shelves
    return shelves.map { shelf ->
        if (shelf.title !in typedHeadings) return@map shelf
        val kept = shelf.items.filterNot { it.browseId in redundantIds }
        if (kept.size == shelf.items.size) shelf else shelf.copy(items = kept)
    }.filter { it.items.isNotEmpty() }
}

/**
 * AnimatedContent must treat one artist+section as one navigation destination.
 * The list of cards, cover URLs and their subtitles are asynchronously
 * enriched and are NOT a screen identity. Keying by a whole HomeShelf caused
 * repeated crossfades and recreated the LazyVerticalGrid state on long artist
 * discographies, sending the user back to the top after each cover/page update.
 *
 * Keep the MB artist identity in the key so opening the same named section
 * for a different artist cannot inherit the previous artist's grid position.
 */
internal fun canonicalArtistExpandedShelfContentKey(
    artistBrowseId: String,
    shelf: HomeShelf?,
): Pair<String, String>? = shelf?.let { artistBrowseId to it.title }

/**
 * An expanded artist grid has historically held a snapshot of the shelf
 * captured at tap time. Network pagination and editorial projections update
 * the artist carousel, but the expanded page continued showing its *stale*
 * cards. Resolve by stable section title on every recomposition instead.
 * Never replace independent recommendation/editorial shelves.
 */
internal fun canonicalArtistCurrentExpandedShelf(
    opened: HomeShelf?,
    current: List<HomeShelf>,
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
): HomeShelf? {
    if (opened == null || opened.title !in setOf(albumsHeading, singlesHeading)) return opened
    return current.firstOrNull { it.title == opened.title }
}

/**
 * MusicBrainz may use "BOOMPALA" as the ReleaseGroup title for several remixes
 * while carrying the actual differentiating collaboration in artist-credit.
 * Disambiguate ONLY on a collision and ONLY from that typed group credit.
 * The original MBID, search identity, tracklists and edition details never
 * change; unknown credits remain unknown instead of receiving guessed labels.
 */
internal fun canonicalArtistDisambiguateCreditedCollisions(
    shelves: List<HomeShelf>,
    groups: List<CanonicalCatalogSearchRow>,
    artistName: String,
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
): List<HomeShelf> {
    val artist = artistName.trim()
    if (artist.isEmpty() || shelves.isEmpty() || groups.isEmpty()) return shelves
    val typedHeadings = setOf(albumsHeading, singlesHeading)
    val groupsById = groups.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .groupBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val visibleCards = shelves.filter { it.title in typedHeadings }.flatMap { it.items }
    val duplicates = visibleCards.groupingBy {
        it.title.trim().lowercase(java.util.Locale.ROOT)
    }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isEmpty()) return shelves

    fun qualifiedTitle(card: ShelfItem): String {
        if (card.title.trim().lowercase(java.util.Locale.ROOT) !in duplicates) return card.title
        val browseId = card.browseId ?: return card.title
        val mbid = browseId.removePrefix("catalog:RELEASE_GROUP:")
            .takeIf { "catalog:RELEASE_GROUP:$it" == browseId }
            ?.lowercase(java.util.Locale.ROOT) ?: return card.title
        val group = groupsById[mbid]?.singleOrNull() ?: return card.title
        if (!group.title.trim().equals(card.title.trim(), ignoreCase = true)) return card.title
        val credit = group.subtitle?.trim().orEmpty()
        if (!credit.startsWith(artist, ignoreCase = true) || credit.length <= artist.length)
            return card.title
        val remainder = credit.substring(artist.length).trim()
        val qualifier = when {
            remainder.startsWith("feat. ", ignoreCase = true) &&
                remainder.length > "feat. ".length -> remainder
            remainder.startsWith("& ") && remainder.length > 2 ->
                "com " + remainder.substring(2).trim()
            else -> return card.title
        }
        return "${card.title} ($qualifier)"
    }

    var changed = false
    val updated = shelves.map { shelf ->
        if (shelf.title !in typedHeadings) return@map shelf
        val newItems = shelf.items.map { card ->
            val title = qualifiedTitle(card)
            if (title == card.title) card else {
                changed = true
                card.copy(title = title)
            }
        }
        if (newItems == shelf.items) shelf else shelf.copy(items = newItems)
    }
    return if (changed) updated else shelves
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
    artistName: String = "",
): List<HomeShelf> {
    fun polish(shelves: List<HomeShelf>): List<HomeShelf> =
        canonicalArtistDisambiguateCreditedCollisions(
            canonicalArtistCollapseLinkedTitleTrackSingles(
                shelves, musicBrainzGroups, albumsHeading, singlesHeading,
            ),
            musicBrainzGroups, artistName, albumsHeading, singlesHeading,
        )
    if (groupProofs.isEmpty() || musicBrainzGroups.isEmpty()) {
        return polish(original)
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
        return polish(original)
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
    return polish(buildList {
        if (albums.isNotEmpty()) add(
            original.firstOrNull { it.title == albumsHeading }?.copy(items = albums)
                ?: HomeShelf(albumsHeading, albums)
        )
        if (singles.isNotEmpty()) add(
            original.firstOrNull { it.title == singlesHeading }?.copy(items = singles)
                ?: HomeShelf(singlesHeading, singles)
        )
        addAll(extras)
    })
}
