package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl
import com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy
import java.time.LocalDate
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.AppleEditorialSearchRow

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

/**
 * Apple-first editorial cards carry their own exact product attribution, not
 * a guessed URL constructed from a title or MusicBrainz relationship.
 */
private fun ItunesEditorialRelease.asAttributableShelfItem(): ShelfItem {
    val exactLink = com.music.bitchord.data.catalog.metadata
        .appleStoreLinkForExactProduct(
            storeUrl, ItunesCatalogItemType.ALBUM, appleCollectionId, storefront,
        )
    val badge = appleApprovedBadgeForStoreUrl(exactLink)
    return ShelfItem(
        title = title, subtitle = artistName,
        thumbnailUrl = artworkUrl, videoId = null, browseId = appleScopedId,
        appleStoreUrl = exactLink?.takeIf { badge != null },
        appleStoreBadgeUrl = badge,
    )
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
                apple.asAttributableShelfItem()
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
        // Source-order is append-stable while public Apple probes complete.
        // Do not re-sort whole shelves every time a newly found album arrives.
    fun cards(releases: List<ItunesEditorialRelease>): List<ShelfItem> =
        releases.map { album -> album.asAttributableShelfItem() }

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
        // Artist/title probes append; preserving order avoids scroll jumps.
        .take(24)
        .map { album -> album.asAttributableShelfItem() }
    return buildList {
        if (releases.isNotEmpty()) add(HomeShelf(releaseHeading, releases))
        publicPlaylists?.takeIf { it.items.isNotEmpty() }?.let(::add)
    }
}


/** Never decorate Explore categories from an unrelated MB release sleeve. */
internal fun appleExploreGenreCoverMap(
    appleAlbums: List<ItunesEditorialRelease>,
    tags: List<String>,
): Map<String, String> = tags.distinct().mapNotNull { tag ->
    val cover = appleAlbums.firstOrNull { release ->
        AppleExploreGenrePolicy.matches(tag, release.genre) &&
            release.appleCollectionId > 0L &&
            release.appleScopedId ==
                "itunes:${release.storefront}:album:${release.appleCollectionId}" &&
            AppleCatalogBrowseRoute.parse(release.appleScopedId)?.kind ==
                ItunesCatalogItemType.ALBUM &&
            !release.artworkUrl.isNullOrBlank()
    }?.artworkUrl
    if (cover == null) null else tag to cover
}.toMap()

/**
 * A failed/empty MusicBrainz seed fetch does not imply an Apple catalog
 * request is pending. Avoid an endless spinner when there is no seed to
 * search, while retaining the legitimate loading state during source fetch.
 */
internal fun appleFirstHomeEditorialIsPending(
    appleStillLoading: Boolean,
    musicBrainzStillLoading: Boolean,
    hasCandidateSeeds: Boolean,
): Boolean = musicBrainzStillLoading || (appleStillLoading && hasCandidateSeeds)



/**
 * The image probe and the Apple release ID must both agree before refreshing
 * Home/Explore. This is presentation-only: never write CDN variants to Room.
 */
internal fun appleEditorialReleasesWithProbedArtwork(
    releases: List<ItunesEditorialRelease>,
    requestedAlbumId: String,
    original: String?,
    probed: String?,
): List<ItunesEditorialRelease> {
    val route = AppleCatalogBrowseRoute.parse(requestedAlbumId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return releases
    if (original.isNullOrBlank() || probed == original ||
        safeAppleArtwork(original) == null ||
        safeAppleArtwork(probed) == null
    ) return releases
    if (releases.count {
        it.appleScopedId == requestedAlbumId &&
            it.storefront == route.storefront && it.appleCollectionId == route.id
    } != 1) return releases
    return releases.map { release ->
        if (release.appleScopedId == requestedAlbumId &&
            release.storefront == route.storefront &&
            release.appleCollectionId == route.id &&
            release.artworkUrl == original
        ) release.copy(artworkUrl = probed) else release
    }
}


/**
 * Keep a single ordered queue over successive Apple artist pages. A page
 * contains 24 verified products: processing only the first 12 leaves the rest
 * at 100px forever when the native shelf refuses to upscale thumbnails.
 *
 * The exact storefront route is the dedupe key, never artist/title.
 */
internal fun appleArtistArtworkTasksForNewPages(
    candidates: List<AppleEditorialSearchRow>,
    alreadyQueued: MutableSet<String>,
    featuredCount: Int = 0,
): List<Pair<Int, AppleEditorialSearchRow>> = candidates.mapIndexedNotNull { index, album ->
    if (AppleCatalogBrowseRoute.parse(album.browseId)?.kind !=
            ItunesCatalogItemType.ALBUM ||
        !alreadyQueued.add(album.browseId)
    ) null else appleArtworkTargetPxForShelf(index, featuredCount) to album
}

/** Artist discographies use typed Apple IDs, never a title/artist cover join. */
internal fun appleArtistAlbumRowsWithProbedArtwork(
    rows: List<AppleEditorialSearchRow>,
    albumBrowseId: String,
    original: String?,
    probed: String?,
): List<AppleEditorialSearchRow> {
    val route = AppleCatalogBrowseRoute.parse(albumBrowseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return rows
    if (original.isNullOrBlank() || original == probed ||
        safeAppleArtwork(original) == null || safeAppleArtwork(probed) == null
    ) return rows
    if (rows.count { it.browseId == albumBrowseId } != 1) return rows
    return rows.map { row ->
        if (row.browseId == albumBrowseId &&
            AppleCatalogBrowseRoute.parse(row.browseId) == route &&
            row.artworkUri == original
        ) row.copy(artworkUri = probed) else row
    }
}


/**
 * UI artwork budget. Large featured cards and album detail heroes can use a
 * native 1024px Apple rendition; ordinary grids stay at 600px. Never schedule
 * more than four large thumbnails from a single page of shelf candidates.
 */
internal fun appleArtworkTargetPxForShelf(index: Int, featuredCount: Int): Int =
    if (index >= 0 && index < featuredCount.coerceIn(0, 4)) 1024 else 600


/**
 * The Apple artist pager reconstructs its rows from the raw 100px Search API
 * snapshots. Retain a previously verified 600/720/1024 variant ONLY when
 * both URLs share the identical CDN asset identity. Do not revert 1024px
 * covers during pagination, or borrow a sleeve by artist/title.
 */
internal fun appleKeepVerifiedArtworkAcrossPaging(
    currentFromApple: String?,
    previouslyDisplayed: String?,
): String? {
    if (previouslyDisplayed.isNullOrBlank() ||
        currentFromApple.isNullOrBlank() ||
        currentFromApple == previouslyDisplayed
    ) return currentFromApple
    return if (listOf(600, 720, 1024).any { target ->
        com.music.bitchord.data.catalog.metadata
            .appleHighResolutionArtworkCandidate(currentFromApple, target) ==
                previouslyDisplayed
    }) previouslyDisplayed else currentFromApple
}


/**
 * Network refreshes carry Apple's original 100px links. Preserve a previously
 * probed high-resolution URL for the same Apple store product AND the same
 * underlying CDN image asset. A newly changed sleeve is always allowed to
 * replace the old high-resolution image.
 */
internal fun appleReleasesPreservingVerifiedArtwork(
    fresh: List<ItunesEditorialRelease>,
    previous: List<ItunesEditorialRelease>,
): List<ItunesEditorialRelease> {
    if (fresh.isEmpty() || previous.isEmpty()) return fresh
    val indexed = previous.groupBy { it.appleScopedId }
    return fresh.map { album ->
        val old = indexed[album.appleScopedId]?.singleOrNull()?.takeIf {
            it.storefront == album.storefront &&
                it.appleCollectionId == album.appleCollectionId
        }
        val artwork = appleKeepVerifiedArtworkAcrossPaging(
            album.artworkUrl, old?.artworkUrl,
        )
        if (artwork != album.artworkUrl) album.copy(artworkUrl = artwork)
        else album
    }
}


/** An Apple Search page is appendable; refreshed pages must not downgrade
 * already verified artwork for the exact same storefront product. */
internal fun appleSearchRowsPreservingVerifiedArtwork(
    fresh: List<AppleEditorialSearchRow>,
    previous: List<AppleEditorialSearchRow>,
): List<AppleEditorialSearchRow> {
    if (fresh.isEmpty() || previous.isEmpty()) return fresh
    val oldById = previous.groupBy { it.browseId }
    return fresh.map { item ->
        val route = AppleCatalogBrowseRoute.parse(item.browseId)
        val old = oldById[item.browseId]?.singleOrNull()
        val artwork = if (route?.kind == ItunesCatalogItemType.ALBUM &&
            old?.browseId == item.browseId
        ) appleKeepVerifiedArtworkAcrossPaging(item.artworkUri, old.artworkUri)
        else item.artworkUri
        if (artwork != item.artworkUri) item.copy(artworkUri = artwork) else item
    }
}
