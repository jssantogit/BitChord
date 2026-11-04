package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.SourceQuality
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * The original native release composition, now separate from root Library
 * grid/list browse. A release stays a typed ReleaseId with revalidated assets:
 * no artificial Song, browseId or playback for metadata-only recordings.
 */
@Composable
internal fun NativeCanonicalLibraryAlbumDetail(
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
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
        val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
        LazyColumn(contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "native-library-header") {
                NativeReleaseHeaderLayout(
                    title = title,
                    credit = albumCredit,
                    meta = "",
                    rating = albumRating,
                    quality = albumQuality,
                    onRate = onRateAlbum,
                    palette = palette,
                    sleeveSize = sleeve,
                    artwork = {
                        when {
                            !heroArtworkUri.isNullOrBlank() &&
                                (heroArtworkUri.startsWith("content://") ||
                                    heroArtworkUri.startsWith("file://")) -> AsyncImage(
                                model = heroArtworkUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            heroArtworkTrack != null -> CanonicalUnifiedArtwork(
                                heroArtworkTrack, artworkLoader, Modifier.fillMaxSize(),
                            )
                            else -> CoverPlaceholder(Icons.Rounded.Folder, Modifier.fillMaxSize())
                        }
                    },
                    actions = {
                        if (tracks.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            NativeReleaseActionRow(
                                onPlay = { onPlayAll(tracks, false) },
                                onShuffle = { onPlayAll(tracks, true) },
                                isLocal = false,
                                canShuffle = tracks.isNotEmpty(),
                                reservePinAction = true,
                                isPinnedInLibrary = albumPinned,
                                onTogglePin = onToggleAlbumPin,
                            )
                        }
                    },
                )
            }
            if (searching || query.isNotBlank()) {
                item(key = "native-library-search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onClose = { onQueryChange("") },
                        autoFocus = searching,
                        onFocused = {},
                        palette = palette,
                    )
                }
            }
            when {
                loading -> item { NativeLoadingSpinner() }
                failed -> item { RemoteBrowseMessage("Não foi possível aplicar o filtro.") }
                tracks.isEmpty() -> item {
                    RemoteBrowseMessage("Nenhuma música nesta seleção.")
                }
                else -> nativeCanonicalAlbumTrackRows(
                    tracks = tracks,
                    artworkLoader = artworkLoader,
                    palette = palette,
                    trackNumbers = albumTrackNumbers,
                    onTrackPlay = onTrackPlay,
                    onTrackEditRating = onTrackEditRating,
                    onTrackOptions = onTrackOptions,
                )
            }
            if (albumFooter != null) {
                item(key = "native-album-footer") {
                    NativeAlbumMetadataFooter(
                        metadata = albumFooter,
                        palette = palette,
                    )
                }
            }
        }
    }
}
