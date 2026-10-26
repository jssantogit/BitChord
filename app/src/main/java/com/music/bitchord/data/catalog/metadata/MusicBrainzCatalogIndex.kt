package com.music.bitchord.data.catalog.metadata

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.MusicBrainzArtistEnrichmentEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingEnrichmentEntity
import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity
import com.music.bitchord.data.catalog.CatalogReleaseGroupEntity
import com.music.bitchord.data.catalog.CatalogReleaseGroupEditionEntity
import com.music.bitchord.data.catalog.CatalogMusicBrainzArtistGroupEntity
import com.music.bitchord.data.catalog.CatalogReleaseTrackSourceEntity
import com.music.bitchord.data.catalog.CatalogMusicBrainzTrackEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import java.util.Locale
import java.util.UUID

/**
 * Persist public MusicBrainz Recording identities independently of availability.
 * An indexed Recording has no audio unless a local or authorized remote sync
 * later attaches an actual MediaAsset to this same canonical identity.
 *
 * Search cannot infer local identity from title/artist. Only an already-claimed
 * strong MusicBrainz Recording MBID is reused.
 */
internal class MusicBrainzCatalogIndex(
    private val database: CanonicalCatalogDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun persistRecordings(hits: List<MusicBrainzRecordingHit>): Map<String, String> =
        database.withTransaction {
            val dao = database.catalogDao()
            val indexed = linkedMapOf<String, String>()
            for (hit in hits) {
                val mbid = hit.musicBrainzId.trim().lowercase(Locale.ROOT)
                if (!MBID.matches(mbid) || mbid in indexed) continue
                val title = hit.title.trim().takeIf(String::isNotEmpty) ?: continue
                val existing = dao.entityIdForExternalIdentity("musicbrainz", "recording", mbid)
                if (existing != null) {
                    // A stale alias is an integrity problem; do not transfer it.
                    if (dao.recordingById(existing) != null) indexed[mbid] = existing
                    continue
                }

                val recordingId = idFactory()
                dao.upsertRecording(
                    RecordingEntity(
                        id = recordingId,
                        title = title,
                        artistDisplay = hit.artistDisplay?.trim()?.takeIf(String::isNotEmpty)
                            ?: "Unknown Artist",
                        durationMs = hit.durationMs?.takeIf { it >= 0 },
                    ),
                )
                val claim = dao.claimExternalIdentityExclusivelyForEntity(
                    ExternalIdentityEntity(
                        provider = "musicbrainz",
                        entityType = "recording",
                        externalId = mbid,
                        entityId = recordingId,
                    ),
                )
                // A transaction aborts on a concurrent claim rather than
                // leaving an orphaned Recording or silently stealing an MBID.
                check(claim.inserted && claim.candidateOwnerEntityId == recordingId) {
                    "MusicBrainz Recording identity claim conflict"
                }
                indexed[mbid] = recordingId
            }
            indexed
        }



    /** Persist independent Artist identities without merging namesakes. */
    suspend fun persistArtists(hits: List<MusicBrainzArtistHit>): Map<String, String> =
        database.withTransaction {
            val dao = database.catalogDao()
            val indexed = linkedMapOf<String, String>()
            hits.forEach { hit ->
                val mbid = hit.musicBrainzId.trim().lowercase(Locale.ROOT)
                if (!MBID.matches(mbid) || hit.name.isBlank() || mbid in indexed) return@forEach
                val existing = dao.entityIdForExternalIdentity("musicbrainz", "artist", mbid)
                val artistId = if (existing != null) {
                    if (dao.artistById(existing) == null) return@forEach // Orphaned claim: fail closed.
                    existing
                } else {
                    val created = idFactory()
                    dao.upsertArtist(ArtistEntity(created, hit.name.trim()))
                    claim(dao, "artist", mbid, created)
                    created
                }
                // Name is not a strong identity: preserve disambiguation in the
                // existing source-specific cache, including offline namesakes.
                // A subsequent sparse response never erases richer evidence.
                val previous = dao.musicBrainzArtistEnrichment(artistId)
                dao.upsertMusicBrainzArtistEnrichment(MusicBrainzArtistEnrichmentEntity(
                    artistId = artistId,
                    mbid = mbid,
                    name = previous?.name ?: hit.name.trim(),
                    sortName = previous?.sortName,
                    disambiguation = hit.disambiguation?.trim()?.takeIf(String::isNotEmpty)
                        ?: previous?.disambiguation,
                    country = hit.country?.trim()?.takeIf(String::isNotEmpty)
                        ?: previous?.country,
                    area = previous?.area,
                    fetchedAtEpochMs = System.currentTimeMillis(),
                ))
                indexed[mbid] = artistId
            }
            indexed
        }

    /** ReleaseGroup is conceptual; it is not a particular country edition. */
    suspend fun persistReleaseGroups(hits: List<MusicBrainzReleaseGroupHit>): Map<String, String> =
        database.withTransaction {
            val dao = database.catalogDao()
            val indexed = linkedMapOf<String, String>()
            hits.forEach { hit ->
                val mbid = hit.musicBrainzId.trim().lowercase(Locale.ROOT)
                if (!MBID.matches(mbid) || hit.title.isBlank() || mbid in indexed) return@forEach
                val existing = dao.entityIdForExternalIdentity("musicbrainz", "release-group", mbid)
                if (existing != null) {
                    if (dao.catalogReleaseGroup(existing) != null) indexed[mbid] = existing
                } else {
                    val created = idFactory()
                    dao.upsertCatalogReleaseGroup(CatalogReleaseGroupEntity(
                        created, hit.title.trim(), hit.artistDisplay,
                        hit.primaryType, hit.firstReleaseDate,
                    ))
                    claim(dao, "release-group", mbid, created)
                    indexed[mbid] = created
                }
            }
            indexed
        }

    /**
     * Associate groups with one verified artist MBID. This stores positive
     * evidence only: a partial browse, timeout or empty response never prunes.
     */
    suspend fun persistArtistGroups(artistMbid: String, hits: List<MusicBrainzReleaseGroupHit>) {
        val normalized = artistMbid.trim().lowercase(Locale.ROOT)
        if (!MBID.matches(normalized)) return
        database.withTransaction {
            val dao = database.catalogDao()
            val artistId = dao.entityIdForExternalIdentity("musicbrainz", "artist", normalized)
                ?: return@withTransaction
            check(dao.artistById(artistId) != null) { "Orphaned MusicBrainz artist claim" }
            persistReleaseGroups(hits).values.forEach { groupId ->
                dao.upsertMusicBrainzArtistGroup(CatalogMusicBrainzArtistGroupEntity(artistId, groupId))
            }
        }
    }

    suspend fun cachedArtistGroups(artistMbid: String): List<MusicBrainzReleaseGroupHit> =
        database.withTransaction {
            val normalized = artistMbid.trim().lowercase(Locale.ROOT).takeIf(MBID::matches)
                ?: return@withTransaction emptyList()
            val dao = database.catalogDao()
            val artistId = dao.entityIdForExternalIdentity("musicbrainz", "artist", normalized)
                ?: return@withTransaction emptyList()
            dao.musicBrainzGroupsForArtist(artistId).mapNotNull { group ->
                val mbid = dao.externalIdentitiesForEntity(
                    "musicbrainz", "release-group", group.id,
                ).singleOrNull()?.externalId ?: return@mapNotNull null
                MusicBrainzReleaseGroupHit(mbid, group.title, group.primaryType,
                    group.firstReleaseDate, group.artistDisplay)
            }
        }

    /** Edition search evidence uses the existing source-specific v3 cache. */
    suspend fun persistReleases(hits: List<MusicBrainzReleaseHit>): Map<String, String> =
        database.withTransaction {
            val dao = database.catalogDao()
            val indexed = linkedMapOf<String, String>()
            for (hit in hits) {
                val mbid = hit.musicBrainzId.trim().lowercase(Locale.ROOT)
                if (!MBID.matches(mbid) || hit.title.isBlank() || mbid in indexed) continue
                val existing = dao.entityIdForExternalIdentity("musicbrainz", "release", mbid)
                val releaseId = if (existing != null) {
                    check(dao.releaseById(existing) != null) { "Orphaned release claim" }
                    existing
                } else {
                    val created = idFactory()
                    dao.upsertRelease(ReleaseEntity(created, hit.title.trim(), hit.artistDisplay, null))
                    claim(dao, "release", mbid, created)
                    created
                }
                val previous = dao.musicBrainzReleaseEnrichment(releaseId)
                dao.upsertMusicBrainzReleaseEnrichment(MusicBrainzReleaseEnrichmentEntity(
                    releaseId = releaseId, mbid = mbid,
                    title = hit.title.trim(),
                    artistDisplay = hit.artistDisplay?.takeIf(String::isNotBlank) ?: previous?.artistDisplay,
                    releaseDate = hit.releaseDate?.takeIf(String::isNotBlank) ?: previous?.releaseDate,
                    country = hit.country?.takeIf(String::isNotBlank) ?: previous?.country,
                    status = hit.status?.trim()?.takeIf(String::isNotEmpty)
                        ?: previous?.status,
                    fetchedAtEpochMs = System.currentTimeMillis(),
                ))
                indexed[mbid] = releaseId
            }
            indexed
        }

    /** A browse response owns positive associations, never an exhaustive prune. */
    suspend fun persistGroupEditions(groupMbid: String, hits: List<MusicBrainzReleaseHit>) {
        val normalized = groupMbid.trim().lowercase(Locale.ROOT)
        if (!MBID.matches(normalized)) return
        database.withTransaction {
            val dao = database.catalogDao()
            val groupId = dao.entityIdForExternalIdentity("musicbrainz", "release-group", normalized)
                ?: return@withTransaction
            check(dao.catalogReleaseGroup(groupId) != null) { "Orphaned release-group claim" }
            persistReleases(hits).values.forEach { releaseId ->
                dao.upsertCatalogReleaseGroupEdition(CatalogReleaseGroupEditionEntity(groupId, releaseId))
            }
        }
    }

    suspend fun cachedGroupReleases(groupMbid: String): List<MusicBrainzReleaseHit> =
        database.withTransaction {
            val dao = database.catalogDao()
            val normalized = groupMbid.trim().lowercase(Locale.ROOT).takeIf(MBID::matches)
                ?: return@withTransaction emptyList()
            val groupId = dao.entityIdForExternalIdentity("musicbrainz", "release-group", normalized)
                ?: return@withTransaction emptyList()
            dao.catalogGroupEditions(groupId).mapNotNull { edition ->
                val mbid = dao.externalIdentitiesForEntity("musicbrainz", "release", edition.releaseId)
                    .singleOrNull()?.externalId ?: return@mapNotNull null
                val release = dao.releaseById(edition.releaseId) ?: return@mapNotNull null
                val info = dao.musicBrainzReleaseEnrichment(edition.releaseId)
                MusicBrainzReleaseHit(mbid, release.title, info?.country, info?.releaseDate,
                    info?.artistDisplay ?: release.artistDisplay, status = info?.status)
            }
        }

    /** A release seen in search alone does not prove a downloaded tracklist. */
    suspend fun cachedRelease(mbid: String): MusicBrainzCatalogReleaseDetail? = database.withTransaction {
        val dao = database.catalogDao()
        val normalized = mbid.trim().lowercase(Locale.ROOT).takeIf(MBID::matches)
            ?: return@withTransaction null
        val releaseId = dao.entityIdForExternalIdentity("musicbrainz", "release", normalized)
            ?: return@withTransaction null
        val release = dao.releaseById(releaseId) ?: return@withTransaction null
        val tracks = dao.cachedMusicBrainzTracks(releaseId)
        if (tracks.isEmpty()) return@withTransaction null
        // Reject divergent identities instead of displaying an arbitrary recording.
        if (tracks.map { it.trackMbid }.distinct().size != tracks.size) return@withTransaction null
        val info = dao.musicBrainzReleaseEnrichment(releaseId)
        MusicBrainzCatalogReleaseDetail(normalized,
            dao.musicBrainzGroupMbidsForRelease(releaseId).singleOrNull(), release.title,
            info?.artistDisplay ?: release.artistDisplay, info?.country, info?.releaseDate, tracks)
    }

    /**
     * Canonical ReleaseGroup/Release/Track graph is independent of audio
     * availability and YouTube. MBIDs must never become provider sourceKeys.
     * Edition-specific track MBIDs are not recording MBIDs.
     */
    suspend fun persistReleaseDetail(detail: MusicBrainzCatalogReleaseDetail): String? {
        val releaseMbid = detail.musicBrainzId.trim().lowercase(Locale.ROOT)
        if (!MBID.matches(releaseMbid) || detail.title.isBlank()) return null
        val groupMbid = detail.groupMbid?.trim()?.lowercase(Locale.ROOT)?.takeIf(MBID::matches)
        // Recording identity claims are independently valid if the later release
        // fails; they never attach artificial MediaAssets to the library.
        val recordingIds = persistRecordings(detail.tracks.map { track ->
            MusicBrainzRecordingHit(
                musicBrainzId = track.recordingMbid,
                title = track.recordingTitle?.takeIf(String::isNotBlank) ?: track.title,
                artistDisplay = track.recordingArtistDisplay ?: detail.artistDisplay,
                durationMs = track.recordingDurationMs ?: track.durationMs,
            )
        })
        return database.withTransaction {
            val dao = database.catalogDao()
            val groupId = groupMbid?.let { mbid ->
                val existing = dao.entityIdForExternalIdentity("musicbrainz", "release-group", mbid)
                if (existing != null) {
                    check(dao.catalogReleaseGroup(existing) != null) { "Orphaned release-group claim" }
                    existing
                } else {
                    val created = idFactory()
                    dao.upsertCatalogReleaseGroup(CatalogReleaseGroupEntity(
                        id = created,
                        title = detail.title,
                        artistDisplay = detail.artistDisplay,
                        primaryType = null,
                        firstReleaseDate = detail.releaseDate,
                    ))
                    claim(dao, "release-group", mbid, created)
                    created
                }
            }
            val existingRelease = dao.entityIdForExternalIdentity(
                "musicbrainz", "release", releaseMbid,
            )
            val releaseId = if (existingRelease != null) {
                check(dao.releaseById(existingRelease) != null) { "Orphaned release claim" }
                existingRelease
            } else {
                val created = idFactory()
                dao.upsertRelease(ReleaseEntity(
                    id = created,
                    title = detail.title,
                    artistDisplay = detail.artistDisplay,
                    artworkUri = null,
                ))
                claim(dao, "release", releaseMbid, created)
                created
            }
            persistReleases(listOf(MusicBrainzReleaseHit(releaseMbid, detail.title,
                detail.country, detail.releaseDate, detail.artistDisplay)))
            if (groupId != null) {
                dao.upsertCatalogReleaseGroupEdition(CatalogReleaseGroupEditionEntity(
                    releaseGroupId = groupId, releaseId = releaseId,
                ))
            }
            val uniqueRecordings = mutableSetOf<String>()
            detail.tracks.forEach { track ->
                val trackMbid = track.trackMbid.trim().lowercase(Locale.ROOT)
                val recordingId = recordingIds[track.recordingMbid.lowercase(Locale.ROOT)]
                if (!MBID.matches(trackMbid) || recordingId == null ||
                    track.mediumNumber <= 0 || track.trackNumber <= 0) return@forEach
                // An edition-specific Track title may differ from its nested
                // Recording. Cache source-specific Recording facts without
                // mutating the user's canonical local/Navidrome metadata.
                val recordingMbid = track.recordingMbid.trim().lowercase(Locale.ROOT)
                val previousEvidence = dao.musicBrainzRecordingEnrichment(recordingId)
                check(previousEvidence == null || previousEvidence.mbid.equals(recordingMbid, true)) {
                    "Recording enrichment MBID conflicts with the claimed Recording identity"
                }
                val recordingTitle = track.recordingTitle?.trim()?.takeIf(String::isNotEmpty)
                val recordingCredit = track.recordingArtistDisplay?.trim()?.takeIf(String::isNotEmpty)
                if (recordingTitle != null || track.recordingDurationMs != null ||
                    recordingCredit != null
                ) {
                    dao.upsertMusicBrainzRecordingEnrichment(MusicBrainzRecordingEnrichmentEntity(
                        recordingId = recordingId, mbid = recordingMbid,
                        title = recordingTitle ?: previousEvidence?.title,
                        durationMs = track.recordingDurationMs ?: previousEvidence?.durationMs,
                        artistDisplay = recordingCredit ?: previousEvidence?.artistDisplay,
                        fetchedAtEpochMs = System.currentTimeMillis(),
                    ))
                }
                val existingTrack = dao.catalogMusicBrainzTrack(trackMbid)
                check(existingTrack == null ||
                    (existingTrack.releaseId == releaseId && existingTrack.recordingId == recordingId)) {
                    "MusicBrainz Track MBID conflicts with a different recording or edition"
                }
                dao.upsertCatalogMusicBrainzTrack(CatalogMusicBrainzTrackEntity(
                    trackMbid, releaseId, recordingId, track.mediumNumber,
                    track.trackNumber, track.title, track.durationMs,
                    track.artistDisplay, track.recordingArtistDisplay,
                ))
                // Legacy album browse assumes only one edge per (Release,Recording).
                // A recording may appear twice on different media; its track
                // positions remain intact in the distinct MB Track sidecar.
                if (uniqueRecordings.add(recordingId)) {
                    dao.insertReleaseTrackIfAbsent(ReleaseTrackEntity(
                        releaseId, recordingId, track.trackNumber,
                    ))
                }
                dao.upsertCatalogReleaseTrackSource(CatalogReleaseTrackSourceEntity(
                    releaseId, recordingId, "musicbrainz",
                ))
            }
            releaseId
        }
    }

    private suspend fun claim(
        dao: CanonicalCatalogDao, type: String, mbid: String, localId: String,
    ) {
        val result = dao.claimExternalIdentityExclusivelyForEntity(
            ExternalIdentityEntity("musicbrainz", type, mbid, localId),
        )
        check(result.inserted && result.candidateOwnerEntityId == localId) {
            "MusicBrainz $type claim conflict"
        }
    }

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
