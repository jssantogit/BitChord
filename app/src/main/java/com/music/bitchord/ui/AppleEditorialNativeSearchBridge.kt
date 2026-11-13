package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleEditorialTrackRow

/**
 * Presentation boundary for the Android app. Do not turn an Apple product ID
 * into a MusicBrainz row merely to reuse an existing clickable search card.
 */
internal fun appleNativeSearchRows(
    source: AppleEditorialLoad.Success,
): List<AppleEditorialSearchRow> = source.releases.mapNotNull { it.asNativeSearchRow() }

private fun ItunesEditorialRelease.asNativeSearchRow(): AppleEditorialSearchRow? {
    val identity = AppleCatalogBrowseRoute.parse(appleScopedId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM &&
            it.storefront == storefront && it.id == appleCollectionId } ?: return null
    if (title.isBlank() || artistName.isBlank()) return null
    return AppleEditorialSearchRow(
        browseId = appleScopedId,
        title = title,
        artistName = artistName,
        artworkUri = artworkUrl,
        releaseDate = releaseDate,
    )
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
    )
}
