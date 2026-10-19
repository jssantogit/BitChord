package com.music.bitchord.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

internal data class CanonicalArtistCreditRow(
    val artistId: String,
    val artistName: String,
    val position: Int,
)

internal data class ExclusiveExternalIdentityClaimResult(
    val existingExternalIdsForEntity: List<String>,
    val candidateOwnerEntityId: String?,
    val inserted: Boolean,
)

@Dao
internal interface CanonicalCatalogDao {

    @Query("SELECT * FROM catalog_recording WHERE id = :recordingId LIMIT 1")
    suspend fun recordingById(recordingId: String): RecordingEntity?

    @Query("SELECT * FROM catalog_release WHERE id = :releaseId LIMIT 1")
    suspend fun releaseById(releaseId: String): ReleaseEntity?

    @Query("SELECT * FROM catalog_artist WHERE id = :artistId LIMIT 1")
    suspend fun artistById(artistId: String): ArtistEntity?

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

    @Query(
        """
        SELECT * FROM catalog_external_identity
        WHERE provider = :provider
          AND entityType = :entityType
          AND entityId = :entityId
        ORDER BY externalId
        """,
    )
    suspend fun externalIdentitiesForEntity(
        provider: String,
        entityType: String,
        entityId: String,
    ): List<ExternalIdentityEntity>


    /**
     * One Recording's pre-existing, ordered evidence read under one Room transaction.
     * No writes; MusicBrainz cache and canonical Artist identities remain untouched.
     */
    @Transaction
    suspend fun recordingArtistCreditPreviewSnapshot(
        recordingId: String,
    ): com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewSnapshot {
        val evidence = musicBrainzRecordingArtistCredits(recordingId).map {
            com.music.bitchord.data.catalog.metadata.preview.ArtistCreditEvidence(
                position = it.position,
                musicBrainzArtistId = it.musicBrainzArtistId,
                name = it.name,
                joinPhrase = it.joinPhrase,
            )
        }
        val aliases = evidence.map { it.musicBrainzArtistId }.distinct()
        val claims = aliases.associateWith { mbid ->
            val artistId = entityIdForExternalIdentity("musicbrainz", "artist", mbid)
            com.music.bitchord.data.catalog.metadata.preview.ArtistCreditClaim(
                artistId = artistId,
                artistExists = artistId != null && artistById(artistId) != null,
                trustedMbids = artistId?.let {
                    externalIdentitiesForEntity("musicbrainz", "artist", it)
                        .map(ExternalIdentityEntity::externalId)
                }.orEmpty(),
            )
        }
        return com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewSnapshot(
            recordingId = recordingId,
            recordingExists = recordingById(recordingId) != null,
            trustedRecordingMbids = externalIdentitiesForEntity("musicbrainz", "recording", recordingId)
                .map(ExternalIdentityEntity::externalId),
            cachedRecordingMbid = musicBrainzRecordingEnrichment(recordingId)?.mbid,
            evidenceCredits = evidence,
            currentCanonicalCredits = recordingArtistCreditIds(listOf(recordingId)).map {
                com.music.bitchord.data.catalog.metadata.preview.CanonicalArtistCredit(
                    position = it.position, artistId = it.artistId,
                )
            },
            claimsByArtistMbid = claims,
        )
    }


    /**
     * An entire bounded batch is observed within one Room transaction to prevent
     * hybrid snapshots while provider sync is updating canonical credit claims.
     * No queries by provider-native ID and no writes.
     */
    @Transaction
    suspend fun recordingArtistCreditBatchPreviewSnapshots(
        recordingIds: List<String>,
    ): List<com.music.bitchord.data.catalog.metadata.preview.RecordingArtistCreditPreviewSnapshot> =
        recordingIds.map { recordingArtistCreditPreviewSnapshot(it) }

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
            credit.recordingId AS recordingId,
            credit.artistId AS artistId,
            credit.position AS position
        FROM catalog_recording_artist_credit AS credit
        WHERE credit.recordingId IN (:recordingIds)
        ORDER BY credit.recordingId ASC, credit.position ASC, credit.artistId ASC
        """,
    )
    suspend fun recordingArtistCreditIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingArtistIdRow>

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

    /**
     * Atomically claims an external-identity key without ever transferring an
     * existing claim. Returns -1 when the key is already present.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalIdentityIfAbsent(identity: ExternalIdentityEntity): Long

    /**
     * Atomically claims an identity for namespaces where one canonical entity
     * may own at most one external identity for a provider/entity type pair.
     *
     * This is intentionally opt-in. It must not be used for namespaces such as
     * ISRC where multiple identities for the same Recording are valid.
     */
    @Transaction
    suspend fun claimExternalIdentityExclusivelyForEntity(
        identity: ExternalIdentityEntity,
    ): ExclusiveExternalIdentityClaimResult {
        val existingExternalIds = externalIdentitiesForEntity(
            provider = identity.provider,
            entityType = identity.entityType,
            entityId = identity.entityId,
        ).map(ExternalIdentityEntity::externalId).distinct()

        if (existingExternalIds.isNotEmpty()) {
            return ExclusiveExternalIdentityClaimResult(
                existingExternalIdsForEntity = existingExternalIds,
                candidateOwnerEntityId = entityIdForExternalIdentity(
                    provider = identity.provider,
                    entityType = identity.entityType,
                    externalId = identity.externalId,
                ),
                inserted = false,
            )
        }

        val inserted = insertExternalIdentityIfAbsent(identity) != -1L
        return ExclusiveExternalIdentityClaimResult(
            existingExternalIdsForEntity = emptyList(),
            candidateOwnerEntityId = entityIdForExternalIdentity(
                provider = identity.provider,
                entityType = identity.entityType,
                externalId = identity.externalId,
            ),
            inserted = inserted,
        )
    }

    // MusicBrainz enrichment cache. These tables are source-specific and can be
    // discarded/rebuilt without changing the canonical catalog.

    @Query("SELECT * FROM musicbrainz_artist_enrichment WHERE artistId = :artistId LIMIT 1")
    suspend fun musicBrainzArtistEnrichment(artistId: String): MusicBrainzArtistEnrichmentEntity?

    @Upsert
    suspend fun upsertMusicBrainzArtistEnrichment(enrichment: MusicBrainzArtistEnrichmentEntity)

    @Query("SELECT * FROM musicbrainz_recording_enrichment WHERE recordingId = :recordingId LIMIT 1")
    suspend fun musicBrainzRecordingEnrichment(recordingId: String): MusicBrainzRecordingEnrichmentEntity?

    @Upsert
    suspend fun upsertMusicBrainzRecordingEnrichment(enrichment: MusicBrainzRecordingEnrichmentEntity)

    @Query(
        """
        SELECT * FROM musicbrainz_recording_artist_credit
        WHERE recordingId = :recordingId
        ORDER BY position ASC
        """,
    )
    suspend fun musicBrainzRecordingArtistCredits(
        recordingId: String,
    ): List<MusicBrainzRecordingArtistCreditEntity>

    @Upsert
    suspend fun upsertMusicBrainzRecordingArtistCredits(
        credits: List<MusicBrainzRecordingArtistCreditEntity>,
    )

    @Query("DELETE FROM musicbrainz_recording_artist_credit WHERE recordingId = :recordingId")
    suspend fun deleteMusicBrainzRecordingArtistCredits(recordingId: String)

    @Transaction
    suspend fun persistMusicBrainzRecordingEnrichment(
        enrichment: MusicBrainzRecordingEnrichmentEntity,
        credits: List<MusicBrainzRecordingArtistCreditEntity>?,
        clearExistingCredits: Boolean,
    ) {
        upsertMusicBrainzRecordingEnrichment(enrichment)
        if (clearExistingCredits || credits != null) {
            deleteMusicBrainzRecordingArtistCredits(enrichment.recordingId)
        }
        if (!credits.isNullOrEmpty()) upsertMusicBrainzRecordingArtistCredits(credits)
    }

    @Query("SELECT * FROM musicbrainz_release_enrichment WHERE releaseId = :releaseId LIMIT 1")
    suspend fun musicBrainzReleaseEnrichment(releaseId: String): MusicBrainzReleaseEnrichmentEntity?

    @Upsert
    suspend fun upsertMusicBrainzReleaseEnrichment(enrichment: MusicBrainzReleaseEnrichmentEntity)

    @Query(
        """
        SELECT * FROM musicbrainz_release_artist_credit
        WHERE releaseId = :releaseId
        ORDER BY position ASC
        """,
    )
    suspend fun musicBrainzReleaseArtistCredits(releaseId: String): List<MusicBrainzReleaseArtistCreditEntity>

    @Upsert
    suspend fun upsertMusicBrainzReleaseArtistCredits(
        credits: List<MusicBrainzReleaseArtistCreditEntity>,
    )

    @Query("DELETE FROM musicbrainz_release_artist_credit WHERE releaseId = :releaseId")
    suspend fun deleteMusicBrainzReleaseArtistCredits(releaseId: String)

    @Transaction
    suspend fun persistMusicBrainzReleaseEnrichment(
        enrichment: MusicBrainzReleaseEnrichmentEntity,
        credits: List<MusicBrainzReleaseArtistCreditEntity>?,
        clearExistingCredits: Boolean,
    ) {
        upsertMusicBrainzReleaseEnrichment(enrichment)
        if (clearExistingCredits || credits != null) {
            deleteMusicBrainzReleaseArtistCredits(enrichment.releaseId)
        }
        if (!credits.isNullOrEmpty()) upsertMusicBrainzReleaseArtistCredits(credits)
    }

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
