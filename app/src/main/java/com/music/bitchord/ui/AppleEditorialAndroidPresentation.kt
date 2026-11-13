package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem

/**
 * Explicit Apple storefront routing. A source ID must NEVER be interpreted
 * as a MusicBrainz MBID or a YouTube browse ID. MainActivity must route
 * these independently when the Apple-first UI cutover is implemented.
 */
internal data class AppleCatalogBrowseRoute(
    val storefront: String,
    val kind: ItunesCatalogItemType,
    val id: Long,
) {
    companion object {
        private val identity = Regex("^itunes:([a-z]{2}):(artist|album|track):([1-9][0-9]*)$")

        fun parse(value: String?): AppleCatalogBrowseRoute? {
            val match = value?.let { identity.matchEntire(it) } ?: return null
            val id = match.groupValues[3].toLongOrNull()?.takeIf { it > 0L }
                ?: return null
            val kind = when (match.groupValues[2]) {
                "artist" -> ItunesCatalogItemType.ARTIST
                "album" -> ItunesCatalogItemType.ALBUM
                "track" -> ItunesCatalogItemType.TRACK
                else -> return null
            }
            return AppleCatalogBrowseRoute(match.groupValues[1], kind, id)
        }
    }
}

/** Headings are provided by Android localized resources, never hardcoded here. */
internal data class AppleEditorialShelfHeadings(
    val albums: String,
    val eps: String,
    val singles: String,
    val unclassified: String,
)

/**
 * Native, source-typed cards: Apple owns the visible release list, titles,
 * artwork and separate edition IDs. Ratings/playback are NOT synthesized.
 * Only pass these cards into Home/Explore when a matching Apple browse route
 * is installed; otherwise click would be interpreted by the old MB renderer.
 */
internal fun appleEditorialShelves(
    entries: List<AppleTriSourceEditorialEntry>,
    headings: AppleEditorialShelfHeadings,
    maxPerShelf: Int = 12,
): List<HomeShelf> {
    if (maxPerShelf <= 0) return emptyList()
    val safe = entries.asSequence()
        .filter { row ->
            val route = AppleCatalogBrowseRoute.parse(row.release.appleScopedId)
            route != null && route.kind == ItunesCatalogItemType.ALBUM &&
                route.storefront == row.release.storefront &&
                route.id == row.release.appleCollectionId
        }
        .groupBy { it.release.appleScopedId }
        // Contradictory repeated snapshots must not make UI depend on the
        // accidental HTTP order of one source response.
        .values.mapNotNull { rows ->
            rows.firstOrNull()?.takeIf { first -> rows.all { it == first } }
        }
    val sections = listOf(
        ItunesEditorialReleaseFormat.ALBUM to headings.albums,
        ItunesEditorialReleaseFormat.EP to headings.eps,
        ItunesEditorialReleaseFormat.SINGLE to headings.singles,
        ItunesEditorialReleaseFormat.UNCLASSIFIED to headings.unclassified,
    )
    return sections.mapNotNull { (kind, label) ->
        val products = safe.filter { it.classification.format == kind }
            .take(maxPerShelf.coerceAtMost(24))
        if (products.isEmpty()) return@mapNotNull null
        HomeShelf(
            title = label,
            items = products.map { item ->
                val apple = item.release
                ShelfItem(
                    title = apple.title,
                    subtitle = apple.artistName,
                    thumbnailUrl = apple.artworkUrl,
                    videoId = null,
                    browseId = apple.appleScopedId,
                )
            },
        )
    }
}
