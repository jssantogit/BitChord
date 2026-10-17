package com.music.bitchord.data.catalog

import android.content.Context
import androidx.room.withTransaction
import com.music.bitchord.data.model.Song
import java.util.Locale
import java.util.UUID

internal object CanonicalLocalLibrary {
    private const val LOCAL_MEDIASTORE_PROVIDER = "local-mediastore"
    private const val LOCAL_TAGS_PROVIDER = "local-tags"
    private const val YOUTUBE_PROVIDER = "youtube"
    private const val RECORDING_ENTITY_TYPE = "recording"
    private const val RELEASE_ENTITY_TYPE = "release"

    /**
     * Reconciles the latest phone MediaStore scan into canonical rows and then
     * reads Local Music back from Room. The existing UI/player sees only the
     * legacy adapter output; Room is the source of truth after this boundary.
     */
    suspend fun sync(context: Context, scannedSongs: List<Song>): List<Song> {
        val database = CanonicalCatalogDatabase.get(context)
        return database.withTransaction {
            val dao = database.catalogDao()
            val candidates = scannedSongs.mapNotNull(::candidateFromLegacySong)
            val seenSourceKeys = ArrayList<String>(candidates.size)

            for (candidate in candidates) {
                seenSourceKeys += candidate.sourceKey

                val existingAsset = dao.mediaAsset(LOCAL_MEDIASTORE_PROVIDER, candidate.sourceKey)
                val rememberedRecordingId = dao.entityIdForExternalIdentity(
                    LOCAL_MEDIASTORE_PROVIDER,
                    RECORDING_ENTITY_TYPE,
                    candidate.sourceKey,
                )
                val recordingId = existingAsset?.recordingId
                    ?: rememberedRecordingId
                    ?: UUID.randomUUID().toString()
                val mediaAssetId = existingAsset?.id ?: UUID.randomUUID().toString()

                dao.upsertRecording(
                    RecordingEntity(
                        id = recordingId,
                        title = candidate.title,
                        artistDisplay = candidate.artistDisplay,
                        durationMs = candidate.durationMs,
                    ),
                )
                dao.upsertExternalIdentity(
                    ExternalIdentityEntity(
                        provider = LOCAL_MEDIASTORE_PROVIDER,
                        entityType = RECORDING_ENTITY_TYPE,
                        externalId = candidate.sourceKey,
                        entityId = recordingId,
                    ),
                )
                dao.upsertMediaAsset(
                    MediaAssetEntity(
                        id = mediaAssetId,
                        recordingId = recordingId,
                        provider = LOCAL_MEDIASTORE_PROVIDER,
                        sourceKey = candidate.sourceKey,
                        uri = candidate.uri,
                        path = candidate.path,
                        dateAddedSeconds = candidate.dateAddedSeconds,
                        dateModifiedSeconds = candidate.dateModifiedSeconds,
                        artworkUri = candidate.artworkUri,
                    ),
                )

                dao.deleteReleaseTracksForRecording(recordingId)
                candidate.albumTitle?.let { albumTitle ->
                    val releaseEvidenceKey = localReleaseEvidenceKey(
                        albumTitle = albumTitle,
                        artistDisplay = candidate.artistDisplay,
                    )
                    val releaseId = dao.entityIdForExternalIdentity(
                        LOCAL_TAGS_PROVIDER,
                        RELEASE_ENTITY_TYPE,
                        releaseEvidenceKey,
                    ) ?: UUID.randomUUID().toString()

                    dao.upsertRelease(
                        ReleaseEntity(
                            id = releaseId,
                            title = albumTitle,
                            artistDisplay = candidate.artistDisplay,
                            artworkUri = candidate.artworkUri,
                        ),
                    )
                    dao.upsertExternalIdentity(
                        ExternalIdentityEntity(
                            provider = LOCAL_TAGS_PROVIDER,
                            entityType = RELEASE_ENTITY_TYPE,
                            externalId = releaseEvidenceKey,
                            entityId = releaseId,
                        ),
                    )
                    dao.upsertReleaseTrack(
                        ReleaseTrackEntity(
                            releaseId = releaseId,
                            recordingId = recordingId,
                            trackNumber = candidate.trackNumber,
                        ),
                    )
                }

                candidate.youtubeVideoId?.let { youtubeVideoId ->
                    dao.upsertExternalIdentity(
                        ExternalIdentityEntity(
                            provider = YOUTUBE_PROVIDER,
                            entityType = RECORDING_ENTITY_TYPE,
                            externalId = youtubeVideoId,
                            entityId = recordingId,
                        ),
                    )
                }
            }

            if (seenSourceKeys.isEmpty()) {
                dao.deleteAllMediaAssets(LOCAL_MEDIASTORE_PROVIDER)
            } else {
                dao.deleteMediaAssetsMissingFromScan(LOCAL_MEDIASTORE_PROVIDER, seenSourceKeys)
            }

            dao.localRows(
                localProvider = LOCAL_MEDIASTORE_PROVIDER,
                youtubeProvider = YOUTUBE_PROVIDER,
                recordingEntityType = RECORDING_ENTITY_TYPE,
            ).map(CanonicalLocalRow::toLegacySong)
        }
    }

    internal fun candidateFromLegacySong(song: Song): LocalMediaCandidate? {
        val uri = song.localUri?.takeIf(String::isNotBlank) ?: return null
        return LocalMediaCandidate(
            sourceKey = uri,
            title = song.title,
            artistDisplay = song.artist,
            durationMs = parseDurationMillis(song.durationText),
            albumTitle = song.albumName.cleanOptionalTag(),
            artworkUri = song.thumbnailUrl,
            uri = uri,
            path = song.localPath,
            dateAddedSeconds = song.localDateAddedSeconds,
            dateModifiedSeconds = song.localDateModifiedSeconds,
            trackNumber = song.trackNumber,
            youtubeVideoId = song.videoId.takeIf { id ->
                id != uri &&
                    !id.startsWith("content://", ignoreCase = true) &&
                    !id.startsWith("file://", ignoreCase = true)
            },
        )
    }

    internal fun localReleaseEvidenceKey(albumTitle: String, artistDisplay: String): String =
        "${normalizeEvidence(albumTitle)}|${normalizeEvidence(artistDisplay)}"

    internal fun parseDurationMillis(durationText: String?): Long? {
        val rawParts = durationText?.trim()?.split(':') ?: return null
        if (rawParts.size !in 2..3) return null

        val parts = rawParts.map { part ->
            part.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        if (parts.last() !in 0L..59L) return null
        if (parts.size == 3 && parts[1] !in 0L..59L) return null

        val totalSeconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3_600L + parts[1] * 60L + parts[2]
            else -> return null
        }
        return totalSeconds.takeIf { it >= 0L }?.times(1_000L)
    }

    private fun normalizeEvidence(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun String?.cleanOptionalTag(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
}

internal data class LocalMediaCandidate(
    val sourceKey: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val albumTitle: String?,
    val artworkUri: String?,
    val uri: String,
    val path: String?,
    val dateAddedSeconds: Long?,
    val dateModifiedSeconds: Long?,
    val trackNumber: Int?,
    val youtubeVideoId: String?,
)
