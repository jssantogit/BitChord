package com.music.bitchord.data.catalog

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface CanonicalCatalogDao {

    @Query(
        """
        SELECT * FROM catalog_media_asset
        WHERE provider = :provider AND sourceKey = :sourceKey
        LIMIT 1
        """,
    )
    suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity?

    @Query(
        """
        SELECT entityId FROM catalog_external_identity
        WHERE provider = :provider
          AND entityType = :entityType
          AND externalId = :externalId
        LIMIT 1
        """,
    )
    suspend fun entityIdForExternalIdentity(
        provider: String,
        entityType: String,
        externalId: String,
    ): String?

    @Upsert
    suspend fun upsertRecording(recording: RecordingEntity)

    @Upsert
    suspend fun upsertRelease(release: ReleaseEntity)

    @Upsert
    suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity)

    @Upsert
    suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity)

    @Upsert
    suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity)

    @Query("DELETE FROM catalog_release_track WHERE recordingId = :recordingId")
    suspend fun deleteReleaseTracksForRecording(recordingId: String)

    @Query("DELETE FROM catalog_media_asset WHERE provider = :provider")
    suspend fun deleteAllMediaAssets(provider: String)

    @Query(
        """
        DELETE FROM catalog_media_asset
        WHERE provider = :provider
          AND sourceKey NOT IN (:sourceKeys)
        """,
    )
    suspend fun deleteMediaAssetsMissingFromScan(provider: String, sourceKeys: List<String>)

    @Query(
        """
        SELECT
            recording.id AS recordingId,
            asset.id AS mediaAssetId,
            asset.sourceKey AS sourceKey,
            recording.title AS title,
            recording.artistDisplay AS artistDisplay,
            recording.durationMs AS durationMs,
            release.title AS albumTitle,
            COALESCE(release.artworkUri, asset.artworkUri) AS artworkUri,
            asset.uri AS uri,
            asset.path AS path,
            asset.dateAddedSeconds AS dateAddedSeconds,
            asset.dateModifiedSeconds AS dateModifiedSeconds,
            releaseTrack.trackNumber AS trackNumber,
            (
                SELECT identity.externalId
                FROM catalog_external_identity AS identity
                WHERE identity.entityId = recording.id
                  AND identity.provider = :youtubeProvider
                  AND identity.entityType = :recordingEntityType
                LIMIT 1
            ) AS youtubeVideoId
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording
            ON recording.id = asset.recordingId
        LEFT JOIN catalog_release_track AS releaseTrack
            ON releaseTrack.recordingId = recording.id
        LEFT JOIN catalog_release AS release
            ON release.id = releaseTrack.releaseId
        WHERE asset.provider = :localProvider
        ORDER BY recording.artistDisplay COLLATE NOCASE, recording.title COLLATE NOCASE
        """,
    )
    suspend fun localRows(
        localProvider: String,
        youtubeProvider: String,
        recordingEntityType: String,
    ): List<CanonicalLocalRow>
}
