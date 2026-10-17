package com.music.bitchord.data.catalog

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

/**
 * Persistence projection for canonical Release membership. Availability is
 * verified by the query/store before this row reaches the repository.
 */
internal data class CanonicalBrowseReleaseTrackRow(
    val releaseId: String,
    val releaseTitle: String,
    val releaseArtistDisplay: String?,
    val releaseArtworkUri: String?,
    val recordingId: String,
    val trackNumber: Int?,
)

/** Small provider-neutral seam so browse behavior is JVM-testable without Room. */
internal interface CanonicalLibraryBrowseStore {
    fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>>
    fun observeReleaseTracks(providerKey: String): Flow<List<CanonicalBrowseReleaseTrackRow>>
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

internal data class CanonicalBrowseReleaseTrack(
    val track: CanonicalBrowseTrack,
    val trackNumber: Int?,
)

internal data class CanonicalBrowseRelease(
    val releaseId: String,
    val title: String,
    val artistDisplay: String?,
    val artworkUri: String?,
    val tracks: List<CanonicalBrowseReleaseTrack>,
)

internal data class CanonicalBrowseLibrary(
    val tracks: List<CanonicalBrowseTrack>,
    val releases: List<CanonicalBrowseRelease>,
) {
    companion object {
        val EMPTY = CanonicalBrowseLibrary(emptyList(), emptyList())
    }
}

/**
 * Builds a stable, provider-agnostic browse model from persisted canonical
 * catalog rows. Sorting/grouping happens on database emissions rather than in
 * Compose recomposition.
 */
internal class CanonicalLibraryBrowseRepository(
    private val store: CanonicalLibraryBrowseStore,
) {
    fun observe(providerKey: String): Flow<CanonicalBrowseLibrary> = combine(
        store.observeTrackAssets(providerKey),
        store.observeReleaseTracks(providerKey),
    ) { assetRows, releaseRows ->
        val tracks = buildTracks(assetRows)
        CanonicalBrowseLibrary(
            tracks = tracks,
            releases = buildReleases(releaseRows, tracks),
        )
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

    private fun buildReleases(
        rows: List<CanonicalBrowseReleaseTrackRow>,
        tracks: List<CanonicalBrowseTrack>,
    ): List<CanonicalBrowseRelease> {
        if (rows.isEmpty() || tracks.isEmpty()) return emptyList()
        val tracksById = tracks.associateBy(CanonicalBrowseTrack::recordingId)
        return rows
            .groupBy(CanonicalBrowseReleaseTrackRow::releaseId)
            .values
            .mapNotNull { releaseRows ->
                val first = releaseRows.firstOrNull() ?: return@mapNotNull null
                val memberships = releaseRows
                    .mapNotNull { row ->
                        tracksById[row.recordingId]?.let { track ->
                            CanonicalBrowseReleaseTrack(track, row.trackNumber)
                        }
                    }
                    .distinctBy { it.track.recordingId }
                    .sortedWith(
                        compareBy<CanonicalBrowseReleaseTrack> { it.trackNumber == null }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy { it.track.title.lowercase(Locale.ROOT) }
                            .thenBy { it.track.recordingId },
                    )
                if (memberships.isEmpty()) return@mapNotNull null
                CanonicalBrowseRelease(
                    releaseId = first.releaseId,
                    title = first.releaseTitle,
                    artistDisplay = first.releaseArtistDisplay,
                    artworkUri = releaseRows.firstNotNullOfOrNull { row ->
                        row.releaseArtworkUri?.takeIf(String::isNotBlank)
                    },
                    tracks = memberships,
                )
            }
            .sortedWith(
                compareBy<CanonicalBrowseRelease> {
                    it.artistDisplay.orEmpty().lowercase(Locale.ROOT)
                }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy(CanonicalBrowseRelease::releaseId),
            )
    }
}

/** Room adapter; the repository itself remains independent of Android/Room. */
internal class RoomCanonicalLibraryBrowseStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalLibraryBrowseStore {
    override fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>> =
        dao.observeProviderBrowseAssets(providerKey)

    override fun observeReleaseTracks(providerKey: String): Flow<List<CanonicalBrowseReleaseTrackRow>> =
        dao.observeProviderBrowseReleaseTracks(providerKey)
}
