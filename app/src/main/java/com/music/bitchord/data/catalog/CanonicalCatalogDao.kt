package com.music.bitchord.data.catalog

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

internal data class CanonicalArtistCreditRow(
    val artistId: String,
    val artistName: String,
    val position: Int,
)

@Dao
internal interface CanonicalCatalogDao {

    @Query("SELECT * FROM catalog_media_asset WHERE id = :mediaAssetId LIMIT 1")
    suspend fun mediaAssetById(mediaAssetId: String): MediaAssetEntity?

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
    suspend fun upsertArtist(artist: ArtistEntity)

    @Upsert
    suspend fun upsertRecordingArtistCredits(credits: List<RecordingArtistCreditEntity>)

    @Upsert
    suspend fun upsertReleaseArtistCredits(credits: List<ReleaseArtistCreditEntity>)

    @Query("DELETE FROM catalog_recording_artist_credit WHERE recordingId = :recordingId")
    suspend fun deleteRecordingArtistCredits(recordingId: String)

    @Query("DELETE FROM catalog_release_artist_credit WHERE releaseId = :releaseId")
    suspend fun deleteReleaseArtistCredits(releaseId: String)

    @Transaction
    suspend fun replaceRecordingArtistCredits(
        recordingId: String,
        credits: List<RecordingArtistCreditEntity>,
    ) {
        deleteRecordingArtistCredits(recordingId)
        if (credits.isNotEmpty()) upsertRecordingArtistCredits(credits)
    }

    @Transaction
    suspend fun replaceReleaseArtistCredits(
        releaseId: String,
        credits: List<ReleaseArtistCreditEntity>,
    ) {
        deleteReleaseArtistCredits(releaseId)
        if (credits.isNotEmpty()) upsertReleaseArtistCredits(credits)
    }

    @Query(
        """
        SELECT
            credit.artistId AS artistId,
            artist.name AS artistName,
            credit.position AS position
        FROM catalog_recording_artist_credit AS credit
        INNER JOIN catalog_artist AS artist ON artist.id = credit.artistId
        WHERE credit.recordingId = :recordingId
        ORDER BY credit.position ASC
        """,
    )
    suspend fun recordingArtistCredits(recordingId: String): List<CanonicalArtistCreditRow>

    @Query(
        """
        SELECT
            credit.artistId AS artistId,
            artist.name AS artistName,
            credit.position AS position
        FROM catalog_release_artist_credit AS credit
        INNER JOIN catalog_artist AS artist ON artist.id = credit.artistId
        WHERE credit.releaseId = :releaseId
        ORDER BY credit.position ASC
        """,
    )
    suspend fun releaseArtistCredits(releaseId: String): List<CanonicalArtistCreditRow>

    @Query(
        """
        SELECT recording.*
        FROM catalog_recording AS recording
        INNER JOIN catalog_recording_artist_credit AS credit
            ON credit.recordingId = recording.id
        WHERE credit.artistId = :artistId
        ORDER BY recording.title COLLATE NOCASE, recording.id
        """,
    )
    suspend fun recordingsByArtist(artistId: String): List<RecordingEntity>

    @Upsert
    suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity)

    @Upsert
    suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity)

    @Query("DELETE FROM catalog_release_track WHERE recordingId = :recordingId")
    suspend fun deleteReleaseTracksForRecording(recordingId: String)

    @Query("DELETE FROM catalog_media_asset WHERE provider = :provider")
    suspend fun deleteAllMediaAssets(provider: String)

    @Query("SELECT sourceKey FROM catalog_media_asset WHERE provider = :provider")
    suspend fun mediaAssetSourceKeys(provider: String): List<String>

    @Query(
        """
        DELETE FROM catalog_media_asset
        WHERE provider = :provider AND sourceKey = :sourceKey
        """,
    )
    suspend fun deleteMediaAsset(provider: String, sourceKey: String)

    @Query(
        """
        SELECT
            recording.id AS recordingId,
            asset.id AS mediaAssetId,
            asset.provider AS provider,
            asset.sourceKey AS sourceKey,
            recording.title AS title,
            recording.artistDisplay AS artistDisplay,
            recording.durationMs AS durationMs,
            asset.artworkUri AS artworkUri,
            asset.uri AS uri,
            asset.dateAddedSeconds AS dateAddedSeconds
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording
            ON recording.id = asset.recordingId
        WHERE asset.provider = :provider
        ORDER BY recording.artistDisplay COLLATE NOCASE, recording.title COLLATE NOCASE
        """,
    )
    suspend fun providerRows(provider: String): List<CanonicalProviderRow>

    /**
     * Provider-neutral browse projection. It intentionally omits sourceKey and
     * URI so a provider-native track id cannot leak into UI identity/playback.
     */
    @Query(
        """
        SELECT
            recording.id AS recordingId,
            asset.id AS mediaAssetId,
            asset.provider AS providerKey,
            recording.title AS title,
            recording.artistDisplay AS artistDisplay,
            recording.durationMs AS durationMs,
            asset.artworkUri AS artworkUri
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording
            ON recording.id = asset.recordingId
        WHERE asset.provider = :providerKey
        ORDER BY
            recording.artistDisplay COLLATE NOCASE,
            recording.title COLLATE NOCASE,
            recording.id,
            asset.id
        """,
    )
    fun observeProviderBrowseAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>>

    @Query(
        """
        SELECT
            recording.id AS recordingId,
            asset.id AS mediaAssetId,
            asset.sourceKey AS sourceKey,
            recording.title AS title,
            recording.artistDisplay AS artistDisplay,
            recording.durationMs AS durationMs,
            albumRelease.title AS albumTitle,
            COALESCE(albumRelease.artworkUri, asset.artworkUri) AS artworkUri,
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
        LEFT JOIN catalog_release AS albumRelease
            ON albumRelease.id = releaseTrack.releaseId
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
