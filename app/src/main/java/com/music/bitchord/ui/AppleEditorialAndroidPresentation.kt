package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy
import java.time.LocalDate
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


/**
 * Public Home release cards come exclusively from Apple storefront products.
 * MusicBrainz recent groups may *suggest search terms* upstream, but their
 * unverified metadata rows cannot be fallback cards in the Apple-first feed.
 * Local playback picks remain independently verified and are not Apple titles.
 */
internal fun appleFirstHomeShelves(
    appleAlbums: List<ItunesEditorialRelease>,
    existingLocalShelves: List<HomeShelf>,
    releasesHeading: String,
    discoveryHeading: String,
    todayIsoDate: String,
): List<HomeShelf> {
    val today = runCatching { LocalDate.parse(todayIsoDate) }.getOrNull()
        ?: return emptyList()
    val clean = appleAlbums.asSequence()
        .filter { album ->
            album.appleCollectionId > 0L && album.artistId?.let { it > 0L } == true &&
                album.appleScopedId ==
                    "itunes:${album.storefront}:album:${album.appleCollectionId}" &&
                AppleCatalogBrowseRoute.parse(album.appleScopedId)?.kind ==
                    ItunesCatalogItemType.ALBUM &&
                album.title.isNotBlank() && album.artistName.isNotBlank()
        }
        .groupBy { it.appleScopedId }
        .values.mapNotNull { rows ->
            rows.firstOrNull()?.takeIf { first -> rows.all { it == first } }
        }
        .filter { album ->
            val releaseDate = album.releaseDate?.takeIf(String::isNotBlank)
                ?: return@filter true
            val date = runCatching { LocalDate.parse(releaseDate.take(10)) }.getOrNull()
            date != null && date <= today
        }
        .sortedWith(compareByDescending<ItunesEditorialRelease> {
            it.releaseDate.orEmpty()
        }.thenBy { it.appleScopedId })
    fun cards(releases: List<ItunesEditorialRelease>): List<ShelfItem> =
        releases.map { album ->
            ShelfItem(
                title = album.title,
                subtitle = album.artistName,
                thumbnailUrl = album.artworkUrl,
                videoId = null,
                browseId = album.appleScopedId,
            )
        }

    val recentCutoff = today.minusDays(90)
    val recent = clean.filter { album ->
        album.releaseDate?.let { raw ->
            runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
                ?.let { it >= recentCutoff } == true
        } == true
    }
    val assigned = recent.take(12).map { it.appleScopedId }.toSet()
    val remaining = clean.filterNot { it.appleScopedId in assigned }.take(18)

    val local = existingLocalShelves.mapNotNull { shelf ->
        val items = shelf.items.filter { item ->
            val id = item.browseId.orEmpty()
            id.startsWith("local:") || id.startsWith("server:") ||
                id.startsWith("downloads:")
        }
        if (items.isEmpty()) null else shelf.copy(items = items)
    }
    return buildList {
        if (recent.isNotEmpty()) add(HomeShelf(
            releasesHeading, cards(recent.take(12)),
        ))
        if (remaining.isNotEmpty()) add(HomeShelf(
            discoveryHeading, cards(remaining),
        ))
        addAll(local)
    }
}


/**
 * Explore's static category grid remains navigation. Opening a category
 * displays only Apple catalog albums whose independent primaryGenreName
 * agrees with that category; MB tag hits are never copied into these rows.
 */
internal fun appleFirstGenreShelves(
    tag: String,
    appleAlbums: List<ItunesEditorialRelease>,
    publicPlaylists: HomeShelf?,
    releaseHeading: String,
): List<HomeShelf> {
    val releases = appleAlbums.asSequence()
        .filter { album ->
            AppleExploreGenrePolicy.matches(tag, album.genre) &&
                album.appleCollectionId > 0L &&
                album.artistId?.let { it > 0L } == true &&
                album.appleScopedId ==
                    "itunes:${album.storefront}:album:${album.appleCollectionId}" &&
                AppleCatalogBrowseRoute.parse(album.appleScopedId)?.kind ==
                    ItunesCatalogItemType.ALBUM
        }
        .groupBy { it.appleScopedId }
        .values.mapNotNull { rows ->
            rows.firstOrNull()?.takeIf { first -> rows.all { it == first } }
        }
        .sortedWith(compareByDescending<ItunesEditorialRelease> {
            it.releaseDate.orEmpty()
        }.thenBy { it.appleScopedId })
        .take(24)
        .map { album ->
            ShelfItem(
                album.title, album.artistName, album.artworkUrl,
                videoId = null, browseId = album.appleScopedId,
            )
        }
    return buildList {
        if (releases.isNotEmpty()) add(HomeShelf(releaseHeading, releases))
        publicPlaylists?.takeIf { it.items.isNotEmpty() }?.let(::add)
    }
}
