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
 * Attach every asynchronous result to the identity that requested it.
 *
 * Compose restarts produceState when a key changes, but it may retain the
 * previous value until the new effect begins. Filtering by the requested
 * edition at read time prevents a one-frame leak into a different album.
 */
internal data class DiscogsBoundReleaseEvidence(
    val requestedMbid: String,
    val evidence: DiscogsEditionEnrichment,
)

internal fun discogsEvidenceForVisibleRelease(
    releaseMbid: String?,
    enabled: Boolean,
    observation: DiscogsBoundReleaseEvidence?,
): DiscogsEditionEnrichment? =
    observation?.takeIf {
        enabled && !releaseMbid.isNullOrBlank() &&
            it.requestedMbid == releaseMbid
    }?.evidence

internal data class DiscogsBoundLibraryIdentity(
    val requestedReleaseId: String,
    val releaseMbid: String?,
)

internal fun discogsMbidForVisibleLibraryRelease(
    releaseId: String?,
    observation: DiscogsBoundLibraryIdentity?,
): String? =
    observation?.takeIf {
        !releaseId.isNullOrBlank() && it.requestedReleaseId == releaseId
    }?.releaseMbid

/**
 * Single source-aware bridge for online and unified Library release details.
 * No network request when the personal-use provider is disabled.
 */
@Composable
internal fun rememberDiscogsEditionForRelease(
    releaseMbid: String?,
    enabled: Boolean = DiscogsEditionAccess.ENABLED,
    coordinator: DiscogsCanonicalEditionCoordinator? = null,
): DiscogsEditionEnrichment? {
    val state by produceState<DiscogsBoundReleaseEvidence?>(
        initialValue = null,
        key1 = releaseMbid,
        key2 = enabled,
        key3 = coordinator,
    ) {
        value = null
        if (!enabled) return@produceState
        val mbid = releaseMbid?.takeIf(String::isNotBlank) ?: return@produceState
        val observed = try {
            (coordinator ?: DiscogsEditionAccess.coordinator).observeRelease(mbid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiscogsEditionEnrichment.Unavailable
        }
        value = DiscogsBoundReleaseEvidence(mbid, observed)
    }
    return discogsEvidenceForVisibleRelease(releaseMbid, enabled, state)
}

/**
 * Resolve one Library ReleaseId to a verified MusicBrainz *Release* identity.
 * Both artist-nested albums and direct Library albums use this same observer;
 * no duplicated screen-specific ID resolver or title-matching fallback.
 *
 * The provider gate short circuits before any Room or network operations.
 */
@Composable
internal fun rememberDiscogsLibraryEditionMbid(
    releaseId: String?,
    catalogDb: CanonicalCatalogDatabase,
    catalogDao: CanonicalCatalogDao,
): String? {
    val identity by produceState<DiscogsBoundLibraryIdentity?>(
        initialValue = null,
        key1 = releaseId,
        key2 = catalogDb,
        key3 = catalogDao,
    ) {
        value = null
        if (!DiscogsEditionAccess.ENABLED) return@produceState
        val selectedReleaseId = releaseId?.takeIf(String::isNotBlank)
            ?: return@produceState
        catalogDb.invalidationTracker.createFlow(
            "musicbrainz_release_enrichment",
        ).collectLatest {
            val mbid = try {
                withContext(Dispatchers.IO) {
                    discogsLibraryReleaseMbid(
                        selectedReleaseId,
                        catalogDao.musicBrainzReleaseEnrichment(selectedReleaseId),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            value = DiscogsBoundLibraryIdentity(selectedReleaseId, mbid)
        }
    }
    return discogsMbidForVisibleLibraryRelease(releaseId, identity)
}
