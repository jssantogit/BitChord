package com.music.bitchord

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.canvas.VisualArtworkCandidate
import com.music.bitchord.data.canvas.VisualArtworkPrefetch
import com.music.bitchord.data.canvas.VisualArtworkPriority
import com.music.bitchord.data.canvas.planVisualShelfArtwork
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Native screen artwork speculation, independent of MusicBrainz / Apple /
 * Navidrome. Kept outside BitChordApp because the root Compose renderer is
 * near the JVM's 64-KiB per-method bytecode limit.
 *
 * Always cancel stale speculative work on viewport/query changes; the visible
 * AsyncImage request is never gated by these optional decodes.
 */
@Composable
internal fun CatalogArtworkWarmupEffects(
    showHome: Boolean,
    homeShelves: List<HomeShelf>,
    homeListState: LazyListState,
    showSearch: Boolean,
    searchResults: StateFlow<UiState<List<CanonicalCatalogSearchRow>>?>,
    showExplore: Boolean,
    showGenreDetail: Boolean,
    genreState: UiState<List<HomeShelf>>,
    genreCovers: Map<String, String>,
) {
    val context = LocalContext.current.applicationContext
    val homeUrls = remember(homeShelves) {
        homeShelves.map { shelf -> shelf.items.map { item -> item.thumbnailUrl } }
    }
    LaunchedEffect(showHome, homeUrls, homeListState) {
        if (showHome) snapshotFlow {
            homeListState.firstVisibleItemIndex
        }.collectLatest { firstVisible ->
            // A fast scroll must not wait for decodes from the old viewport.
            VisualArtworkPrefetch.warm(
                context, planVisualShelfArtwork(homeUrls, firstVisible, CARD_ART_PX),
            )
        }
    }

    LaunchedEffect(showSearch, searchResults) {
        if (showSearch) searchResults.collectLatest { result ->
            val rows = (result as? UiState.Success)?.data.orEmpty()
            val targets = rows.asSequence()
                .mapNotNull { it.artworkUri?.takeIf(String::isNotBlank) }
                .distinct().take(10)
                .map { VisualArtworkCandidate(it, VisualArtworkPriority.VISIBLE, ROW_ART_PX) }
                .toList()
            VisualArtworkPrefetch.warm(context, targets)
        }
    }

    val exploreArtwork = remember(showExplore, showGenreDetail, genreState, genreCovers) {
        if (!showExplore) emptyList() else {
            val candidates = if (!showGenreDetail) {
                genreCovers.values.asSequence()
            } else {
                (genreState as? UiState.Success)?.data.orEmpty()
                    .asSequence().take(3).flatMap { shelf ->
                        shelf.items.asSequence().take(5).map { it.thumbnailUrl }
                    }
            }
            candidates.filterNotNull().filter(String::isNotBlank)
                .distinct().take(12).map {
                    VisualArtworkCandidate(it, VisualArtworkPriority.VISIBLE, CARD_ART_PX)
                }.toList()
        }
    }
    LaunchedEffect(showExplore, exploreArtwork) {
        if (showExplore) VisualArtworkPrefetch.warm(context, exploreArtwork)
    }
}
