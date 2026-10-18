package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.MusicBrainzArtistEnrichmentEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingArtistCreditEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingEnrichmentEntity
import com.music.bitchord.data.catalog.MusicBrainzReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity

internal const val MUSICBRAINZ_PROVIDER = "musicbrainz"

internal enum class MetadataLookupFailure {
    UNAVAILABLE,
    MALFORMED_RESPONSE,
    INVALID_EXTERNAL_ID,
}

internal sealed interface MetadataLookupResult<out T> {
    data class Success<T>(val value: T) : MetadataLookupResult<T>
    data class Failure(val reason: MetadataLookupFailure) : MetadataLookupResult<Nothing>
}

internal data class MusicBrainzArtistCredit(
    val musicBrainzArtistId: String,
    val name: String,
    val joinPhrase: String? = null,
)

internal data class ArtistMetadataEnrichment(
    val musicBrainzId: String,
    val name: String? = null,
    val sortName: String? = null,
    val disambiguation: String? = null,
    val country: String? = null,
    val area: String? = null,
)

internal data class RecordingMetadataEnrichment(
    val musicBrainzId: String,
    val title: String? = null,
    val durationMs: Long? = null,
    val artistDisplay: String? = null,
    /** null means the provider response omitted the credit field entirely. */
    val artistCredits: List<MusicBrainzArtistCredit>? = null,
)

internal data class ReleaseMetadataEnrichment(
    val musicBrainzId: String,
    val title: String? = null,
    val artistDisplay: String? = null,
    val releaseDate: String? = null,
    val country: String? = null,
    val status: String? = null,
    /** null means the provider response omitted the credit field entirely. */
    val artistCredits: List<MusicBrainzArtistCredit>? = null,
)

/**
 * Provider-neutral seam for metadata sources that are given an already-trusted
 * external identity. Intentionally no search API exists here.
 */
internal interface CanonicalMetadataEnrichmentProvider {
    val providerKey: String

    suspend fun lookupArtist(externalId: String): MetadataLookupResult<ArtistMetadataEnrichment>
    suspend fun lookupRecording(externalId: String): MetadataLookupResult<RecordingMetadataEnrichment>
    suspend fun lookupRelease(externalId: String): MetadataLookupResult<ReleaseMetadataEnrichment>
}

internal enum class MetadataFieldSource {
    CANONICAL,
    MUSICBRAINZ,
}

internal data class MetadataProvenance(
    val source: MetadataFieldSource,
    val externalId: String? = null,
)

internal data class ResolvedMetadataField<T>(
    val value: T?,
    val provenance: MetadataProvenance?,
)

internal data class ResolvedArtistMetadata(
    val artistId: String,
    val musicBrainzId: String?,
    val name: ResolvedMetadataField<String>,
    val sortName: ResolvedMetadataField<String>,
    val disambiguation: ResolvedMetadataField<String>,
    val country: ResolvedMetadataField<String>,
    val area: ResolvedMetadataField<String>,
    val fetchedAtEpochMs: Long?,
)

internal data class ResolvedRecordingMetadata(
    val recordingId: String,
    val musicBrainzId: String?,
    val title: ResolvedMetadataField<String>,
    val durationMs: ResolvedMetadataField<Long>,
    val artistDisplay: ResolvedMetadataField<String>,
    val musicBrainzArtistCredits: List<MusicBrainzArtistCredit>,
    val fetchedAtEpochMs: Long?,
)

internal data class ResolvedReleaseMetadata(
    val releaseId: String,
    val musicBrainzId: String?,
    val title: ResolvedMetadataField<String>,
    val artistDisplay: ResolvedMetadataField<String>,
    val releaseDate: ResolvedMetadataField<String>,
    val country: ResolvedMetadataField<String>,
    val status: ResolvedMetadataField<String>,
    val musicBrainzArtistCredits: List<MusicBrainzArtistCredit>,
    val fetchedAtEpochMs: Long?,
)

internal sealed interface MetadataRefreshResult {
    data class Refreshed(
        val externalId: String,
        val fetchedAtEpochMs: Long,
    ) : MetadataRefreshResult

    data object NoTrustedIdentity : MetadataRefreshResult
    data class Failed(val reason: MetadataLookupFailure) : MetadataRefreshResult
}

/**
 * Trusted-ID enrichment coordinator. Network is used only by explicit refresh;
 * resolve* reads are Room-only and therefore do not consume provider quota.
 */
internal class CanonicalMetadataEnrichmentRepository(
    private val dao: CanonicalCatalogDao,
    private val provider: CanonicalMetadataEnrichmentProvider,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(provider.providerKey == MUSICBRAINZ_PROVIDER) {
            "This v0 cache is source-specific to MusicBrainz"
        }
    }

    suspend fun refreshArtist(artistId: String): MetadataRefreshResult {
        val mbid = trustedExternalId("artist", artistId) ?: return MetadataRefreshResult.NoTrustedIdentity
        return when (val lookup = provider.lookupArtist(mbid)) {
            is MetadataLookupResult.Failure -> MetadataRefreshResult.Failed(lookup.reason)
            is MetadataLookupResult.Success -> {
                if (!lookup.value.musicBrainzId.equals(mbid, ignoreCase = true)) {
                    return MetadataRefreshResult.Failed(MetadataLookupFailure.MALFORMED_RESPONSE)
                }
                val fetchedAt = nowEpochMs()
                val old = dao.musicBrainzArtistEnrichment(artistId)?.takeIf {
                    it.mbid.equals(mbid, ignoreCase = true)
                }
                val incoming = lookup.value
                dao.upsertMusicBrainzArtistEnrichment(
                    MusicBrainzArtistEnrichmentEntity(
                        artistId = artistId,
                        mbid = mbid,
                        name = incoming.name ?: old?.name,
                        sortName = incoming.sortName ?: old?.sortName,
                        disambiguation = incoming.disambiguation ?: old?.disambiguation,
                        country = incoming.country ?: old?.country,
                        area = incoming.area ?: old?.area,
                        fetchedAtEpochMs = fetchedAt,
                    ),
                )
                MetadataRefreshResult.Refreshed(mbid, fetchedAt)
            }
        }
    }

    suspend fun refreshRecording(recordingId: String): MetadataRefreshResult {
        val mbid = trustedExternalId("recording", recordingId) ?: return MetadataRefreshResult.NoTrustedIdentity
        return when (val lookup = provider.lookupRecording(mbid)) {
            is MetadataLookupResult.Failure -> MetadataRefreshResult.Failed(lookup.reason)
            is MetadataLookupResult.Success -> {
                if (!lookup.value.musicBrainzId.equals(mbid, ignoreCase = true)) {
                    return MetadataRefreshResult.Failed(MetadataLookupFailure.MALFORMED_RESPONSE)
                }
                val fetchedAt = nowEpochMs()
                val previousRaw = dao.musicBrainzRecordingEnrichment(recordingId)
                val previous = previousRaw?.takeIf { it.mbid.equals(mbid, ignoreCase = true) }
                val incoming = lookup.value
                val credits = incoming.artistCredits?.mapIndexed { position, credit ->
                    MusicBrainzRecordingArtistCreditEntity(
                        recordingId = recordingId,
                        position = position,
                        musicBrainzArtistId = credit.musicBrainzArtistId,
                        name = credit.name,
                        joinPhrase = credit.joinPhrase,
                    )
                }
                dao.persistMusicBrainzRecordingEnrichment(
                    enrichment = MusicBrainzRecordingEnrichmentEntity(
                        recordingId = recordingId,
                        mbid = mbid,
                        title = incoming.title ?: previous?.title,
                        durationMs = incoming.durationMs ?: previous?.durationMs,
                        artistDisplay = incoming.artistDisplay ?: previous?.artistDisplay,
                        fetchedAtEpochMs = fetchedAt,
                    ),
                    credits = credits,
                    clearExistingCredits = previousRaw != null && previous == null,
                )
                MetadataRefreshResult.Refreshed(mbid, fetchedAt)
            }
        }
    }

    suspend fun refreshRelease(releaseId: String): MetadataRefreshResult {
        val mbid = trustedExternalId("release", releaseId) ?: return MetadataRefreshResult.NoTrustedIdentity
        return when (val lookup = provider.lookupRelease(mbid)) {
            is MetadataLookupResult.Failure -> MetadataRefreshResult.Failed(lookup.reason)
            is MetadataLookupResult.Success -> {
                if (!lookup.value.musicBrainzId.equals(mbid, ignoreCase = true)) {
                    return MetadataRefreshResult.Failed(MetadataLookupFailure.MALFORMED_RESPONSE)
                }
                val fetchedAt = nowEpochMs()
                val previousRaw = dao.musicBrainzReleaseEnrichment(releaseId)
                val previous = previousRaw?.takeIf { it.mbid.equals(mbid, ignoreCase = true) }
                val incoming = lookup.value
                val credits = incoming.artistCredits?.mapIndexed { position, credit ->
                    MusicBrainzReleaseArtistCreditEntity(
                        releaseId = releaseId,
                        position = position,
                        musicBrainzArtistId = credit.musicBrainzArtistId,
                        name = credit.name,
                        joinPhrase = credit.joinPhrase,
                    )
                }
                dao.persistMusicBrainzReleaseEnrichment(
                    enrichment = MusicBrainzReleaseEnrichmentEntity(
                        releaseId = releaseId,
                        mbid = mbid,
                        title = incoming.title ?: previous?.title,
                        artistDisplay = incoming.artistDisplay ?: previous?.artistDisplay,
                        releaseDate = incoming.releaseDate ?: previous?.releaseDate,
                        country = incoming.country ?: previous?.country,
                        status = incoming.status ?: previous?.status,
                        fetchedAtEpochMs = fetchedAt,
                    ),
                    credits = credits,
                    clearExistingCredits = previousRaw != null && previous == null,
                )
                MetadataRefreshResult.Refreshed(mbid, fetchedAt)
            }
        }
    }

    suspend fun resolveArtist(artistId: String): ResolvedArtistMetadata? {
        val canonical = dao.artistById(artistId) ?: return null
        val mbid = trustedExternalId("artist", artistId)
        val cached = mbid?.let { trusted ->
            dao.musicBrainzArtistEnrichment(artistId)?.takeIf { it.mbid.equals(trusted, ignoreCase = true) }
        }
        return ResolvedArtistMetadata(
            artistId = canonical.id,
            musicBrainzId = mbid,
            name = resolveField(cached?.name, canonical.name, mbid),
            sortName = musicBrainzOnly(cached?.sortName, mbid),
            disambiguation = musicBrainzOnly(cached?.disambiguation, mbid),
            country = musicBrainzOnly(cached?.country, mbid),
            area = musicBrainzOnly(cached?.area, mbid),
            fetchedAtEpochMs = cached?.fetchedAtEpochMs,
        )
    }

    suspend fun resolveRecording(recordingId: String): ResolvedRecordingMetadata? {
        val canonical = dao.recordingById(recordingId) ?: return null
        val mbid = trustedExternalId("recording", recordingId)
        val cached = mbid?.let { trusted ->
            dao.musicBrainzRecordingEnrichment(recordingId)?.takeIf { it.mbid.equals(trusted, ignoreCase = true) }
        }
        val credits = if (cached != null) {
            dao.musicBrainzRecordingArtistCredits(recordingId).map { row ->
                MusicBrainzArtistCredit(row.musicBrainzArtistId, row.name, row.joinPhrase)
            }
        } else {
            emptyList()
        }
        return ResolvedRecordingMetadata(
            recordingId = canonical.id,
            musicBrainzId = mbid,
            title = resolveField(cached?.title, canonical.title, mbid),
            durationMs = resolveField(cached?.durationMs, canonical.durationMs, mbid),
            artistDisplay = resolveField(cached?.artistDisplay, canonical.artistDisplay, mbid),
            musicBrainzArtistCredits = credits,
            fetchedAtEpochMs = cached?.fetchedAtEpochMs,
        )
    }

    suspend fun resolveRelease(releaseId: String): ResolvedReleaseMetadata? {
        val canonical = dao.releaseById(releaseId) ?: return null
        val mbid = trustedExternalId("release", releaseId)
        val cached = mbid?.let { trusted ->
            dao.musicBrainzReleaseEnrichment(releaseId)?.takeIf { it.mbid.equals(trusted, ignoreCase = true) }
        }
        val credits = if (cached != null) {
            dao.musicBrainzReleaseArtistCredits(releaseId).map { row ->
                MusicBrainzArtistCredit(row.musicBrainzArtistId, row.name, row.joinPhrase)
            }
        } else {
            emptyList()
        }
        return ResolvedReleaseMetadata(
            releaseId = canonical.id,
            musicBrainzId = mbid,
            title = resolveField(cached?.title, canonical.title, mbid),
            artistDisplay = resolveField(cached?.artistDisplay, canonical.artistDisplay, mbid),
            releaseDate = musicBrainzOnly(cached?.releaseDate, mbid),
            country = musicBrainzOnly(cached?.country, mbid),
            status = musicBrainzOnly(cached?.status, mbid),
            musicBrainzArtistCredits = credits,
            fetchedAtEpochMs = cached?.fetchedAtEpochMs,
        )
    }

    /**
     * Multiple MusicBrainz aliases for one canonical entity are ambiguous. v0
     * refuses to pick one silently; it therefore performs no network lookup.
     */
    private suspend fun trustedExternalId(entityType: String, entityId: String): String? {
        val identities = dao.externalIdentitiesForEntity(
            provider = MUSICBRAINZ_PROVIDER,
            entityType = entityType,
            entityId = entityId,
        )
        return identities.singleOrNull()?.externalId?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun <T> resolveField(
        enriched: T?,
        canonical: T?,
        mbid: String?,
    ): ResolvedMetadataField<T> = when {
        enriched != null -> ResolvedMetadataField(
            value = enriched,
            provenance = MetadataProvenance(MetadataFieldSource.MUSICBRAINZ, mbid),
        )
        canonical != null -> ResolvedMetadataField(
            value = canonical,
            provenance = MetadataProvenance(MetadataFieldSource.CANONICAL),
        )
        else -> ResolvedMetadataField(null, null)
    }

    private fun <T> musicBrainzOnly(value: T?, mbid: String?): ResolvedMetadataField<T> =
        if (value != null) {
            ResolvedMetadataField(
                value = value,
                provenance = MetadataProvenance(MetadataFieldSource.MUSICBRAINZ, mbid),
            )
        } else {
            ResolvedMetadataField(null, null)
        }
}
