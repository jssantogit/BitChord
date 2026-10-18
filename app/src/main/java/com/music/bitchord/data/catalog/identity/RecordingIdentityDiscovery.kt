package com.music.bitchord.data.catalog.identity

import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.metadata.MUSICBRAINZ_PROVIDER
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class RecordingIdentityDiscoveryFailure {
    UNAVAILABLE,
    MALFORMED_RESPONSE,
    INVALID_IDENTIFIER,
}

internal data class StrongRecordingIdentityCandidate(
    val provider: String,
    val externalId: String,
)

internal sealed interface StrongRecordingIdentityLookupResult {
    data class Success(
        val candidates: List<StrongRecordingIdentityCandidate>,
    ) : StrongRecordingIdentityLookupResult

    data class Failure(
        val reason: RecordingIdentityDiscoveryFailure,
    ) : StrongRecordingIdentityLookupResult
}

/** Strong-identifier discovery is intentionally separate from metadata enrichment/search. */
internal interface RecordingIdentityDiscoveryProvider {
    val providerKey: String

    suspend fun lookupByIsrc(isrc: String): StrongRecordingIdentityLookupResult
}

/** Exact MusicBrainz `/isrc/{ISRC}` lookup. No text-search surface exists here. */
internal class MusicBrainzRecordingIdentityDiscoveryProvider(
    private val client: MusicBrainzClient = MusicBrainzClient(),
) : RecordingIdentityDiscoveryProvider {
    override val providerKey: String = MUSICBRAINZ_PROVIDER

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    override suspend fun lookupByIsrc(isrc: String): StrongRecordingIdentityLookupResult {
        val normalized = IsrcNormalizer.normalize(isrc)
            ?: return StrongRecordingIdentityLookupResult.Failure(
                RecordingIdentityDiscoveryFailure.INVALID_IDENTIFIER,
            )
        val raw = try {
            client.get("isrc/$normalized", mapOf("fmt" to "json"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return StrongRecordingIdentityLookupResult.Failure(
                RecordingIdentityDiscoveryFailure.UNAVAILABLE,
            )
        }
        val response = runCatching { json.decodeFromString<MusicBrainzIsrcResponse>(raw) }.getOrNull()
            ?: return StrongRecordingIdentityLookupResult.Failure(
                RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE,
            )
        response.isrc?.let { responseIsrc ->
            if (IsrcNormalizer.normalize(responseIsrc) != normalized) {
                return StrongRecordingIdentityLookupResult.Failure(
                    RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE,
                )
            }
        }

        val seen = LinkedHashSet<String>()
        for (recording in response.recordings) {
            val mbid = recording.id.validMusicBrainzMbidOrNull()
                ?: return StrongRecordingIdentityLookupResult.Failure(
                    RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE,
                )
            seen += mbid
        }
        return StrongRecordingIdentityLookupResult.Success(
            candidates = seen.map { mbid ->
                StrongRecordingIdentityCandidate(MUSICBRAINZ_PROVIDER, mbid)
            },
        )
    }
}

internal sealed interface RecordingIdentityResolutionResult {
    data class AlreadyResolved(val musicBrainzId: String) : RecordingIdentityResolutionResult
    data class AmbiguousExistingIdentity(
        val musicBrainzIds: List<String>,
    ) : RecordingIdentityResolutionResult

    data object NoStrongIdentifier : RecordingIdentityResolutionResult
    data object Unresolved : RecordingIdentityResolutionResult
    data class Promoted(val musicBrainzId: String) : RecordingIdentityResolutionResult
    data class AmbiguousLookup(
        val isrc: String,
        val musicBrainzIds: List<String>,
    ) : RecordingIdentityResolutionResult

    data class ConflictingCandidates(
        val musicBrainzIds: List<String>,
    ) : RecordingIdentityResolutionResult

    data class IdentityConflict(
        val musicBrainzId: String,
        val claimedByRecordingId: String,
    ) : RecordingIdentityResolutionResult

    data class Failed(
        val reason: RecordingIdentityDiscoveryFailure,
    ) : RecordingIdentityResolutionResult
}

/**
 * Promotes exact strong-ID evidence only after every ISRC lookup in the attempt
 * has completed successfully. It never merges canonical Recordings.
 */
internal class CanonicalRecordingIdentityDiscoveryRepository(
    private val dao: CanonicalCatalogDao,
    private val provider: RecordingIdentityDiscoveryProvider,
) {
    init {
        require(provider.providerKey == MUSICBRAINZ_PROVIDER) {
            "ISRC identity discovery v0 expects MusicBrainz Recording identities"
        }
    }

    suspend fun resolveMusicBrainzRecordingIdentity(
        recordingId: String,
    ): RecordingIdentityResolutionResult {
        val existing = musicBrainzIds(recordingId)
        when {
            existing.size == 1 -> return RecordingIdentityResolutionResult.AlreadyResolved(existing.single())
            existing.size > 1 -> return RecordingIdentityResolutionResult.AmbiguousExistingIdentity(existing)
        }

        val isrcs = IsrcNormalizer.normalizeDistinct(
            dao.externalIdentitiesForEntity(
                provider = ISRC_PROVIDER,
                entityType = RECORDING_ENTITY_TYPE,
                entityId = recordingId,
            ).map(ExternalIdentityEntity::externalId),
        )
        if (isrcs.isEmpty()) return RecordingIdentityResolutionResult.NoStrongIdentifier

        val convergedCandidates = LinkedHashSet<String>()
        for (isrc in isrcs) {
            when (val lookup = provider.lookupByIsrc(isrc)) {
                is StrongRecordingIdentityLookupResult.Failure -> {
                    return RecordingIdentityResolutionResult.Failed(lookup.reason)
                }
                is StrongRecordingIdentityLookupResult.Success -> {
                    val ids = LinkedHashSet<String>()
                    for (candidate in lookup.candidates) {
                        if (candidate.provider != MUSICBRAINZ_PROVIDER) {
                            return RecordingIdentityResolutionResult.Failed(
                                RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE,
                            )
                        }
                        val mbid = candidate.externalId.validMusicBrainzMbidOrNull()
                            ?: return RecordingIdentityResolutionResult.Failed(
                                RecordingIdentityDiscoveryFailure.MALFORMED_RESPONSE,
                            )
                        ids += mbid
                    }
                    if (ids.size > 1) {
                        return RecordingIdentityResolutionResult.AmbiguousLookup(
                            isrc = isrc,
                            musicBrainzIds = ids.toList(),
                        )
                    }
                    ids.singleOrNull()?.let(convergedCandidates::add)
                }
            }
        }

        if (convergedCandidates.isEmpty()) return RecordingIdentityResolutionResult.Unresolved
        if (convergedCandidates.size > 1) {
            return RecordingIdentityResolutionResult.ConflictingCandidates(
                convergedCandidates.toList(),
            )
        }
        val candidate = convergedCandidates.single()

        // Re-check after network activity so a concurrently-added trusted identity
        // cannot be ignored by this attempt. The final claim below is still atomic.
        val afterLookup = musicBrainzIds(recordingId)
        when {
            afterLookup.size == 1 -> return if (afterLookup.single().equals(candidate, ignoreCase = true)) {
                RecordingIdentityResolutionResult.AlreadyResolved(afterLookup.single())
            } else {
                RecordingIdentityResolutionResult.AmbiguousExistingIdentity(
                    (afterLookup + candidate).distinct(),
                )
            }
            afterLookup.size > 1 -> return RecordingIdentityResolutionResult.AmbiguousExistingIdentity(afterLookup)
        }

        val claimedBy = dao.entityIdForExternalIdentity(
            provider = MUSICBRAINZ_PROVIDER,
            entityType = RECORDING_ENTITY_TYPE,
            externalId = candidate,
        )
        when {
            claimedBy == recordingId -> return RecordingIdentityResolutionResult.AlreadyResolved(candidate)
            claimedBy != null -> return RecordingIdentityResolutionResult.IdentityConflict(candidate, claimedBy)
        }

        val insertedRowId = dao.insertExternalIdentityIfAbsent(
            ExternalIdentityEntity(
                provider = MUSICBRAINZ_PROVIDER,
                entityType = RECORDING_ENTITY_TYPE,
                externalId = candidate,
                entityId = recordingId,
            ),
        )
        return when (val persisted = dao.entityIdForExternalIdentity(
            MUSICBRAINZ_PROVIDER,
            RECORDING_ENTITY_TYPE,
            candidate,
        )) {
            null -> RecordingIdentityResolutionResult.Unresolved
            recordingId -> if (insertedRowId == -1L) {
                RecordingIdentityResolutionResult.AlreadyResolved(candidate)
            } else {
                RecordingIdentityResolutionResult.Promoted(candidate)
            }
            else -> RecordingIdentityResolutionResult.IdentityConflict(candidate, persisted)
        }
    }

    private suspend fun musicBrainzIds(recordingId: String): List<String> =
        dao.externalIdentitiesForEntity(
            provider = MUSICBRAINZ_PROVIDER,
            entityType = RECORDING_ENTITY_TYPE,
            entityId = recordingId,
        ).mapNotNull { identity ->
            identity.externalId.trim().takeIf(String::isNotEmpty)
        }.distinct()

    companion object {
        private const val ISRC_PROVIDER = "isrc"
        private const val RECORDING_ENTITY_TYPE = "recording"
    }
}

@Serializable
private data class MusicBrainzIsrcResponse(
    val isrc: String? = null,
    val recordings: List<MusicBrainzIsrcRecording> = emptyList(),
)

@Serializable
private data class MusicBrainzIsrcRecording(
    val id: String? = null,
)

private fun String?.validMusicBrainzMbidOrNull(): String? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return value.takeIf(MUSICBRAINZ_MBID_PATTERN::matches)
}

private val MUSICBRAINZ_MBID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)
