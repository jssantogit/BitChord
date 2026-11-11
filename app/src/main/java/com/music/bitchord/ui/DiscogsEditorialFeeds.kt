package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsGroupEditorialEvidence
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsSourceOnlyDiscovery
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * Common Home/Explore metadata-only classification projection. It never
 * constructs audio assets, synthetic MBIDs, or a parallel browse surface.
 */
internal data class DiscogsEditorialFeeds(
    val cached: List<CanonicalCatalogSearchRow>,
    val worldwide: List<CanonicalCatalogSearchRow>,
    val genre: UiState<List<CanonicalCatalogSearchRow>>,
    val sourceOnlyShelves: List<HomeShelf>,
)

@Composable
internal fun rememberDiscogsEditorialFeeds(
    cached: List<CanonicalCatalogSearchRow>,
    worldwide: List<CanonicalCatalogSearchRow>,
    genre: UiState<List<CanonicalCatalogSearchRow>>,
): DiscogsEditorialFeeds {
    val proofs by DiscogsGroupEditorialEvidence.store.proofs.collectAsStateWithLifecycle()
    val sourceSnapshots by DiscogsSourceOnlyDiscovery.store.snapshots.collectAsStateWithLifecycle()
    LaunchedEffect(sourceSnapshots) {
        val now = System.currentTimeMillis()
        DiscogsSourceOnlyDiscovery.store.nextExpiryMillis(now)?.let { expiry ->
            kotlinx.coroutines.delay((expiry - now).coerceAtLeast(1L))
            DiscogsSourceOnlyDiscovery.store.prune(System.currentTimeMillis())
        }
    }
    LaunchedEffect(proofs) {
        val now = System.currentTimeMillis()
        DiscogsGroupEditorialEvidence.store.nextExpiryMillis(now)?.let { expiry ->
            kotlinx.coroutines.delay((expiry - now).coerceAtLeast(1L))
            DiscogsGroupEditorialEvidence.store.prune(System.currentTimeMillis())
        }
    }
    return remember(cached, worldwide, genre, proofs, sourceSnapshots) {
        DiscogsEditorialFeeds(
            cached = discogsEditorialProjectedRows(cached, proofs),
            worldwide = discogsEditorialProjectedRows(worldwide, proofs),
            genre = when (genre) {
                is UiState.Success -> UiState.Success(
                    discogsEditorialProjectedRows(genre.data, proofs),
                )
                else -> genre
            },
            sourceOnlyShelves = discogsSourceOnlyCatalogShelves(
                DiscogsSourceOnlyDiscovery.store.active(System.currentTimeMillis()),
            ),
        )
    }
}
