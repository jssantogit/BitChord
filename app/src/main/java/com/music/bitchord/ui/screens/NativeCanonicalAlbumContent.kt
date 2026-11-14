package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.SourceQuality
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * The list is keyed by the verified ReleaseId, never by a title or mutable
 * track list. This retains scroll on palette/artwork/Room refresh and resets
 * it when a different album is opened in the same detail host.
 */
@Composable
internal fun rememberNativeAlbumDetailListState(releaseIdentity: String): LazyListState =
    rememberSaveable(releaseIdentity, saver = LazyListState.Saver) { LazyListState() }

/** A single native release body for Library albums and albums nested in artists.
 * The caller owns verified media membership, source-specific cover, rating,
 * running order and playback capabilities. Only one visual tree is rendered.
 */
@Composable
internal fun NativeCanonicalAlbumContent(
    releaseIdentity: String,
    title: String,
    credit: String,
    visibleTracks: List<CanonicalBrowseTrack>,
    playbackTracks: List<CanonicalBrowseTrack>,
    artworkLoader: OpenSubsonicArtworkLoader,
    palette: ArtworkPalette,
    rating: Double?,
    quality: SourceQuality?,
    onRate: (() -> Unit)?,
    footer: CanonicalAlbumFooterMetadata?,
    trackNumbers: Map<String, Int>,
    pinned: Boolean,
    onTogglePin: (() -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    loading: Boolean,
    failed: Boolean,
    headerKey: String,
    searchKey: String,
    footerKey: String,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
    artwork: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
        val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
        // An album refresh keeps its place; opening a different ReleaseId
        // starts at its own header instead of inheriting the previous scroll.
        val releaseListState = rememberNativeAlbumDetailListState(releaseIdentity)
        LazyColumn(
            state = releaseListState,
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item(key = headerKey) {
                NativeReleaseHeaderLayout(
                    title = title,
                    credit = credit,
                    meta = "",
                    rating = rating,
                    quality = quality,
                    onRate = onRate,
                    palette = palette,
                    sleeveSize = sleeve,
                    artwork = artwork,
                    actions = {
                        // Loading or metadata-only releases retain an authentic
                        // pin action, without manufacturing playable RecordingIds.
                        NativeAlbumHeroActions(
                            canPlay = playbackTracks.isNotEmpty(),
                            isPinned = pinned,
                            onTogglePin = onTogglePin,
                            onPlay = { onPlayAll(playbackTracks, false) },
                            onShuffle = { onPlayAll(playbackTracks, true) },
                        )
                    },
                )
            }
            if (searching || query.isNotBlank()) {
                item(key = searchKey) {
                    NativeDetailSearchField(
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
                failed -> item {
                    RemoteBrowseMessage("Não foi possível aplicar o filtro.")
                }
                visibleTracks.isEmpty() -> item {
                    RemoteBrowseMessage(
                        nativeAlbumEmptyMessage(query, playbackTracks.isNotEmpty()),
                    )
                }
                else -> nativeCanonicalAlbumTrackRows(
                    tracks = visibleTracks,
                    artworkLoader = artworkLoader,
                    palette = palette,
                    trackNumbers = trackNumbers,
                    onTrackPlay = onTrackPlay,
                    onTrackEditRating = onTrackEditRating,
                    onTrackOptions = onTrackOptions,
                )
            }
            if (footer != null) {
                item(key = footerKey) {
                    NativeAlbumMetadataFooter(
                        metadata = footer,
                        palette = palette,
                    )
                }
            }
        }
    }
}

internal fun nativeAlbumEmptyMessage(query: String, hasPlayableTracks: Boolean): String =
    if (query.isNotBlank() && hasPlayableTracks) {
        "Nenhuma música corresponde à pesquisa."
    } else {
        "Nenhuma música nesta seleção."
    }
