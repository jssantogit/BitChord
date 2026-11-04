package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
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
    )
    val releasePalette = rememberArtworkPalette(
        imageUrl = canonicalNativeReleasePaletteUri(
            artworkTrack?.artworkUri, release.artworkUri,
        ),
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(releasePalette.background)) {
        val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
        LazyColumn(contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "nested-release-header") {
                NativeReleaseHeaderLayout(
                    title = canonicalAlbumDisplayTitle(
                        release.title, evidence?.primaryType,
                    ),
                    credit = release.artistDisplay.orEmpty(),
                    meta = "",
                    rating = albumRating,
                    quality = albumQuality,
                    onRate = onRateRelease,
                    palette = releasePalette,
                    sleeveSize = sleeve,
                    artwork = {
                        if (artworkTrack != null) {
                            CanonicalUnifiedArtwork(
                                artworkTrack, artworkLoader, Modifier.fillMaxSize(),
                            )
                        } else {
                            CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                        }
                    },
                    actions = {
                        if (albumTracks.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            NativeReleaseActionRow(
                                onPlay = { onPlayAll(albumTracks, false) },
                                onShuffle = { onPlayAll(albumTracks, true) },
                                isLocal = false,
                                reservePinAction = true,
                                isPinnedInLibrary = isPinned,
                                onTogglePin = onTogglePin,
                            )
                        }
                    },
                )
            }
            if (shouldShowNativeArtistSearch(searching, query)) {
                item(key = "nested-release-search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onClose = { onQueryChange("") },
                        autoFocus = searching,
                        onFocused = {},
                        palette = releasePalette,
                    )
                }
            }
            if (visibleAlbumTracks.isEmpty() && albumTracks.isNotEmpty()) {
                item(key = "nested-release-no-results") {
                    Text(
                        "Nenhuma música corresponde à pesquisa.",
                        modifier = Modifier.padding(PAGE_GUTTER),
                    )
                }
            }
            nativeCanonicalAlbumTrackRows(
                tracks = visibleAlbumTracks,
                artworkLoader = artworkLoader,
                palette = releasePalette,
                trackNumbers = albumTrackNumbers,
                onTrackPlay = onTrackPlay,
                onTrackEditRating = onTrackEditRating,
                onTrackOptions = onTrackOptions,
            )
            item(key = "artist-nested-album-footer") {
                NativeAlbumMetadataFooter(
                    metadata = albumFooter, palette = releasePalette,
                )
            }
        }
    }
}
