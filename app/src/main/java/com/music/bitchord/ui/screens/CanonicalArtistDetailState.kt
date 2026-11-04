package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

internal data class CanonicalArtistNativeReleases(
    val sections: CanonicalArtistReleaseSections,
    val artwork: Map<String, CanonicalBrowseTrack>,
    val evidence: Map<String, CanonicalArtistReleaseEvidence> = emptyMap(),
) {
    companion object {
        val EMPTY = CanonicalArtistNativeReleases(
            canonicalArtistReleaseSections(emptyList(), emptyMap()), emptyMap(),
        )
    }
}

/**
 * Only a real Recording -> Release edge permits an album in an artist page.
 * Equal title/credit across two releases is never relationship evidence.
 * Release ordering follows the scoped provider response; repeated releases
 * across Local and Navidrome are not duplicated in the UI.
 */
internal fun canonicalArtistVerifiedReleases(
    scopedReleases: List<ReleaseEntity>,
    linkedReleaseIds: Set<String>,
): List<ReleaseEntity> = scopedReleases
    .distinctBy { it.id }
    .filter { it.id in linkedReleaseIds }

/**
 * Source-neutral, reactive detail data. It owns Room queries and evidence,
 * not navigation, gestures, artwork composition, ratings or player actions.
 * This can be reused by the native DetailScreen when we retire the Library
 * copy of the artist renderer; MusicBrainz identities remain separate.
 */
@Composable
internal fun rememberCanonicalArtistReleases(
    tracks: List<CanonicalBrowseTrack>,
    sourceKeys: List<String>,
    selectedSourceKey: String?,
): State<CanonicalArtistNativeReleases> {
    val context = LocalContext.current.applicationContext
    val catalogDb = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(catalogDb) { catalogDb.catalogDao() }
    val keys = sourceKeys.filter { selectedSourceKey == null || it == selectedSourceKey }
    return produceState(
        initialValue = CanonicalArtistNativeReleases.EMPTY,
        key1 = tracks.map { it.recordingId },
        key2 = keys,
    ) {
        // A delayed MusicBrainz classification or provider rescan must refresh
        // an artist already on screen, without requiring navigation away/back.
        catalogDb.invalidationTracker.createFlow(
            "catalog_release", "catalog_release_track", "catalog_media_asset",
            "catalog_release_group", "catalog_release_group_edition",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
                    val recordingIds = tracks.map { it.recordingId }
                val releaseIds = recordingIds.chunked(300).flatMap {
                    dao.releaseIdsForVisibleRecordings(it)
                }.toSet()
                val releases = canonicalArtistVerifiedReleases(
                    keys.flatMap { dao.providerScopedReleases(it) },
                    releaseIds,
                )
                val proofs = releases.map { it.id }.chunked(300).flatMap {
                    dao.canonicalArtistReleaseEvidence(it)
                }.associate { proof ->
                    proof.releaseId to CanonicalArtistReleaseEvidence(
                        proof.releaseId, proof.primaryType, proof.firstReleaseDate,
                    )
                }
                val coverLinks = releases.map { it.id }.chunked(300).flatMap { ids ->
                    dao.playlistAlbumCoverRecordingIds(keys, ids)
                }
                val counts = releases.map { it.id }.chunked(300).flatMap { ids ->
                    dao.scopedReleaseRecordingCounts(ids, keys)
                }.associate { it.releaseId to it.recordingCount }
                val sections = canonicalArtistReleaseSections(releases, proofs, counts)
                // Count-only diagnostics distinguish missing ReleaseGroup types
                // from missing memberships without leaking account IDs/titles.
                runCatching {
                    Diagnostics.event(
                        category = DiagnosticCategory.CATALOG,
                        name = "unified_artist_shelves_v1",
                        metadata = mapOf(
                            "sources" to keys.size.toString(),
                            "releases" to releases.size.toString(),
                            "typed" to proofs.values.count { !it.primaryType.isNullOrBlank() }.toString(),
                            "counted" to counts.size.toString(),
                            "albums" to sections.albums.size.toString(),
                            "singlesEps" to sections.singlesAndEps.size.toString(),
                            "unknown" to sections.unclassified.size.toString(),
                        ),
                    )
                }
                CanonicalArtistNativeReleases(
                    sections, canonicalLibraryEntityArtworkById(coverLinks, tracks), proofs,
                )
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching {
                    Diagnostics.event(
                        category = DiagnosticCategory.CATALOG,
                        name = "unified_artist_shelves_failed_v1",
                        metadata = mapOf(
                            "errorType" to error.javaClass.simpleName,
                            "sources" to keys.size.toString(),
                            "visibleTracks" to tracks.size.toString(),
                        ),
                    )
                }
                CanonicalArtistNativeReleases.EMPTY
            }
        }
    }

}
