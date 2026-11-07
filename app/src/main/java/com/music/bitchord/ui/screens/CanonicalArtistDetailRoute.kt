package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.keyColors
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * This typed route feeds the shared native artist compositor with the
 * verified Library state, playback actions, pin and nested release controls.
 *
 * A Release belongs here only through a verified RecordingId->ReleaseId edge;
 * source/account scope is carried through MediaAsset availability. No Song,
 * legacy browseId or artist ID is inferred from a name.
 */
@Composable
internal fun CanonicalArtistDetailRoute(
    artistName: String,
    artistId: String?,
    tracks: List<CanonicalBrowseTrack>,
    availableLibraryTracks: List<CanonicalBrowseTrack>,
    sourceKeys: List<String>,
    selectedSourceKey: String?,
    heroArtworkTrack: CanonicalBrowseTrack?,
    artworkLoader: OpenSubsonicArtworkLoader,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    nestedBackToken: Int,
    onNestedDetailChanged: (Boolean) -> Unit,
    albumSort: SongSort = SongSort.DEFAULT,
    albumMenuToken: Int = 0,
    albumPlaylistToken: Int = 0,
    onNestedAlbumAddToPlaylist: (ReleaseEntity) -> Unit = {},
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onReleaseOptions: (ReleaseEntity) -> Unit,
    onRateArtist: (() -> Unit)? = null,
    onRateRelease: ((ReleaseEntity) -> Unit)? = null,
    selectedItems: List<CanonicalLibrarySelection>,
    onToggleSelection: (CanonicalLibrarySelection) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val pinRepository = remember(context) { CanonicalLibraryPinRepository.get(context) }
    val libraryPins by pinRepository.pins.collectAsStateWithLifecycle()
    // Reactive release evidence remains in the state layer; this is only an
    // entry adapter, while NativeCanonicalArtistContent owns all visual sections.
    val nativeReleases by rememberCanonicalArtistReleases(
        tracks, sourceKeys, selectedSourceKey,
    )
    // Library records frequently have no linked MusicBrainz release date;
    // corroborate exact artist+group data for the recent-release presentation
    // without mutating their local Recording/Release identity.
    val datedNativeReleases by rememberLocalArtistExternallyDatedReleases(
        artistName, nativeReleases,
    )
    // Preserve the exact Recording→Release sleeve when opening the shared
    // action sheet from a nested artist detail; the root album map may be
    // scoped to Artists and therefore cannot resolve the release cover.
    val releaseOptionsWithArtwork: (ReleaseEntity) -> Unit = { release ->
        val cover = nativeReleases.artwork[release.id]?.artworkUri
        onReleaseOptions(if (cover.isNullOrBlank()) release else release.copy(
            artworkUri = cover,
        ))
    }

    var appleArt by remember(artistName) {
        mutableStateOf(AppleArtistArtRepository.cached(artistName))
    }
    LaunchedEffect(artistName) {
        appleArt = AppleArtistArtRepository.artFor(artistName) ?: appleArt
    }
    val palette = rememberArtworkPalette(
        imageUrl = appleArt?.heroUrl,
        keyColors = appleArt?.keyColors(),
    )

    // The rating observer is shared with the native artist detail contract.
    // It keeps DISPLAY_ARTIST separate from a verified canonical ArtistId.
    val artistRatingState by rememberCanonicalArtistRating(artistId)
    // Keep scrolling position across nested album navigation.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val nestedNavigation = rememberCanonicalArtistNestedNavigation(
        artistId = artistId,
        artistName = artistName,
        nestedBackToken = nestedBackToken,
        albumMenuToken = albumMenuToken,
        albumPlaylistToken = albumPlaylistToken,
        onNestedDetailChanged = onNestedDetailChanged,
        onQueryChange = onQueryChange,
        onReleaseOptions = releaseOptionsWithArtwork,
        onNestedAlbumAddToPlaylist = onNestedAlbumAddToPlaylist,
    )
    val releaseRecordingIds by rememberCanonicalArtistNestedRecordings(
        nestedNavigation.openedRelease?.id, sourceKeys, selectedSourceKey,
    )

    val selectedAlbum = nestedNavigation.openedRelease
    if (selectedAlbum != null) {
        val albumSelection = CanonicalLibrarySelection(
            CanonicalLibraryEntityType.ALBUM, selectedAlbum.id,
        )
        NativeCanonicalNestedAlbumDetail(
            release = selectedAlbum,
            releaseRecordingIds = releaseRecordingIds,
            availableLibraryTracks = availableLibraryTracks,
            selectedSourceKey = selectedSourceKey,
            artworkTrack = nativeReleases.artwork[selectedAlbum.id],
            evidence = nativeReleases.evidence[selectedAlbum.id],
            artworkLoader = artworkLoader,
            albumSort = albumSort,
            query = query,
            searching = searching,
            onQueryChange = onQueryChange,
            isPinned = albumSelection in libraryPins.map { it.selection },
            onTogglePin = {
                pinRepository.toggle(CanonicalLibraryPin(
                    selection = albumSelection,
                    title = selectedAlbum.title,
                    subtitle = selectedAlbum.artistDisplay.orEmpty(),
                ))
            },
            onRateRelease = onRateRelease?.let { edit -> { edit(selectedAlbum) } },
            onPlayAll = onPlayAll,
            onTrackPlay = onTrackPlay,
            onTrackEditRating = onTrackEditRating,
            onTrackOptions = onTrackOptions,
        )
        return
    }

    NativeCanonicalArtistContent(
        artistName = artistName,
        tracks = tracks,
        heroArtworkTrack = heroArtworkTrack,
        nativeReleases = datedNativeReleases,
        artistRatingState = artistRatingState,
        appleArt = appleArt,
        palette = palette,
        listState = listState,
        artworkLoader = artworkLoader,
        query = query,
        onQueryChange = onQueryChange,
        searching = searching,
        selectedItems = selectedItems,
        onToggleSelection = onToggleSelection,
        onOpenRelease = nestedNavigation::open,
        onTrackPlay = onTrackPlay,
        onTrackEditRating = onTrackEditRating,
        onTrackOptions = onTrackOptions,
        onReleaseOptions = releaseOptionsWithArtwork,
        onRateArtist = onRateArtist,
        onPlayAll = onPlayAll,
    )
}
