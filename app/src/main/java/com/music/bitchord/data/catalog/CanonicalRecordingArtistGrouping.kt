package com.music.bitchord.data.catalog

import android.content.Context

/**
 * Provider-neutral Catalog -> Recommendation seam for canonical Recording artist grouping.
 *
 * Only canonical Recording and Artist identities cross this boundary. Display strings,
 * provider/native identities, MediaAssets, and MusicBrainz enrichment evidence remain
 * Catalog implementation details and are intentionally excluded.
 */
internal fun interface CanonicalRecordingArtistGroupingReader {
    suspend fun artistIdsByRecording(
        recordingIds: List<String>,
    ): Map<String, List<String>>
}

/** Minimal canonical projection used by the bulk Room query. */
internal data class CanonicalRecordingArtistIdRow(
    val recordingId: String,
    val artistId: String,
    val position: Int,
)

/** Catalog-internal bulk read abstraction, kept injectable for deterministic tests. */
internal fun interface CanonicalRecordingArtistGroupingStore {
    suspend fun canonicalArtistCreditRows(
        recordingIds: List<String>,
    ): List<CanonicalRecordingArtistIdRow>
}

internal class RoomCanonicalRecordingArtistGroupingStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalRecordingArtistGroupingStore {
    override suspend fun canonicalArtistCreditRows(
        recordingIds: List<String>,
    ): List<CanonicalRecordingArtistIdRow> = dao.recordingArtistCreditIds(recordingIds)
}

/**
 * Canonical Recording -> Artist grouping projection.
 *
 * Recommendation performs one logical call. Catalog chunks large IN queries to a safe,
 * fixed bound and reconstructs a deterministic in-memory result. Recordings without
 * canonical artist credits are represented by an empty list; no metadata fallback is used.
 */
internal class CanonicalRecordingArtistGroupingRepository(
    private val store: CanonicalRecordingArtistGroupingStore,
    private val maxRecordingIdsPerQuery: Int = MAX_RECORDING_IDS_PER_QUERY,
) : CanonicalRecordingArtistGroupingReader {
    init {
        require(maxRecordingIdsPerQuery in 1..MAX_RECORDING_IDS_PER_QUERY) {
            "Recording artist grouping query chunk must be in 1..$MAX_RECORDING_IDS_PER_QUERY"
        }
    }

    override suspend fun artistIdsByRecording(
        recordingIds: List<String>,
    ): Map<String, List<String>> {
        if (recordingIds.isEmpty()) return emptyMap()

        val uniqueIds = recordingIds.asSequence()
            .onEach { recordingId ->
                require(recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
            }
            .distinct()
            .toList()

        val requested = uniqueIds.toHashSet()
        val artistsByRecording = LinkedHashMap<String, LinkedHashSet<String>>(uniqueIds.size)
        uniqueIds.forEach { recordingId ->
            artistsByRecording[recordingId] = linkedSetOf()
        }

        uniqueIds.chunked(maxRecordingIdsPerQuery).forEach { chunk ->
            val chunkIds = chunk.toHashSet()
            store.canonicalArtistCreditRows(chunk)
                .asSequence()
                .filter { row -> row.recordingId in chunkIds && row.recordingId in requested }
                .onEach { row ->
                    require(row.recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
                    require(row.artistId.isNotBlank()) { "Canonical artistId must not be blank" }
                    require(row.position >= 0) { "Canonical artist credit position must not be negative" }
                }
                .sortedWith(
                    compareBy<CanonicalRecordingArtistIdRow> { it.recordingId }
                        .thenBy { it.position }
                        .thenBy { it.artistId },
                )
                .forEach { row ->
                    artistsByRecording.getValue(row.recordingId).add(row.artistId)
                }
        }

        return artistsByRecording.mapValues { (_, artistIds) -> artistIds.toList() }
    }

    internal companion object {
        const val MAX_RECORDING_IDS_PER_QUERY = 500
    }
}

/** Local-only production composition. No provider/network dependency is involved. */
internal object CanonicalRecordingArtistGroupingReaders {
    fun persistent(context: Context): CanonicalRecordingArtistGroupingReader {
        val appContext = context.applicationContext
        return CanonicalRecordingArtistGroupingRepository(
            RoomCanonicalRecordingArtistGroupingStore(
                CanonicalCatalogDatabase.get(appContext).catalogDao(),
            ),
        )
    }
}
