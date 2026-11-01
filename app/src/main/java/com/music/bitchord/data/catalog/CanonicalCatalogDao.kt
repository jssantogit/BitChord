package com.music.bitchord.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

internal data class CanonicalRatingAssetRow(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val uri: String?,
    val path: String?,
)

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

/** Cached ReleaseGroup editions materialized by one relational snapshot query. */
internal data class CanonicalCachedGroupEditionRow(
    val musicBrainzId: String,
    val title: String,
    val country: String?,
    val releaseDate: String?,
    val artistDisplay: String?,
    val status: String?,
)

/** Exact Recording MBID to an already-verified Official ReleaseGroup cover. */
internal data class CanonicalRecordingCoverGroupRow(
    val recordingMbid: String,
    val groupMbid: String,
)

/** Only strong MusicBrainz Recording links qualify; names never qualify. */
internal data class CanonicalMusicBrainzLocalAssetClaim(
    val musicBrainzId: String,
    val uri: String,
    val provider: String,
)

internal data class CanonicalRecordingAlbumArtistRow(
    val recordingId: String,
    val releaseId: String,
    /** Null signals a linked Release whose verified artist credit is absent. */
    val artistName: String?,
)

internal data class CanonicalRecordingReleaseDateRow(
    val recordingId: String,
    val releaseId: String,
    /** MusicBrainz partial ISO date only; NULL is unknown, never year zero. */
    val releaseDate: String?,
)

/** Verified MusicBrainz release evidence, keyed by the actual canonical ReleaseId. */
internal data class CanonicalTrustedReleaseDateRow(
    val releaseId: String,
    val mbid: String?,
    val releaseDate: String?,
)

internal data class CanonicalReleaseArtistCreditNameRow(
    val releaseId: String,
    /** Null/blank = incomplete canonical credit, not a negative match. */
    val artistName: String?,
)

internal data class CanonicalRecordingLocalAddedRow(
    val recordingId: String,
    val dateAddedSeconds: Long?,
)

internal data class CanonicalRecordingReleaseArtworkRow(
    val recordingId: String,
    val releaseId: String,
    /** A stored URL only; not proof that the cover is verified or reachable. */
    val artworkUri: String?,
)

internal data class CanonicalRecordingTrackArtistRow(
    val recordingId: String,
    val artistName: String?,
)

internal data class CanonicalRecordingTrackPositionRow(
    val recordingId: String,
    val releaseId: String,
    /** NULL or nonpositive positions are unknown, not track zero. */
    val trackNumber: Int?,
)

internal data class CanonicalRecordingReleaseTitleRow(
    val recordingId: String,
    val releaseId: String,
    val releaseTitle: String,
)

/** Asset-scoped optional tags; missing row / NULL is unknown, "[]" is verified empty. */
internal data class CanonicalScopedTagRow(
    val recordingId: String,
    val mediaAssetId: String,
    val genresJson: String?,
    val moodsJson: String?,
    val bpmsJson: String?,
    val stylesJson: String?,
)

internal data class CanonicalLocalTagScanRow(
    val mediaAssetId: String,
    val uri: String,
    val sourceFingerprint: String?,
    val parsedFingerprint: String?,
)

@Dao
internal interface CanonicalCatalogDao {

    @Upsert
    suspend fun upsertMediaAssetTags(tags: MediaAssetTagsEntity)

    @Query("SELECT * FROM catalog_media_asset_tags WHERE mediaAssetId = :mediaAssetId LIMIT 1")
    suspend fun mediaAssetTagsById(mediaAssetId: String): MediaAssetTagsEntity?

    @Query(
        """
        SELECT asset.id AS mediaAssetId, asset.uri AS uri,
               tags.localSourceFingerprint AS sourceFingerprint,
               tags.localParsedFingerprint AS parsedFingerprint
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_media_asset_tags AS tags ON tags.mediaAssetId = asset.id
        WHERE asset.provider = :providerKey AND tags.localSourceFingerprint IS NOT NULL
        ORDER BY asset.id
        """,
    )
    suspend fun pendingLocalEmbeddedTags(providerKey: String): List<CanonicalLocalTagScanRow>

    @Query(
        """
        SELECT asset.recordingId AS recordingId,
               asset.id AS mediaAssetId,
               tags.genresJson AS genresJson,
               tags.moodsJson AS moodsJson,
               tags.bpmsJson AS bpmsJson,
               tags.stylesJson AS stylesJson
        FROM catalog_media_asset AS asset
        LEFT JOIN catalog_media_asset_tags AS tags ON tags.mediaAssetId = asset.id
        WHERE asset.provider = :providerKey AND asset.recordingId IN (:recordingIds)
        ORDER BY asset.recordingId ASC, asset.id ASC
        """,
    )
    suspend fun providerScopedTagRows(
        providerKey: String,
        recordingIds: List<String>,
    ): List<CanonicalScopedTagRow>

    /** Never used by the first-party Listening preference store. */
    @Upsert
    suspend fun upsertRemoteRatingEvidence(rows: List<RemoteRatingEvidenceEntity>)

    @Upsert
    suspend fun upsertRemoteRatingCoverage(coverage: RemoteRatingCoverageEntity)

    @Query("DELETE FROM catalog_remote_rating_evidence WHERE providerKey = :providerKey")
    suspend fun clearRemoteRatingEvidence(providerKey: String)

    @Query("SELECT * FROM catalog_remote_rating_coverage WHERE providerKey = :providerKey AND scopeId = :scopeId LIMIT 1")
    suspend fun remoteRatingCoverage(providerKey: String, scopeId: String): RemoteRatingCoverageEntity?

    /** Room invalidation is a cache revision signal, not a per-song query. */
    @Query("SELECT * FROM catalog_remote_rating_coverage")
    fun observeRemoteRatingCoverage(): Flow<List<RemoteRatingCoverageEntity>>

    /**
     * One reactive canonical batch per active provider. Room invalidates this
     * Flow on local import/delete and provider sync, even without any rating
     * edit or remote coverage update. Never surfaces native keys or credentials.
     */
    @Query(
        """
        SELECT recording.id AS recordingId,
               asset.id AS mediaAssetId,
               asset.provider AS providerKey,
               asset.uri AS uri,
               asset.path AS path
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording ON recording.id = asset.recordingId
        WHERE asset.provider = :localProvider OR asset.provider = :remoteProvider
        """,
    )
    fun observeRatingPresentationAssets(
        localProvider: String,
        remoteProvider: String,
    ): Flow<List<CanonicalRatingAssetRow>>

    /**
     * Join with the current canonical MediaAsset and Recording; provider-only IDs
     * or orphaned/stale asset mappings can never be consumer identities.
     */
    @Query(
        """
        SELECT rating.* FROM catalog_remote_rating_evidence AS rating
        INNER JOIN catalog_media_asset AS asset
          ON asset.id = rating.mediaAssetId
         AND asset.provider = rating.providerKey
         AND asset.recordingId = rating.recordingId
        INNER JOIN catalog_recording AS recording ON recording.id = rating.recordingId
        INNER JOIN catalog_external_identity AS identity
          ON identity.provider = asset.provider
         AND identity.entityType = 'recording'
         AND identity.externalId = asset.sourceKey
         AND identity.entityId = rating.recordingId
        WHERE rating.providerKey = :providerKey AND rating.scopeId = :scopeId
        ORDER BY rating.recordingId, rating.mediaAssetId
        """,
    )
    suspend fun reconciledRemoteRatings(providerKey: String, scopeId: String): List<RemoteRatingEvidenceEntity>


    // Canonical MusicBrainz release graph (v6). Provider MBIDs are claimed
    // through catalog_external_identity, never used as core Recording IDs.
    @Upsert suspend fun upsertCatalogReleaseTrackSource(entity: CatalogReleaseTrackSourceEntity)

    @Query("DELETE FROM catalog_release_track_source WHERE recordingId = :recordingId AND provider = :provider")
    suspend fun deleteReleaseTrackSourcesForRecording(recordingId: String, provider: String)

    @Query("DELETE FROM catalog_release_track_source WHERE provider = :provider")
    suspend fun deleteReleaseTrackSourcesForProvider(provider: String)

    @Query("SELECT * FROM catalog_release_track_source WHERE releaseId = :releaseId AND recordingId = :recordingId ORDER BY provider")
    suspend fun catalogReleaseTrackSources(releaseId: String, recordingId: String): List<CatalogReleaseTrackSourceEntity>

    @Query("SELECT * FROM catalog_release_track WHERE recordingId = :recordingId ORDER BY releaseId")
    suspend fun catalogReleaseTracksForRecording(recordingId: String): List<ReleaseTrackEntity>

    @Upsert suspend fun upsertMusicBrainzArtistGroup(entity: CatalogMusicBrainzArtistGroupEntity)

    @Query("SELECT COUNT(*) FROM catalog_musicbrainz_artist_group WHERE artistId = :artistId")
    suspend fun musicBrainzArtistGroupMembershipCount(artistId: String): Int

    @Query("""
        SELECT groupEntity.*
        FROM catalog_musicbrainz_artist_group AS association
        JOIN catalog_release_group AS groupEntity ON groupEntity.id = association.releaseGroupId
        WHERE association.artistId = :artistId
          AND association.publicationEvidence = 'website-default-v1'
        ORDER BY groupEntity.firstReleaseDate DESC, groupEntity.title COLLATE NOCASE, groupEntity.id
    """)
    suspend fun musicBrainzGroupsForArtist(artistId: String): List<CatalogReleaseGroupEntity>

    /**
     * One indexed Room query per artist page, independent of discography size.
     * Both group MBID and artist-group association must be positive evidence;
     * legacy/non-front cache namespaces cannot bleed into these cards.
     */
    @Query("""
        SELECT ident.externalId AS musicBrainzId, COALESCE((
                SELECT edition.title
                FROM catalog_release_group_edition AS groupEdition
                JOIN catalog_release AS edition ON edition.id = groupEdition.releaseId
                JOIN musicbrainz_release_enrichment AS evidence
                    ON evidence.releaseId = edition.id
                WHERE groupEdition.releaseGroupId = grp.id
                  AND lower(evidence.status) = 'official'
                  -- A composite ReleaseGroup label is not necessarily the
                  -- commercially released album title. “How Sweet & Bubble
                  -- Gum” has verified Official editions titled “How Sweet”.
                  -- The title remains source-owned; group ID never changes.
                  AND instr(lower(grp.title), lower(trim(edition.title) || ' & ')) = 1
                ORDER BY CASE WHEN evidence.country = 'XW' THEN 0
                              WHEN evidence.country = 'KR' THEN 1 ELSE 2 END,
                         evidence.releaseDate, edition.id
                LIMIT 1
            ), grp.title) AS title,
               grp.primaryType AS primaryType, grp.firstReleaseDate AS firstReleaseDate,
               grp.artistDisplay AS artistDisplay, grp.secondaryTypes AS secondaryTypes,
               NULL AS artworkUri
        FROM catalog_musicbrainz_artist_group AS membership
        JOIN catalog_release_group AS grp ON grp.id = membership.releaseGroupId
        JOIN catalog_external_identity AS ident ON ident.entityId = grp.id
            AND ident.provider = 'musicbrainz' AND ident.entityType = 'release-group'
        WHERE membership.artistId = :artistId
          AND membership.publicationEvidence = 'website-default-v1'
        ORDER BY grp.firstReleaseDate DESC, grp.title COLLATE NOCASE, ident.externalId
    """)
    suspend fun verifiedArtistDiscographyRows(
        artistId: String,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit>

    @Upsert suspend fun upsertCatalogReleaseGroup(entity: CatalogReleaseGroupEntity)
    @Upsert suspend fun upsertCatalogReleaseGroupEdition(entity: CatalogReleaseGroupEditionEntity)
    @Upsert suspend fun upsertCatalogMusicBrainzTrack(entity: CatalogMusicBrainzTrackEntity)
    @Upsert suspend fun upsertMusicBrainzCoverArtCache(entity: MusicBrainzCoverArtCacheEntity)
    @Query("SELECT * FROM catalog_cover_art_cache WHERE entityType = :entityType AND mbid = :mbid LIMIT 1")
    suspend fun musicBrainzCoverArtCache(entityType: String, mbid: String): MusicBrainzCoverArtCacheEntity?

    /** Batch lookup prevents one Room round trip per artist shelf card. */
    /**
     * A recording may reuse a verified cover only through a proven
     * MusicBrainz Recording -> indexed edition-specific MB Track -> Official
     * Release -> ReleaseGroup association. Never join titles, artists, or
     * arbitrary coverReleaseMbid candidates. Prefer non-compilation editions.
     * Return the MBID rather than trusting a raw URL; CoverArtArchive still
     * validates cache age and URL allowlist before the bitmap is used.
     */
    @Query("""
        SELECT groupIdentity.externalId
        FROM catalog_external_identity AS recordingIdentity
        JOIN catalog_musicbrainz_track AS trackEvidence
            ON trackEvidence.recordingId = recordingIdentity.entityId
        JOIN musicbrainz_release_enrichment AS releaseInfo
            ON releaseInfo.releaseId = trackEvidence.releaseId
                AND lower(releaseInfo.status) = 'official'
        JOIN catalog_release_group_edition AS relation
            ON relation.releaseId = trackEvidence.releaseId
        JOIN catalog_release_group AS groupInfo
            ON groupInfo.id = relation.releaseGroupId
                AND lower(groupInfo.primaryType) IN ('album', 'ep', 'single')
        JOIN catalog_external_identity AS groupIdentity
            ON groupIdentity.entityId = groupInfo.id
                AND groupIdentity.provider = 'musicbrainz'
                AND groupIdentity.entityType = 'release-group'
        JOIN catalog_cover_art_cache AS front
            ON front.mbid = groupIdentity.externalId
                AND front.entityType = 'release-group:front-v5'
                AND front.artworkUri IS NOT NULL
        WHERE recordingIdentity.provider = 'musicbrainz'
          AND recordingIdentity.entityType = 'recording'
          AND recordingIdentity.externalId = :recordingMbid
        ORDER BY CASE WHEN lower(COALESCE(groupInfo.secondaryTypes, ''))
                          LIKE '%compilation%' THEN 1 ELSE 0 END,
                 groupInfo.firstReleaseDate, groupIdentity.externalId
        LIMIT 1
    """)
    suspend fun verifiedRecordingCoverGroupMbid(recordingMbid: String): String?

    /**
     * One indexed join for all visible tracks, not one graph traversal per
     * recording. The same Official Release + Track + Front-v5 provenance as
     * the single-row resolver. Multiple eligible editions are returned in
     * preference order; the UI uses the first valid cache entry per MBID.
     */
    @Query("""
        SELECT recordingIdentity.externalId AS recordingMbid,
               groupIdentity.externalId AS groupMbid
        FROM catalog_external_identity AS recordingIdentity
        JOIN catalog_musicbrainz_track AS trackEvidence
            ON trackEvidence.recordingId = recordingIdentity.entityId
        JOIN musicbrainz_release_enrichment AS releaseInfo
            ON releaseInfo.releaseId = trackEvidence.releaseId
               AND lower(releaseInfo.status) = 'official'
        JOIN catalog_release_group_edition AS relation
            ON relation.releaseId = trackEvidence.releaseId
        JOIN catalog_release_group AS groupInfo
            ON groupInfo.id = relation.releaseGroupId
               AND lower(groupInfo.primaryType) IN ('album', 'ep', 'single')
        JOIN catalog_external_identity AS groupIdentity
            ON groupIdentity.entityId = groupInfo.id
               AND groupIdentity.provider = 'musicbrainz'
               AND groupIdentity.entityType = 'release-group'
        JOIN catalog_cover_art_cache AS front
            ON front.mbid = groupIdentity.externalId
               AND front.entityType = 'release-group:front-v5'
               AND front.artworkUri IS NOT NULL
        WHERE recordingIdentity.provider = 'musicbrainz'
          AND recordingIdentity.entityType = 'recording'
          AND recordingIdentity.externalId IN (:recordingMbids)
        GROUP BY recordingIdentity.externalId, groupIdentity.externalId
        ORDER BY recordingIdentity.externalId,
                 CASE WHEN lower(COALESCE(groupInfo.secondaryTypes, ''))
                           LIKE '%compilation%' THEN 1 ELSE 0 END,
                 groupInfo.firstReleaseDate, groupIdentity.externalId
    """)
    suspend fun verifiedRecordingCoverGroupMbids(
        recordingMbids: List<String>,
    ): List<CanonicalRecordingCoverGroupRow>

    @Query("SELECT * FROM catalog_cover_art_cache WHERE entityType = 'release-group:front-v5' AND mbid IN (:mbids)")
    suspend fun verifiedGroupFronts(mbids: List<String>): List<MusicBrainzCoverArtCacheEntity>

    @Query("SELECT * FROM catalog_musicbrainz_track WHERE trackMbid = :trackMbid LIMIT 1")
    suspend fun catalogMusicBrainzTrack(trackMbid: String): CatalogMusicBrainzTrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReleaseTrackIfAbsent(entity: ReleaseTrackEntity): Long

    @Query("SELECT * FROM catalog_release_group WHERE id = :id LIMIT 1")
    suspend fun catalogReleaseGroup(id: String): CatalogReleaseGroupEntity?

    /**
     * Materialize Official/Promotion/other edition evidence in one statement,
     * rather than one release + identity + enrichment Room read per edition.
     * All real IDs and original status survive; filtering Official remains
     * the caller's responsibility.
     */
    @Query("""
        SELECT ident.externalId AS musicBrainzId,
               edition.title AS title,
               info.country AS country, info.releaseDate AS releaseDate,
               COALESCE(info.artistDisplay, edition.artistDisplay) AS artistDisplay,
               info.status AS status
        FROM catalog_release_group_edition AS relation
        JOIN catalog_release AS edition ON edition.id = relation.releaseId
        JOIN catalog_external_identity AS ident
          ON ident.entityId = edition.id
         AND ident.provider = 'musicbrainz' AND ident.entityType = 'release'
        LEFT JOIN musicbrainz_release_enrichment AS info
          ON info.releaseId = edition.id AND info.mbid = ident.externalId
        WHERE relation.releaseGroupId = :groupId
        ORDER BY relation.releaseId, ident.externalId
    """)
    suspend fun cachedGroupEditionRows(groupId: String): List<CanonicalCachedGroupEditionRow>

    @Query("SELECT * FROM catalog_release_group_edition WHERE releaseGroupId = :groupId ORDER BY releaseId")
    suspend fun catalogGroupEditions(groupId: String): List<CatalogReleaseGroupEditionEntity>

    @Query("SELECT * FROM catalog_musicbrainz_track WHERE releaseId = :releaseId ORDER BY mediumNumber, trackNumber, trackMbid")
    suspend fun catalogMusicBrainzTracks(releaseId: String): List<CatalogMusicBrainzTrackEntity>

    /**
     * Only first-party Recording ↔ Release track links; never guesses an
     * album by title, artist, provider key or a loosely matched string.
     */
    @Query(
        """
        SELECT rt.recordingId AS recordingId,
               rel.id AS releaseId,
               rel.title AS releaseTitle
        FROM catalog_release_track AS rt
        INNER JOIN catalog_release AS rel ON rel.id = rt.releaseId
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId ASC, rel.title COLLATE NOCASE ASC, rel.id ASC
        """,
    )
    suspend fun recordingReleaseTitlesByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingReleaseTitleRow>

    /**
     * Preserve NULL/un-enriched linked releases so a negative year predicate
     * cannot infer that an unknown release definitely fails to match.
     */
    @Query(
        """
        SELECT rt.recordingId AS recordingId,
               rt.releaseId AS releaseId,
               enrichment.releaseDate AS releaseDate
        FROM catalog_release_track AS rt
        LEFT JOIN musicbrainz_release_enrichment AS enrichment
            ON enrichment.releaseId = rt.releaseId
           AND EXISTS (
                SELECT 1 FROM catalog_external_identity AS identity
                WHERE identity.provider = 'musicbrainz'
                  AND identity.entityType = 'release'
                  AND identity.entityId = rt.releaseId
                  AND identity.externalId = enrichment.mbid
           )
           AND (
                SELECT COUNT(*) FROM catalog_external_identity AS identity
                WHERE identity.provider = 'musicbrainz'
                  AND identity.entityType = 'release'
                  AND identity.entityId = rt.releaseId
           ) = 1
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId ASC, rt.releaseId ASC
        """,
    )
    suspend fun recordingReleaseDatesByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingReleaseDateRow>

    /**
     * Only MusicBrainz release dates backed by exactly ONE current trusted
     * external identity are eligible. Corrupt/mismatched and multi-alias rows
     * must project NULL; the filter planner will preserve UNKNOWN through NOT.
     * Caller passes only already-provider-scoped Release IDs, <=400 per query.
     */
    @Query(
        """
        SELECT rel.id AS releaseId,
               verified.mbid AS mbid,
               verified.releaseDate AS releaseDate
        FROM catalog_release AS rel
        LEFT JOIN musicbrainz_release_enrichment AS verified
            ON verified.releaseId = rel.id
           AND EXISTS (
                SELECT 1 FROM catalog_external_identity AS identity
                WHERE identity.provider = 'musicbrainz'
                  AND identity.entityType = 'release'
                  AND identity.entityId = rel.id
                  AND identity.externalId = verified.mbid
           )
           AND (
                SELECT COUNT(*) FROM catalog_external_identity AS identity
                WHERE identity.provider = 'musicbrainz'
                  AND identity.entityType = 'release'
                  AND identity.entityId = rel.id
           ) = 1
        WHERE rel.id IN (:releaseIds)
        ORDER BY rel.id ASC
        """,
    )
    suspend fun trustedReleaseDatesByIds(
        releaseIds: List<String>,
    ): List<CanonicalTrustedReleaseDateRow>

    /** Read canonical ReleaseArtist credits, not display strings or MusicBrainz guesses. */
    @Query(
        """
        SELECT credit.releaseId AS releaseId, artist.name AS artistName
        FROM catalog_release_artist_credit AS credit
        LEFT JOIN catalog_artist AS artist ON artist.id = credit.artistId
        WHERE credit.releaseId IN (:releaseIds)
        ORDER BY credit.releaseId ASC, credit.position ASC
        """,
    )
    suspend fun canonicalReleaseArtistNamesByIds(
        releaseIds: List<String>,
    ): List<CanonicalReleaseArtistCreditNameRow>

    /** Read artwork pointers only from explicitly linked canonical releases. */
    @Query(
        """
        SELECT rt.recordingId AS recordingId,
               rt.releaseId AS releaseId,
               rel.artworkUri AS artworkUri
        FROM catalog_release_track AS rt
        INNER JOIN catalog_release AS rel ON rel.id = rt.releaseId
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId ASC, rt.releaseId ASC
        """,
    )
    suspend fun recordingReleaseArtworkByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingReleaseArtworkRow>

    /** All canonical ReleaseTrack memberships, including missing track numbers. */
    @Query(
        """
        SELECT rt.recordingId AS recordingId,
               rt.releaseId AS releaseId,
               rt.trackNumber AS trackNumber
        FROM catalog_release_track AS rt
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId ASC, rt.releaseId ASC
        """,
    )
    suspend fun recordingTrackPositionsByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingTrackPositionRow>

    /**
     * A Recording can belong to multiple releases, each of which can have
     * several artist credits. LEFT JOIN preserves releases missing credits,
     * so negative comparisons cannot mistake missing metadata for absence.
     */
    @Query(
        """
        SELECT rt.recordingId AS recordingId,
               rt.releaseId AS releaseId,
               artist.name AS artistName
        FROM catalog_release_track AS rt
        LEFT JOIN catalog_release_artist_credit AS credit
            ON credit.releaseId = rt.releaseId
        LEFT JOIN catalog_artist AS artist ON artist.id = credit.artistId
        WHERE rt.recordingId IN (:recordingIds)
        ORDER BY rt.recordingId, rt.releaseId, credit.position
        """,
    )
    suspend fun recordingAlbumArtistCreditsByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingAlbumArtistRow>

    /** Linked track artist credits, not a guessed split of artistDisplay. */
    @Query(
        """
        SELECT credit.recordingId AS recordingId,
               artist.name AS artistName
        FROM catalog_recording_artist_credit AS credit
        LEFT JOIN catalog_artist AS artist ON artist.id = credit.artistId
        WHERE credit.recordingId IN (:recordingIds)
        ORDER BY credit.recordingId ASC, credit.position ASC
        """,
    )
    suspend fun recordingTrackArtistCreditsByIds(
        recordingIds: List<String>,
    ): List<CanonicalRecordingTrackArtistRow>

    /** Only MediaStore local asset added dates; never mix other provider timestamps. */
    @Query(
        """
        SELECT recordingId AS recordingId,
               dateAddedSeconds AS dateAddedSeconds
        FROM catalog_media_asset
        WHERE provider = :localProvider AND recordingId IN (:recordingIds)
        ORDER BY recordingId ASC, id ASC
        """,
    )
    suspend fun localAddedDatesByRecordingIds(
        localProvider: String,
        recordingIds: List<String>,
    ): List<CanonicalRecordingLocalAddedRow>

    /** Bounded, factual local additions: actual MediaStore dates, never fake releases. */
    @Query(
        """
        SELECT recording.id AS recordingId,
               asset.id AS mediaAssetId,
               asset.provider AS providerKey,
               recording.title AS title,
               recording.artistDisplay AS artistDisplay,
               recording.durationMs AS durationMs,
               asset.artworkUri AS artworkUri
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording ON recording.id = asset.recordingId
        WHERE asset.provider = :localProvider
          AND asset.dateAddedSeconds IS NOT NULL
        ORDER BY asset.dateAddedSeconds DESC, asset.id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentLocalAdditions(
        localProvider: String, limit: Int = 12,
    ): List<CanonicalBrowseAssetRow>

    @Query("SELECT * FROM catalog_recording WHERE id IN (:recordingIds)")
    suspend fun recordingsByIds(recordingIds: List<String>): List<RecordingEntity>

    @Query("SELECT * FROM catalog_recording WHERE id = :recordingId LIMIT 1")
    suspend fun recordingById(recordingId: String): RecordingEntity?

    @Query("SELECT * FROM catalog_release WHERE id = :releaseId LIMIT 1")
    suspend fun releaseById(releaseId: String): ReleaseEntity?

    @Query("SELECT * FROM catalog_artist WHERE id = :artistId LIMIT 1")
    suspend fun artistById(artistId: String): ArtistEntity?

    @Query("SELECT * FROM catalog_media_asset WHERE id IN (:mediaAssetIds)")
    suspend fun mediaAssetsByIds(mediaAssetIds: List<String>): List<MediaAssetEntity>

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

    /**
     * A MediaStore rescan owns local-tag associations only. An existing release
     * used by MusicBrainz or a remote provider must not lose its Recording
     * links when local album tags change. Without a per-edge ownership table,
     * favor retaining a possibly stale shared edge over deleting remote data.
     */
    @Query(
        """
        DELETE FROM catalog_release_track
        WHERE recordingId = :recordingId
          AND releaseId IN (
            SELECT identity.entityId
            FROM catalog_external_identity AS identity
            WHERE identity.provider = :localTagsProvider
              AND identity.entityType = 'release'
          )
          AND NOT EXISTS (
            SELECT 1 FROM catalog_release_track_source AS edgeOwner
            WHERE edgeOwner.releaseId = catalog_release_track.releaseId
              AND edgeOwner.recordingId = catalog_release_track.recordingId
              AND edgeOwner.provider != :localTagsProvider
          )
          AND NOT EXISTS (
            SELECT 1
            FROM catalog_external_identity AS other
            WHERE other.entityId = catalog_release_track.releaseId
              AND other.entityType = 'release'
              AND other.provider != :localTagsProvider
          )
        """,
    )
    suspend fun deleteLocalOnlyReleaseTracksForRecording(
        recordingId: String,
        localTagsProvider: String,
    )


    @Query(
        """
        SELECT identity.externalId AS musicBrainzId,
            COALESCE(NULLIF(info.name, ''), artist.name) AS name,
            info.disambiguation AS disambiguation, info.country AS country
        FROM catalog_external_identity identity
        JOIN catalog_artist artist ON artist.id = identity.entityId
        LEFT JOIN musicbrainz_artist_enrichment info
            ON info.artistId = artist.id AND info.mbid = identity.externalId
        WHERE identity.provider = 'musicbrainz' AND identity.entityType = 'artist'
          AND (
            instr(lower(artist.name), lower(:query)) > 0
            OR instr(lower(info.name), lower(:query)) > 0
          )
        ORDER BY COALESCE(NULLIF(info.name, ''), artist.name) COLLATE NOCASE,
                 identity.externalId
        LIMIT :limit
        """,
    )
    suspend fun searchKnownMusicBrainzArtists(
        query: String, limit: Int,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit>

    @Query(
        """
        SELECT identity.externalId AS musicBrainzId, COALESCE((
                SELECT edition.title
                FROM catalog_release_group_edition AS groupEdition
                JOIN catalog_release AS edition ON edition.id = groupEdition.releaseId
                JOIN musicbrainz_release_enrichment AS evidence
                    ON evidence.releaseId = edition.id
                WHERE groupEdition.releaseGroupId = grp.id
                  AND lower(evidence.status) = 'official'
                  -- A composite ReleaseGroup label is not necessarily the
                  -- commercially released album title. “How Sweet & Bubble
                  -- Gum” has verified Official editions titled “How Sweet”.
                  -- The title remains source-owned; group ID never changes.
                  AND instr(lower(grp.title), lower(trim(edition.title) || ' & ')) = 1
                ORDER BY CASE WHEN evidence.country = 'XW' THEN 0
                              WHEN evidence.country = 'KR' THEN 1 ELSE 2 END,
                         evidence.releaseDate, edition.id
                LIMIT 1
            ), grp.title) AS title,
               grp.primaryType AS primaryType, grp.firstReleaseDate AS firstReleaseDate,
               grp.artistDisplay AS artistDisplay, grp.secondaryTypes AS secondaryTypes,
               NULL AS artworkUri
        FROM catalog_external_identity identity
        JOIN catalog_release_group grp ON grp.id = identity.entityId
        WHERE identity.provider = 'musicbrainz' AND identity.entityType = 'release-group'
          AND (instr(lower(grp.title), lower(:query)) > 0
            OR instr(lower(grp.artistDisplay), lower(:query)) > 0)
        ORDER BY grp.title COLLATE NOCASE, identity.externalId
        LIMIT :limit
        """,
    )
    suspend fun searchKnownMusicBrainzGroups(
        query: String, limit: Int,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit>

    @Query("""
        SELECT identity.externalId AS musicBrainzId, edition.title AS title,
            edition.artistDisplay AS artistDisplay, info.country AS country,
            info.releaseDate AS releaseDate, info.status AS status,
            COALESCE(art.thumbnailUri, art.artworkUri) AS artworkUri
        FROM catalog_external_identity identity
        JOIN catalog_release edition ON edition.id = identity.entityId
        LEFT JOIN musicbrainz_release_enrichment info
            ON info.releaseId = edition.id AND info.mbid = identity.externalId
        LEFT JOIN catalog_cover_art_cache art
            ON art.entityType = 'release:front-v3' AND art.mbid = identity.externalId
        WHERE identity.provider = 'musicbrainz' AND identity.entityType = 'release'
            AND (instr(lower(edition.title), lower(:query)) > 0
                OR instr(lower(edition.artistDisplay), lower(:query)) > 0)
        ORDER BY edition.title COLLATE NOCASE, identity.externalId
        LIMIT :limit
    """)
    suspend fun searchKnownMusicBrainzReleases(
        query: String, limit: Int,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit>

    @Query("""
        SELECT identity.externalId FROM catalog_release_group_edition edition
        JOIN catalog_external_identity identity ON identity.entityId = edition.releaseGroupId
        WHERE edition.releaseId = :releaseId AND identity.provider = 'musicbrainz'
            AND identity.entityType = 'release-group'
        ORDER BY identity.externalId
    """)
    suspend fun musicBrainzGroupMbidsForRelease(releaseId: String): List<String>

    @Query("""
        SELECT track.trackMbid AS trackMbid, identity.externalId AS recordingMbid,
            track.title AS title, track.mediumNumber AS mediumNumber,
            track.trackNumber AS trackNumber, track.durationMs AS durationMs,
            track.artistDisplay AS artistDisplay,
            track.recordingArtistDisplay AS recordingArtistDisplay,
            COALESCE(recordingEvidence.title, recording.title) AS recordingTitle,
            COALESCE(recordingEvidence.durationMs, recording.durationMs) AS recordingDurationMs
        FROM catalog_musicbrainz_track track
        JOIN catalog_recording recording ON recording.id = track.recordingId
        JOIN catalog_external_identity identity ON identity.entityId = recording.id
        LEFT JOIN musicbrainz_recording_enrichment recordingEvidence
          ON recordingEvidence.recordingId = recording.id
         AND recordingEvidence.mbid = identity.externalId
        WHERE track.releaseId = :releaseId AND identity.provider = 'musicbrainz'
            AND identity.entityType = 'recording'
        ORDER BY track.mediumNumber, track.trackNumber, track.trackMbid
    """)
    suspend fun cachedMusicBrainzTracks(
        releaseId: String,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack>

    /** Persisted MusicBrainz recordings are queryable even without any asset. */
    @Query(
        """
        SELECT identity.externalId AS musicBrainzId,
               recording.title AS title,
               recording.artistDisplay AS artistDisplay,
               recording.durationMs AS durationMs,
               info.hasOfficialAudioRelease AS hasOfficialAudioRelease,
               info.isVideo AS isVideo
        FROM catalog_external_identity AS identity
        INNER JOIN catalog_recording AS recording ON recording.id = identity.entityId
        LEFT JOIN musicbrainz_recording_enrichment info
            ON info.recordingId = recording.id AND info.mbid = identity.externalId
        WHERE identity.provider = 'musicbrainz'
          AND identity.entityType = 'recording'
          AND (
            instr(lower(recording.title), lower(:query)) > 0
            OR instr(lower(recording.artistDisplay), lower(:query)) > 0
          )
        ORDER BY CASE WHEN EXISTS (
                    SELECT 1 FROM catalog_media_asset playable
                    WHERE playable.recordingId = recording.id
                ) THEN 0 ELSE 1 END,
                 recording.artistDisplay COLLATE NOCASE,
                 recording.title COLLATE NOCASE, identity.externalId
        LIMIT :limit
        """,
    )
    suspend fun searchKnownMusicBrainzRecordings(
        query: String, limit: Int,
    ): List<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>

    /** Catalogued renditions are evidence, not a guarantee of account access. */
    @Query(
        """
        SELECT DISTINCT identity.externalId
        FROM catalog_external_identity AS identity
        INNER JOIN catalog_media_asset AS asset ON asset.recordingId = identity.entityId
        WHERE identity.provider = 'musicbrainz'
          AND identity.entityType = 'recording'
          AND identity.externalId IN (:mbids)
        """,
    )
    suspend fun musicBrainzRecordingMbidsWithAssets(mbids: List<String>): List<String>

    /**
     * Read-only evidence for a downloaded icon. Identity must be the *same*
     * canonical Recording; MediaStore ownership is not inferred by title.
     * Consumers must additionally verify that the URI is still readable.
     */
    @Query("""
        SELECT DISTINCT identity.externalId AS musicBrainzId,
               asset.uri AS uri,
               asset.provider AS provider
        FROM catalog_external_identity AS identity
        INNER JOIN catalog_media_asset AS asset
            ON asset.recordingId = identity.entityId
        WHERE identity.provider = 'musicbrainz'
          AND identity.entityType = 'recording'
          AND identity.externalId IN (:mbids)
          AND asset.provider = 'local-mediastore'
          AND asset.uri LIKE 'content://%'
    """)
    suspend fun musicBrainzRecordingLocalAssetClaims(
        mbids: List<String>,
    ): List<CanonicalMusicBrainzLocalAssetClaim>

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
     * Availability needs canonical IDs only, not sourceKey, URI, artwork or
     * raw asset data. DISTINCT collapses multiple versions of one Recording.
     */
    @Query(
        """
        SELECT DISTINCT recordingId
        FROM catalog_media_asset
        WHERE provider = :provider
        ORDER BY recordingId ASC
        """,
    )
    suspend fun availableRecordingIdsForProvider(provider: String): List<String>

    /**
     * Bounded normal-playlist picker. Identity comes from the Recording table,
     * eligibility from an actual asset in one of the CURRENT configured sources.
     * Search never falls back to provider-native/video IDs or another account.
     */
    @Query(
        """
        SELECT DISTINCT recording.*
        FROM catalog_recording AS recording
        INNER JOIN catalog_media_asset AS asset
            ON asset.recordingId = recording.id
        WHERE asset.provider IN (:providerKeys)
          AND (:term = '' OR
               recording.title LIKE '%' || :term || '%' COLLATE NOCASE OR
               recording.artistDisplay LIKE '%' || :term || '%' COLLATE NOCASE)
        ORDER BY recording.artistDisplay COLLATE NOCASE,
                 recording.title COLLATE NOCASE, recording.id
        LIMIT 200
        """,
    )
    suspend fun searchAvailableRecordingsForPlaylist(
        providerKeys: List<String>,
        term: String,
    ): List<RecordingEntity>

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

    /**
     * On-device server search covers every synchronized OpenSubsonic account
     * (Navidrome, compatible services) and any future Jellyfin provider.
     * Unlike provider-specific playback APIs, the projection never exposes
     * URLs, tokens, provider-native IDs, or a synthetic videoId.
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
        INNER JOIN catalog_recording AS recording ON recording.id = asset.recordingId
        WHERE (asset.provider LIKE 'opensubsonic:%' OR asset.provider LIKE 'jellyfin:%')
          AND (instr(lower(recording.title), lower(:query)) > 0
               OR instr(lower(recording.artistDisplay), lower(:query)) > 0)
        ORDER BY recording.artistDisplay COLLATE NOCASE,
                 recording.title COLLATE NOCASE,
                 recording.id, asset.provider, asset.id
        LIMIT :limit
        """,
    )
    suspend fun searchCanonicalRemoteServerAssets(
        query: String,
        limit: Int,
    ): List<CanonicalBrowseAssetRow>

    /**
     * Scoped Release membership is proven through Recording→ReleaseTrack and
     * a currently persisted asset of this EXACT provider/account.
     * Identical titles on another Release or account never create membership.
     */
    @Query(
        """
        SELECT DISTINCT rel.*
        FROM catalog_release AS rel
        INNER JOIN catalog_release_track AS rt ON rt.releaseId = rel.id
        INNER JOIN catalog_media_asset AS asset ON asset.recordingId = rt.recordingId
        WHERE asset.provider = :providerKey
        ORDER BY rel.title COLLATE NOCASE ASC, rel.id ASC
        """,
    )
    suspend fun providerScopedReleases(providerKey: String): List<ReleaseEntity>

    /**
     * Scoped Artists are proven by canonical recording or release credits
     * connected to a current provider asset. Never group artists by display
     * name or infer a Recording's own canonical artist from provider title text.
     */
    @Query(
        """
        SELECT artist.*
        FROM catalog_artist AS artist
        WHERE EXISTS (
            SELECT 1
            FROM catalog_recording_artist_credit AS credit
            INNER JOIN catalog_media_asset AS asset
                ON asset.recordingId = credit.recordingId
            WHERE credit.artistId = artist.id AND asset.provider = :providerKey
        ) OR EXISTS (
            SELECT 1
            FROM catalog_release_artist_credit AS credit
            INNER JOIN catalog_release_track AS rt ON rt.releaseId = credit.releaseId
            INNER JOIN catalog_media_asset AS asset ON asset.recordingId = rt.recordingId
            WHERE credit.artistId = artist.id AND asset.provider = :providerKey
        )
        ORDER BY artist.name COLLATE NOCASE ASC, artist.id ASC
        """,
    )
    suspend fun providerScopedArtists(providerKey: String): List<ArtistEntity>

    /** Bounded canonical group search for playlist construction. */
    @Query(
        """
        SELECT DISTINCT rel.*
        FROM catalog_release AS rel
        INNER JOIN catalog_release_track AS rt ON rt.releaseId = rel.id
        INNER JOIN catalog_media_asset AS asset ON asset.recordingId = rt.recordingId
        WHERE asset.provider = :providerKey
          AND instr(lower(rel.title), lower(:searchTerm)) > 0
        ORDER BY rel.title COLLATE NOCASE, rel.id
        LIMIT 100
        """,
    )
    suspend fun searchProviderScopedReleases(
        providerKey: String, searchTerm: String,
    ): List<ReleaseEntity>

    @Query(
        """
        SELECT artist.*
        FROM catalog_artist AS artist
        WHERE instr(lower(artist.name), lower(:searchTerm)) > 0
          AND (
              EXISTS (
                  SELECT 1
                  FROM catalog_recording_artist_credit AS credit
                  INNER JOIN catalog_media_asset AS asset
                      ON asset.recordingId = credit.recordingId
                  WHERE credit.artistId = artist.id AND asset.provider = :providerKey
              ) OR EXISTS (
                  SELECT 1
                  FROM catalog_release_artist_credit AS credit
                  INNER JOIN catalog_release_track AS rt ON rt.releaseId = credit.releaseId
                  INNER JOIN catalog_media_asset AS asset ON asset.recordingId = rt.recordingId
                  WHERE credit.artistId = artist.id AND asset.provider = :providerKey
              )
          )
        ORDER BY artist.name COLLATE NOCASE, artist.id
        LIMIT 100
        """,
    )
    suspend fun searchProviderScopedArtists(
        providerKey: String, searchTerm: String,
    ): List<ArtistEntity>


    /** Navigating a Release rechecks its Recording IDs against the active source. */
    @Query(
        """
        SELECT DISTINCT rt.recordingId
        FROM catalog_release_track AS rt
        INNER JOIN catalog_media_asset AS asset ON asset.recordingId = rt.recordingId
        WHERE rt.releaseId = :releaseId AND asset.provider = :providerKey
        ORDER BY rt.recordingId ASC
        """,
    )
    suspend fun providerScopedReleaseRecordingIds(
        providerKey: String,
        releaseId: String,
    ): List<String>

    /**
     * An Artist's tracks are only those canonically credited either on a
     * Recording or a Release reached through a current account MediaAsset.
     * Display names, matching titles and the other provider's assets do not
     * grant artist membership.
     */
    @Query(
        """
        SELECT DISTINCT candidate.recordingId
        FROM (
            SELECT credit.recordingId AS recordingId
            FROM catalog_recording_artist_credit AS credit
            WHERE credit.artistId = :artistId
            UNION
            SELECT rt.recordingId AS recordingId
            FROM catalog_release_artist_credit AS credit
            INNER JOIN catalog_release_track AS rt ON rt.releaseId = credit.releaseId
            WHERE credit.artistId = :artistId
        ) AS candidate
        INNER JOIN catalog_media_asset AS asset ON asset.recordingId = candidate.recordingId
        WHERE asset.provider = :providerKey
        ORDER BY candidate.recordingId ASC
        """,
    )
    suspend fun providerScopedArtistRecordingIds(
        providerKey: String,
        artistId: String,
    ): List<String>

    /**
     * Multi-source canonical browse: asset ID is unique, recording ID is music
     * identity. Never expose sourceKey/URI or group by metadata/name.
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
        INNER JOIN catalog_recording AS recording ON recording.id = asset.recordingId
        WHERE asset.provider IN (:providerKeys)
        ORDER BY
            recording.artistDisplay COLLATE NOCASE,
            recording.title COLLATE NOCASE,
            recording.id,
            asset.provider,
            asset.id
        """,
    )
    fun observeMultiSourceBrowseAssets(providerKeys: List<String>): Flow<List<CanonicalBrowseAssetRow>>

    /**
     * B9 requires ONLY the already-revalidated RecordingIds. Do not fetch
     * the 50k+ whole-library browse for a bounded 500-item smart queue.
     * Providers must be supplied by the current authenticated Catalog scope.
     */
    @Query(
        """
        SELECT recording.id AS recordingId,
               asset.id AS mediaAssetId,
               asset.provider AS providerKey,
               recording.title AS title,
               recording.artistDisplay AS artistDisplay,
               recording.durationMs AS durationMs,
               asset.artworkUri AS artworkUri
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording ON recording.id = asset.recordingId
        WHERE asset.provider IN (:providerKeys)
          AND recording.id IN (:recordingIds)
        ORDER BY recording.artistDisplay COLLATE NOCASE,
                 recording.title COLLATE NOCASE, recording.id,
                 asset.provider, asset.id
        """,
    )
    suspend fun browseAssetsForRecordingIds(
        providerKeys: List<String>,
        recordingIds: List<String>,
    ): List<CanonicalBrowseAssetRow>


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

    /**
     * Same canonical/local projection as localRows, but restrict decoding,
     * release metadata and artwork joins to the exact selected asset IDs.
     * B9/B10 may resolve hundreds of queue entries without scanning 50k+
     * unrelated local media rows on each action.
     */
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
            NULL AS youtubeVideoId
        FROM catalog_media_asset AS asset
        INNER JOIN catalog_recording AS recording
            ON recording.id = asset.recordingId
        LEFT JOIN catalog_release_track AS releaseTrack
            ON releaseTrack.recordingId = recording.id
        LEFT JOIN catalog_release AS albumRelease
            ON albumRelease.id = releaseTrack.releaseId
        WHERE asset.provider = :localProvider AND asset.id IN (:mediaAssetIds)
        ORDER BY recording.artistDisplay COLLATE NOCASE,
                 recording.title COLLATE NOCASE, asset.id
        """,
    )
    suspend fun localRowsByAssetIds(
        localProvider: String,
        mediaAssetIds: List<String>,
    ): List<CanonicalLocalRow>
}
