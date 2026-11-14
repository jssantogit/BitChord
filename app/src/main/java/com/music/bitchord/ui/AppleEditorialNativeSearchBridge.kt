package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleEditorialArtistLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialTrackLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl
import com.music.bitchord.data.catalog.metadata.appleStoreLinkForExactProduct
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialArtistDetailPage
import com.music.bitchord.ui.screens.AppleEditorialSearchKind
import com.music.bitchord.ui.screens.AppleEditorialReleaseCategory
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleEditorialTrackRow
import com.music.bitchord.ui.screens.AppleEditorialTrackSearchRow

/**
 * Presentation boundary for the Android app. Do not turn an Apple product ID
 * into a MusicBrainz row merely to reuse an existing clickable search card.
 */
internal fun appleNativeSearchRows(
    source: AppleEditorialLoad.Success,
): List<AppleEditorialSearchRow> = source.releases.mapNotNull { it.asNativeSearchRow() }

/**
 * iTunes Search returns ranked records, while assemble() sorts discographies
 * chronologically. Search pagination must preserve source rank and previously
 * visible rows when appending pages.
 */
internal fun appleNativeAlbumSearchRowsInSourceOrder(
    appleRows: List<ItunesCatalogItem>,
    storefront: String,
): List<AppleEditorialSearchRow> {
    val ranking = appleRows.asSequence()
        .filter { it.type == ItunesCatalogItemType.ALBUM && it.storefront == storefront }
        .distinctBy { it.id }
        .mapIndexed { index, item -> item.id to index }
        .toMap()
    return ItunesEditorialCatalog.assemble(storefront, appleRows)
        .sortedBy { ranking[it.appleCollectionId] ?: Int.MAX_VALUE }
        .mapNotNull { it.asNativeSearchRow() }
}

private fun ItunesEditorialRelease.asNativeSearchRow(): AppleEditorialSearchRow? {
    val identity = AppleCatalogBrowseRoute.parse(appleScopedId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM &&
            it.storefront == storefront && it.id == appleCollectionId } ?: return null
    if (title.isBlank() || artistName.isBlank()) return null
    val exactLink = appleStoreLinkForExactProduct(
        storeUrl, ItunesCatalogItemType.ALBUM, appleCollectionId, storefront,
    )
    val badge = appleApprovedBadgeForStoreUrl(exactLink)
    return AppleEditorialSearchRow(
        browseId = appleScopedId,
        title = title,
        artistName = artistName,
        artworkUri = artworkUrl,
        releaseDate = releaseDate,
        category = when (format) {
            ItunesEditorialReleaseFormat.ALBUM -> AppleEditorialReleaseCategory.ALBUM
            ItunesEditorialReleaseFormat.EP -> AppleEditorialReleaseCategory.EP
            ItunesEditorialReleaseFormat.SINGLE -> AppleEditorialReleaseCategory.SINGLE
            ItunesEditorialReleaseFormat.UNCLASSIFIED ->
                AppleEditorialReleaseCategory.UNCLASSIFIED
        },
        appleStoreUrl = exactLink?.takeIf { badge != null },
        appleStoreBadgeUrl = badge,
    )
}

/**
 * The external store URL must come from the exact Apple artist search result
 * the user opened. A matching title, MB artist name or album route never grants
 * an artist-level crosswalk. Contradictory duplicate source rows fail closed.
 */
internal fun appleExactArtistStoreUrl(
    source: List<ItunesCatalogItem>,
    requested: AppleEditorialSearchRow,
): String? {
    if (requested.kind != AppleEditorialSearchKind.ARTIST) return null
    val route = AppleCatalogBrowseRoute.parse(requested.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
    return source.singleOrNull { item ->
        item.type == ItunesCatalogItemType.ARTIST &&
            item.id == route.id &&
            item.storefront == route.storefront &&
            item.scopedId == requested.browseId &&
            item.title == requested.title
    }?.storeUrl
}

/** An Apple album is browsable with or without a verified MusicBrainz MBID. */
internal fun appleNativeAlbumDetail(
    source: AppleEditorialLoad.Success,
    requestedBrowseId: String,
): AppleEditorialAlbumDetailPage? {
    val id = AppleCatalogBrowseRoute.parse(requestedBrowseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return null
    val album = source.releases.singleOrNull { release ->
        release.appleScopedId == requestedBrowseId &&
            release.storefront == id.storefront &&
            release.appleCollectionId == id.id
    } ?: return null
    val header = album.asNativeSearchRow() ?: return null
    val tracks = album.tracks
        .filter { it.type == ItunesCatalogItemType.TRACK &&
            it.storefront == id.storefront && it.collectionId == id.id }
        .sortedWith(compareBy({ it.discNumber ?: Int.MAX_VALUE },
            { it.trackNumber ?: Int.MAX_VALUE }, { it.id }))
        .map {
            AppleEditorialTrackRow(
                key = it.scopedId,
                title = it.title,
                artistName = it.artistName,
                discNumber = it.discNumber,
                trackNumber = it.trackNumber,
            )
        }
    val complete = source.state == AppleEditorialReadState.COMPLETE &&
        album.trackCount != null && album.trackCount == tracks.size &&
        tracks.map { it.discNumber to it.trackNumber }.distinct().size == tracks.size
    return AppleEditorialAlbumDetailPage(
        album = header,
        tracks = tracks,
        partialTracklist = !complete,
        appleStoreBadgeUrl = com.music.bitchord.data.catalog.metadata
            .appleApprovedBadgeForStoreUrl(album.storeUrl),
        appleStoreUrl = album.storeUrl.takeIf {
            com.music.bitchord.data.catalog.metadata
                .appleApprovedBadgeForStoreUrl(it) != null
        },
    )
}

/**
 * Only an independently verified edition may update the Album/EP/Single chip.
 * Apple still owns the album title, artwork, tracks, identity and storefront.
 * Conflicting or unclassified evidence leaves the initial Apple page intact.
 */
internal fun appleNativeReconciledAlbumDetail(
    source: AppleEditorialLoad.Success,
    browseId: String,
    evidence: com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry?,
): AppleEditorialAlbumDetailPage? {
    val original = appleNativeAlbumDetail(source, browseId) ?: return null
    val apple = source.releases.singleOrNull { it.appleScopedId == browseId }
        ?: return original
    val proof = evidence ?: return original
    val match = proof.match as?
        com.music.bitchord.data.catalog.metadata.AppleMusicBrainzEditionMatch.Verified
        ?: return original
    val classification = proof.classification
    if (classification.status !=
            com.music.bitchord.data.catalog.metadata.AppleEditorialFormatStatus.DECIDED ||
        classification.format == ItunesEditorialReleaseFormat.UNCLASSIFIED ||
        match.appleScopedId != browseId ||
        proof.release.appleScopedId != browseId ||
        proof.release.appleCollectionId != apple.appleCollectionId ||
        proof.release.storefront != apple.storefront ||
        proof.release.artistId != apple.artistId ||
        proof.release.title != apple.title ||
        proof.release.artistName != apple.artistName ||
        proof.release.artworkUrl != apple.artworkUrl ||
        proof.release.releaseDate != apple.releaseDate ||
        proof.release.upc != apple.upc ||
        proof.release.trackCount != apple.trackCount ||
        proof.release.tracks != apple.tracks
    ) return original
    val refined = appleNativeAlbumDetail(
        source.copy(releases = listOf(apple.copy(format = classification.format))),
        browseId,
    ) ?: return original
    val attributed = proof.discogsEdition?.takeIf {
        Regex("^https://www\\.discogs\\.com/release/[1-9][0-9]{0,11}$")
            .matches(it.attributionUrl)
    }
    return refined.copy(
        discogsAttributionText = attributed?.attributionText,
        discogsAttributionUrl = attributed?.attributionUrl,
        discogsNonAffiliationNotice = attributed?.let {
            com.music.bitchord.data.catalog.metadata.discogs
                .DiscogsEditionAccess.NON_AFFILIATION_NOTICE
        },
    )
}

/** Apple artist IDs remain distinct even for identical stage names. */
internal fun appleNativeArtistSearchRows(
    source: AppleEditorialArtistLoad.Success,
): List<AppleEditorialSearchRow> = source.artists.mapNotNull { artist ->
    val route = AppleCatalogBrowseRoute.parse(artist.scopedId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST &&
            it.storefront == artist.storefront && it.id == artist.id }
        ?: return@mapNotNull null
    AppleEditorialSearchRow(
        browseId = artist.scopedId,
        title = artist.title,
        artistName = artist.title,
        artworkUri = artist.artworkUrl,
        releaseDate = null,
        kind = AppleEditorialSearchKind.ARTIST,
        appleStoreUrl = appleStoreLinkForExactProduct(
            artist.storeUrl, ItunesCatalogItemType.ARTIST, artist.id, artist.storefront,
        ),
        appleStoreBadgeUrl = appleApprovedBadgeForStoreUrl(
            appleStoreLinkForExactProduct(
                artist.storeUrl, ItunesCatalogItemType.ARTIST, artist.id, artist.storefront,
            ),
        ),
    )
}

/** An artist's shelf is built from Apple artist-scoped products, not MB groups. */
internal fun appleNativeArtistDetail(
    source: AppleEditorialLoad.Success,
    artistBrowseId: String,
    artistName: String,
): AppleEditorialArtistDetailPage? {
    val route = AppleCatalogBrowseRoute.parse(artistBrowseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
    if (artistName.isBlank()) return null
    return AppleEditorialArtistDetailPage(
        artist = AppleEditorialSearchRow(
            browseId = artistBrowseId,
            title = artistName,
            artistName = artistName,
            artworkUri = null,
            releaseDate = null,
            kind = AppleEditorialSearchKind.ARTIST,
        ),
        albums = source.releases
            .filter { it.artistId == route.id && it.storefront == route.storefront }
            .mapNotNull { it.asNativeSearchRow() },
        possiblyTruncated = source.state == AppleEditorialReadState.POSSIBLY_TRUNCATED,
    )
}

/** Apple owns the visible song result, even when no MB Recording ID is known. */
internal fun appleNativeTrackSearchRows(
    source: AppleEditorialTrackLoad.Success,
): List<AppleEditorialTrackSearchRow> = source.tracks.mapNotNull { track ->
    val route = AppleCatalogBrowseRoute.parse(track.scopedId)
        ?.takeIf { it.kind == ItunesCatalogItemType.TRACK &&
            it.id == track.id && it.storefront == track.storefront }
        ?: return@mapNotNull null
    val artistName = track.artistName?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
    if (track.title.isBlank()) return@mapNotNull null
    val albumRoute = track.collectionId?.let { id ->
        AppleCatalogBrowseRoute.parse("itunes:${track.storefront}:album:$id")
    }?.takeIf { it.kind == ItunesCatalogItemType.ALBUM }
    AppleEditorialTrackSearchRow(
        key = track.scopedId,
        title = track.title,
        artistName = artistName,
        artworkUri = track.artworkUrl,
        albumBrowseId = albumRoute?.let { "itunes:${it.storefront}:album:${it.id}" },
        durationMs = track.durationMs,
        appleStoreUrl = appleStoreLinkForExactProduct(
            track.storeUrl, ItunesCatalogItemType.TRACK, track.id, track.storefront,
        ),
        appleStoreBadgeUrl = appleApprovedBadgeForStoreUrl(
            appleStoreLinkForExactProduct(
                track.storeUrl, ItunesCatalogItemType.TRACK, track.id, track.storefront,
            ),
        ),
    )
}
