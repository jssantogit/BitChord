package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * The approved native release composition for an album opened inside an
 * artist. It owns no Room repositories, provider routes or legacy Song IDs.
 * Identity evidence, available MediaAssets and actions come from the caller.
 *
 * Root Library albums already reuse this exact NativeReleaseHeaderLayout and
 * nativeCanonicalAlbumTrackRows, and can consume this view model next.
 */
@Composable
internal fun NativeCanonicalNestedAlbumDetail(
    release: ReleaseEntity,
    releaseRecordingIds: List<String>,
    availableLibraryTracks: List<CanonicalBrowseTrack>,
    selectedSourceKey: String?,
    artworkTrack: CanonicalBrowseTrack?,
    evidence: CanonicalArtistReleaseEvidence?,
    editorialEvidence: DiscogsEditionEnrichment?,
    artworkLoader: OpenSubsonicArtworkLoader,
    albumSort: SongSort,
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onRateRelease: (() -> Unit)?,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
) {
    // Release membership is a verified RecordingId -> ReleaseId edge.
    // Available assets are always scoped to this user's Library sources.
    val albumIds = canonicalArtistReleaseTrackIds(
        availableLibraryTracks.map { it.recordingId }, releaseRecordingIds.toSet(),
    ).toSet()
    val albumRunningOrder = canonicalAlbumDetailTrackOrder(
        availableLibraryTracks.filter { it.recordingId in albumIds },
        releaseRecordingIds,
    )
    val albumTracks = canonicalAlbumDetailSort(albumRunningOrder, albumSort)
    val albumTrackNumbers = canonicalAlbumDetailTrackNumbers(albumRunningOrder)
    val visibleAlbumTracks = nativeCanonicalAlbumVisibleTracks(albumTracks, query)
    val albumRating by rememberCanonicalNestedAlbumRating(release.id)
    val albumQuality = canonicalAlbumSourceQuality(albumRunningOrder, selectedSourceKey)
    val albumFooter = canonicalAlbumFooterMetadata(
        evidence?.firstReleaseDate, albumRunningOrder,
    ).copy(editorialEvidence = editorialEvidence, showAlbumAbout = true)
    val releasePalette = rememberArtworkPalette(
        imageUrl = canonicalNativeReleasePaletteUri(
            artworkTrack?.artworkUri, release.artworkUri,
        ),
    )

    NativeCanonicalAlbumContent(
        title = canonicalAlbumDisplayTitle(release.title, evidence?.primaryType),
        credit = release.artistDisplay.orEmpty(),
        visibleTracks = visibleAlbumTracks,
        playbackTracks = albumTracks,
        artworkLoader = artworkLoader,
        palette = releasePalette,
        rating = albumRating,
        quality = albumQuality,
        onRate = onRateRelease,
        footer = albumFooter,
        trackNumbers = albumTrackNumbers,
        pinned = isPinned,
        onTogglePin = onTogglePin,
        query = query,
        onQueryChange = onQueryChange,
        searching = searching,
        loading = false,
        failed = false,
        headerKey = "nested-release-header",
        searchKey = "nested-release-search",
        footerKey = "artist-nested-album-footer",
        onTrackPlay = onTrackPlay,
        onTrackEditRating = onTrackEditRating,
        onTrackOptions = onTrackOptions,
        onPlayAll = onPlayAll,
        artwork = {
            if (artworkTrack != null) {
                CanonicalUnifiedArtwork(
                    artworkTrack, artworkLoader, Modifier.fillMaxSize(),
                )
            } else {
                CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
            }
        },
    )
}
