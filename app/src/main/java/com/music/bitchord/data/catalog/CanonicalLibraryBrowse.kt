package com.music.bitchord.data.catalog

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence projection: one row per canonical MediaAsset available through a
 * provider and its canonical Recording. Provider-native source IDs and stream
 * URIs intentionally do not cross this browse boundary.
 */
internal data class CanonicalBrowseAssetRow(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val artworkUri: String?,
)

/** Small provider-neutral seam so browse behavior is JVM-testable without Room. */
internal interface CanonicalLibraryBrowseStore {
    fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>>
}

internal data class CanonicalBrowseMediaAsset(
    val mediaAssetId: String,
    val providerKey: String,
)

/**
 * A musical row is a canonical Recording with one or more currently available
 * MediaAssets. Its identity is [recordingId], never a provider source ID.
 */
internal data class CanonicalBrowseTrack(
    val recordingId: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val artworkUri: String?,
    val availableAssets: List<CanonicalBrowseMediaAsset>,
)

internal data class CanonicalBrowseLibrary(
    val tracks: List<CanonicalBrowseTrack>,
) {
    companion object {
        val EMPTY = CanonicalBrowseLibrary(emptyList())
    }
}

/**
 * Builds a stable, provider-agnostic browse model from persisted canonical
 * catalog rows. Sorting/grouping happens on Room emissions rather than in
 * Compose recomposition.
 */
internal class CanonicalLibraryBrowseRepository(
    private val store: CanonicalLibraryBrowseStore,
) {
    fun observe(providerKey: String): Flow<CanonicalBrowseLibrary> =
        store.observeTrackAssets(providerKey).map { rows ->
            CanonicalBrowseLibrary(tracks = buildTracks(rows))
        }

    private fun buildTracks(rows: List<CanonicalBrowseAssetRow>): List<CanonicalBrowseTrack> = rows
        .groupBy(CanonicalBrowseAssetRow::recordingId)
        .values
        .mapNotNull { recordingRows ->
            val first = recordingRows.firstOrNull() ?: return@mapNotNull null
            CanonicalBrowseTrack(
                recordingId = first.recordingId,
                title = first.title,
                artistDisplay = first.artistDisplay,
                durationMs = first.durationMs,
                artworkUri = recordingRows.firstNotNullOfOrNull { row ->
                    row.artworkUri?.takeIf(String::isNotBlank)
                },
                availableAssets = recordingRows
                    .distinctBy(CanonicalBrowseAssetRow::mediaAssetId)
                    .map { row ->
                        CanonicalBrowseMediaAsset(
                            mediaAssetId = row.mediaAssetId,
                            providerKey = row.providerKey,
                        )
                    }
                    .sortedBy(CanonicalBrowseMediaAsset::mediaAssetId),
            )
        }
        .sortedWith(
            compareBy<CanonicalBrowseTrack> { it.artistDisplay.lowercase(Locale.ROOT) }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy(CanonicalBrowseTrack::recordingId),
        )
}

/** Room adapter; the repository itself remains independent of Android/Room. */
internal class RoomCanonicalLibraryBrowseStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalLibraryBrowseStore {
    override fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>> =
        dao.observeProviderBrowseAssets(providerKey)
}
