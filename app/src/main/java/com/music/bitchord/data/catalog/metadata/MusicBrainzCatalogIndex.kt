package com.music.bitchord.data.catalog.metadata

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
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

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
