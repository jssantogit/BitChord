package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.discogs.discogsLibraryReleaseMbid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
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

/**
 * Resolve one Library ReleaseId to a verified MusicBrainz *Release* identity.
 * Both artist-nested albums and direct Library albums use this same observer;
 * no duplicated screen-specific ID resolver or title-matching fallback.
 *
 * The default-off gate short circuits before any Room or network operations.
 */
@Composable
internal fun rememberDiscogsLibraryEditionMbid(
    releaseId: String?,
    catalogDb: CanonicalCatalogDatabase,
    catalogDao: CanonicalCatalogDao,
): String? {
    val identity by produceState<String?>(
        initialValue = null,
        key1 = releaseId,
        key2 = catalogDb,
        key3 = catalogDao,
    ) {
        value = null
        if (!DiscogsEditionAccess.ENABLED || releaseId.isNullOrBlank()) {
            return@produceState
        }
        catalogDb.invalidationTracker.createFlow(
            "musicbrainz_release_enrichment",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
                    discogsLibraryReleaseMbid(
                        releaseId, catalogDao.musicBrainzReleaseEnrichment(releaseId),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    return identity
}
