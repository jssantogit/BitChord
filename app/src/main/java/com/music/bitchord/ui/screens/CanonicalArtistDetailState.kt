package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.listening.ListeningEventDatabase
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
 * A verified artist identity, not its display string, owns nested album state.
 * Local display-only groups remain independently keyed, with a disjoint
 * namespace so no provider ID can collide with a visible name.
 */
internal fun canonicalArtistDetailNavigationKey(
    artistId: String?,
    artistName: String,
): String = artistId?.takeIf { it.isNotBlank() }
    ?.let { "artist-id:$it" } ?: "display-artist:$artistName"


/**
 * Source-independent nested release navigation. The selected ReleaseEntity
 * stays scoped to verified artist identity; toolbar events never use a
 * display name or synthesize a provider browseId to find the release.
 */
internal class CanonicalArtistNestedNavigation internal constructor(
    private val current: androidx.compose.runtime.MutableState<ReleaseEntity?>,
) {
    val openedRelease: ReleaseEntity? get() = current.value
    fun open(release: ReleaseEntity) { current.value = release }
    fun close() { current.value = null }
}

@Composable
internal fun rememberCanonicalArtistNestedNavigation(
    artistId: String?,
    artistName: String,
    nestedBackToken: Int,
    albumMenuToken: Int,
    albumPlaylistToken: Int,
    onNestedDetailChanged: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onReleaseOptions: (ReleaseEntity) -> Unit,
    onNestedAlbumAddToPlaylist: (ReleaseEntity) -> Unit,
): CanonicalArtistNestedNavigation {
    val navigation = remember(canonicalArtistDetailNavigationKey(artistId, artistName)) {
        CanonicalArtistNestedNavigation(androidx.compose.runtime.mutableStateOf(null))
    }
    val selected = navigation.openedRelease
    androidx.compose.runtime.LaunchedEffect(selected?.id) {
        onNestedDetailChanged(selected != null)
        if (selected != null) onQueryChange("")
    }
    androidx.compose.runtime.LaunchedEffect(nestedBackToken) {
        if (nestedBackToken > 0) navigation.close()
    }
    androidx.compose.runtime.LaunchedEffect(albumMenuToken) {
        if (albumMenuToken > 0) navigation.openedRelease?.let(onReleaseOptions)
    }
    androidx.compose.runtime.LaunchedEffect(albumPlaylistToken) {
        if (albumPlaylistToken > 0) navigation.openedRelease?.let(onNestedAlbumAddToPlaylist)
    }
    androidx.activity.compose.BackHandler(enabled = selected != null) {
        navigation.close()
    }
    return navigation
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

/**
 * Union available album membership across currently selected asset sources.
 * The provider-specific remote IDs never enter this list; only RecordingIds.
 */
internal fun canonicalArtistNestedRecordingOrder(
    sourceRecordingIds: List<List<String>>,
): List<String> = sourceRecordingIds.flatten().distinct()

/**
 * The nested album controller queries the catalog independently of the
 * artist screen's visual tree. Later, the same result feeds NativeAlbumDetail
 * when the Library renderer is retired.
 */
@Composable
internal fun rememberCanonicalArtistNestedRecordings(
    releaseId: String?,
    sourceKeys: List<String>,
    selectedSourceKey: String?,
): State<List<String>> {
    val context = LocalContext.current.applicationContext
    val catalogDb = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(catalogDb) { catalogDb.catalogDao() }
    val keys = sourceKeys.filter { selectedSourceKey == null || it == selectedSourceKey }
    return produceState(initialValue = emptyList(), key1 = releaseId, key2 = keys) {
        val id = releaseId ?: return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                canonicalArtistNestedRecordingOrder(
                    keys.map { dao.providerScopedReleaseRecordingIds(it, id) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}


/**
 * A first-party rating is bound only to a proven Library ArtistId or a
 * dedicated DISPLAY_ARTIST group key. Never interpret a public MusicBrainz
 * MBID, artist name or legacy videoId as a local rating identity.
 */
internal fun canonicalArtistRatingTarget(
    artistId: String?,
): CanonicalEntityRatingTarget? = artistId?.let { id ->
    canonicalEntityRatingTarget(
        CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, id),
    )
}

internal data class CanonicalArtistRatingUiState(
    val target: CanonicalEntityRatingTarget?,
    val rated: Boolean = false,
)

/**
 * The original Library rating observer lives in the provider-independent
 * detail state. The shared native compositor will consume this exact state.
 */
@Composable
internal fun rememberCanonicalArtistRating(
    artistId: String?,
): State<CanonicalArtistRatingUiState> {
    val context = LocalContext.current.applicationContext
    val target = canonicalArtistRatingTarget(artistId)
    return produceState(
        initialValue = CanonicalArtistRatingUiState(target),
        key1 = target,
    ) {
        value = CanonicalArtistRatingUiState(target)
        if (target == null) return@produceState
        ListeningEventDatabase.get(context).canonicalEntityRatingDao()
            .observe(target.entityType, target.entityId)
            .collectLatest { saved ->
                value = CanonicalArtistRatingUiState(target, rated = saved != null)
            }
    }
}

/**
 * A verified ReleaseId owns album rating regardless of whether the album was
 * opened from the Library root or a nested artist. No provider browse ID.
 */
@Composable
internal fun rememberCanonicalNestedAlbumRating(releaseId: String): State<Double?> {
    val context = LocalContext.current.applicationContext
    return produceState<Double?>(initialValue = null, key1 = releaseId) {
        ListeningEventDatabase.get(context).canonicalEntityRatingDao()
            .observe("ALBUM", releaseId).collectLatest { saved ->
                value = saved?.ratingValue?.takeIf(::isValidCanonicalEntityRating)
            }
    }
}
