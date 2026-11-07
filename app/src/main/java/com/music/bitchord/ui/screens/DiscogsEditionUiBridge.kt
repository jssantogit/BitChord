package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsCanonicalEditionCoordinator
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionAccess
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import kotlinx.coroutines.CancellationException

/**
 * Single source-aware bridge for both online and unified Library release
 * details. A new edition key cancels previous in-flight work and immediately
 * resets the state so navigating never leaks a prior pressing's metadata.
 *
 * Enabled stays false in production until source-rights/API distribution and
 * physical Android smoke are cleared. No network request while disabled.
 */
@Composable
internal fun rememberDiscogsEditionForRelease(
    releaseMbid: String?,
    enabled: Boolean = DiscogsEditionAccess.ENABLED,
    coordinator: DiscogsCanonicalEditionCoordinator? = null,
): DiscogsEditionEnrichment? {
    val state by produceState<DiscogsEditionEnrichment?>(
        initialValue = null,
        key1 = releaseMbid,
        key2 = enabled,
        key3 = coordinator,
    ) {
        value = null
        if (!enabled || releaseMbid.isNullOrBlank()) return@produceState
        value = try {
            (coordinator ?: DiscogsEditionAccess.coordinator).observeRelease(releaseMbid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiscogsEditionEnrichment.Unavailable
        }
    }
    return state
}
