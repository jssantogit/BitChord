package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.NativeDetailTrackNumber
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ResilientAppleCover
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.ui.theme.themeArtworkPalette

/**
 * Android presentation of exact Apple IDs through the EXISTING native release
 * header, native numbered track row, artist hero and artist release shelves.
 * No synthetic Song, BrowseItem, MBID, videoId or playable media asset.
 * Home, Explore and Search can navigate here without forking their detail UI.
 */
internal enum class AppleNativeDetailKind { NONE, ARTIST, ALBUM }

internal fun appleNativeDetailKind(
    album: UiState<AppleEditorialAlbumDetailPage>?,
    artist: UiState<AppleEditorialArtistDetailPage>?,
): AppleNativeDetailKind = when {
    album != null -> AppleNativeDetailKind.ALBUM
    artist != null -> AppleNativeDetailKind.ARTIST
    else -> AppleNativeDetailKind.NONE
}

internal fun appleNativeArtistSections(
    albums: List<AppleEditorialSearchRow>,
): List<Pair<AppleEditorialReleaseCategory, List<AppleEditorialSearchRow>>> =
    listOf(
        AppleEditorialReleaseCategory.ALBUM,
        AppleEditorialReleaseCategory.EP,
        AppleEditorialReleaseCategory.SINGLE,
        AppleEditorialReleaseCategory.UNCLASSIFIED,
    ).mapNotNull { category ->
        albums.filter { it.category == category }.takeIf { it.isNotEmpty() }
            ?.let { category to it }
    }

@Composable
internal fun AppleNativeAlbumDetail(
    state: UiState<AppleEditorialAlbumDetailPage>,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
) {
    when (state) {
        UiState.Loading -> AppleNativePending("Carregando álbum…")
        is UiState.Error -> AppleNativePending("Não foi possível carregar este álbum.")
        is UiState.Success -> {
            val page = state.data
            val cover = appleProminentArtworkOrNull(page.album.artworkUri)
            val palette = rememberArtworkPalette(cover)
            BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
                val sleeveSize = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
                LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "apple:native:album:header") {
                        NativeReleaseHeaderLayout(
                            title = page.album.title,
                            credit = page.album.artistName,
                            meta = page.album.releaseDate?.take(4).orEmpty(),
                            palette = palette,
                            sleeveSize = sleeveSize,
                            artwork = {
                                if (cover != null) {
                                    ResilientAppleCover(
                                        model = cover,
                                        contentDescription = "Capa do álbum",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize()
                                            .clip(RoundedCornerShape(12.dp)),
                                    )
                                }
                            },
                            actions = {}, // Apple catalog does not grant playback.
                        )
                    }
                    itemsIndexed(
                        page.tracks,
                        key = { _, track -> track.key },
                    ) { index, track ->
                        NativeDetailTrackRowLayout(
                            onClick = null,
                            leading = {
                                NativeDetailTrackNumber(
                                    number = track.trackNumber ?: index + 1,
                                    color = palette.onBackgroundVariant,
                                )
                            },
                            title = {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.onBackground,
                                    maxLines = 1,
                                )
                            },
                            subtitle = {
                                track.artistName?.let { credit ->
                                    Text(
                                        credit,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.onBackgroundVariant,
                                        maxLines = 1,
                                    )
                                }
                            },
                            rating = {},
                            showRatingSlot = false,
                        )
                        if (index < page.tracks.lastIndex) HorizontalDivider(
                            modifier = Modifier.padding(start = PAGE_GUTTER + 32.dp),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                    if (page.partialTracklist) item(key = "apple:native:album:partial") {
                        Text(
                            "A lista de faixas deste lançamento pode estar incompleta.",
                            color = palette.onBackgroundVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                        )
                    }
                    page.discogsAttributionText?.let { source ->
                        item(key = "apple:native:album:discogs") {
                            // Attribution text, never a store badge.
                            Text(
                                source,
                                color = palette.onBackgroundVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppleNativeArtistDetail(
    state: UiState<AppleEditorialArtistDetailPage>,
    contentPadding: PaddingValues,
    onAlbumOpen: (AppleEditorialSearchRow) -> Unit,
    onLoadMore: () -> Unit,
    onSearchMore: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    when (state) {
        UiState.Loading -> AppleNativePending("Carregando artista…")
        is UiState.Error -> AppleNativePending("Não foi possível carregar este artista.")
        is UiState.Success -> {
            val page = state.data
            val palette = rememberArtworkPalette(page.artist.artworkUri)
            var search by remember(page.artist.browseId) { mutableStateOf("") }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val heroHeight = (maxWidth * 0.95f).coerceAtMost(maxHeight * 0.57f)
                NativeArtistDetailFrame(
                    palette = palette,
                    artHeight = heroHeight,
                    listState = listState,
                    heroUrl = page.artist.artworkUri,
                    contentPadding = contentPadding,
                ) {
                    item(key = "apple:native:artist:header") {
                        ArtistHeader(
                            artistName = page.artist.title,
                            palette = palette,
                            artHeight = heroHeight,
                            appleArt = null,
                            signature = null,
                        )
                    }
                    appleNativeArtistSections(page.albums).forEach { (category, albums) ->
                        item(key = "apple:native:artist:section:${category.name}") {
                            val title = when (category) {
                                AppleEditorialReleaseCategory.ALBUM -> "Álbuns"
                                AppleEditorialReleaseCategory.EP -> "EPs"
                                AppleEditorialReleaseCategory.SINGLE -> "Singles"
                                AppleEditorialReleaseCategory.UNCLASSIFIED -> "Outros lançamentos"
                            }
                            NativeArtistReleaseShelf(
                                title = title,
                                releases = albums,
                                palette = palette,
                                itemKey = { it.browseId },
                            ) { album ->
                                NativeArtistReleaseCard(
                                    title = album.title,
                                    subtitle = album.releaseDate?.take(4).orEmpty(),
                                    palette = palette,
                                    onClick = { onAlbumOpen(album) },
                                    modifier = Modifier.width(SHELF_CARD_WIDTH),
                                    artwork = {
                                        appleProminentArtworkOrNull(album.artworkUri)?.let { cover ->
                                            ResilientAppleCover(
                                                model = cover,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (page.albums.isEmpty()) item(key = "apple:native:artist:empty") {
                        Text(
                            "Nenhum lançamento encontrado.",
                            color = palette.onBackgroundVariant,
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                        )
                    }
                    if (page.hasMore) item(key = "apple:native:artist:more") {
                        Box(Modifier.fillMaxWidth().padding(PAGE_GUTTER)) {
                            ActionPill(
                                icon = BitChordIcons.Plus,
                                label = "Carregar mais",
                                onClick = onLoadMore,
                                filled = false,
                                enabled = !page.loadingMore,
                            )
                        }
                    }
                    if (page.possiblyTruncated) item(key = "apple:native:artist:coverage") {
                        Text(
                            "A API pública pode não incluir toda a discografia.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onBackgroundVariant,
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                        )
                    }
                    item(key = "apple:native:artist:find") {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            SectionHeading("Buscar lançamentos", palette)
                            NativeDetailSearchField(
                                query = search,
                                onQueryChange = { search = it },
                                onClose = { search = "" },
                                autoFocus = false,
                                onFocused = {},
                                palette = palette,
                                placeholder = "Título do lançamento",
                            )
                            Box(Modifier.padding(horizontal = PAGE_GUTTER)) {
                                ActionPill(
                                    icon = BitChordIcons.Search,
                                    label = "Buscar",
                                    onClick = { onSearchMore(search.trim()) },
                                    filled = false,
                                    enabled = search.trim().length >= 2 && !page.loadingMore,
                                )
                            }
                            if (page.loadingMore) CircularProgressIndicator(
                                modifier = Modifier.padding(PAGE_GUTTER),
                                color = palette.onBackground,
                            )
                            if (page.pageError || page.noAdditionalResults) Text(
                                if (page.pageError) "Não foi possível consultar mais lançamentos."
                                else "Nenhum lançamento adicional encontrado.",
                                color = palette.onBackgroundVariant,
                                modifier = Modifier.padding(PAGE_GUTTER),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleNativePending(message: String) {
    val palette = themeArtworkPalette()
    Box(
        Modifier.fillMaxSize().background(palette.background)
            .padding(PAGE_GUTTER),
    ) {
        Text(message, color = palette.onBackgroundVariant)
    }
}
