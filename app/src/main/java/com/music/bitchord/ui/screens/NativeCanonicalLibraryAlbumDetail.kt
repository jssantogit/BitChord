package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.SourceQuality
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * The original native release composition, now separate from root Library
 * grid/list browse. A release stays a typed ReleaseId with revalidated assets:
 * no artificial Song, browseId or playback for metadata-only recordings.
 */
@Composable
internal fun NativeCanonicalLibraryAlbumDetail(
    releaseIdentity: String,
    title: String,
    tracks: List<CanonicalBrowseTrack>,
    artworkLoader: OpenSubsonicArtworkLoader,
    heroArtworkUri: String?,
    heroArtworkTrack: CanonicalBrowseTrack?,
    albumCredit: String,
    albumRating: Double?,
    albumQuality: SourceQuality?,
    onRateAlbum: (() -> Unit)?,
    albumFooter: CanonicalAlbumFooterMetadata?,
    albumTrackNumbers: Map<String, Int>,
    albumPinned: Boolean,
    onToggleAlbumPin: (() -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    loading: Boolean,
    failed: Boolean,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
) {
    val palette = rememberArtworkPalette(
        imageUrl = canonicalNativeReleasePaletteUri(
            heroArtworkUri, heroArtworkTrack?.artworkUri,
        ),
    )
    NativeCanonicalAlbumContent(
        releaseIdentity = releaseIdentity,
        title = title,
        credit = albumCredit,
        visibleTracks = tracks,
        playbackTracks = tracks,
        artworkLoader = artworkLoader,
        palette = palette,
        rating = albumRating,
        quality = albumQuality,
        onRate = onRateAlbum,
        footer = albumFooter,
        trackNumbers = albumTrackNumbers,
        pinned = albumPinned,
        onTogglePin = onToggleAlbumPin,
        query = query,
        onQueryChange = onQueryChange,
        searching = searching,
        loading = loading,
        failed = failed,
        headerKey = "native-library-header",
        searchKey = "native-library-search",
        footerKey = "native-album-footer",
        onTrackPlay = onTrackPlay,
        onTrackEditRating = onTrackEditRating,
        onTrackOptions = onTrackOptions,
        onPlayAll = onPlayAll,
        artwork = {
            NativeCanonicalAlbumArtwork(
                releaseArtworkUri = heroArtworkUri,
                artworkTrack = heroArtworkTrack,
                artworkLoader = artworkLoader,
            )
        },
    )
}
