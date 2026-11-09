package com.music.bitchord.data.catalog.metadata.discogs

/** Only Verified carries source fields that may be considered for presentation. */
internal sealed interface DiscogsEditionEnrichment {
    data class Verified(val observation: DiscogsObservation) : DiscogsEditionEnrichment {
        val attributionText: String get() = "Data provided by Discogs."
        val attributionUrl: String get() = observation.record.attributionUrl
    }

    data object Disabled : DiscogsEditionEnrichment
    data object NoLink : DiscogsEditionEnrichment
    data object Ambiguous : DiscogsEditionEnrichment
    data object Candidate : DiscogsEditionEnrichment
    data object Conflict : DiscogsEditionEnrichment
    data object Unavailable : DiscogsEditionEnrichment
    data object Invalid : DiscogsEditionEnrichment
}

/**
 * Read-only catalog evidence path. Default-off, no canonical writes, no image
 * URLs and no playback surfaces. A caller needs a verified MusicBrainz edition
 * graph with artist/track evidence before asking for Discogs corroboration.
 *
 * This class is deliberately not wired into the Android UI while the legal/API
 * access gates are open. User data never reaches the Discogs transport.
 */
internal class DiscogsEditionRepository(
    private val links: DiscogsMusicBrainzLinks = DiscogsMusicBrainzLinks(),
    private val discogs: DiscogsClient = DiscogsClient(),
    private val cache: DiscogsMemoryEvidenceStore = DiscogsMemoryEvidenceStore(),
    private val enabled: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val authenticatedDiscovery: DiscogsAuthenticatedDiscovery? = null,
) {
    /** On provider rollback, purge only volatile Discogs observations. */
    fun clearCachedEvidence() = cache.clear()

    suspend fun observe(canonical: CanonicalEditionEvidence): DiscogsEditionEnrichment {
        if (!enabled) return DiscogsEditionEnrichment.Disabled
        val link = when (val result = links.findRelease(canonical.musicBrainzReleaseId)) {
            is DiscogsRelationResult.Linked -> result.link
            DiscogsRelationResult.NoLink -> return observeFallbacks(canonical)
            DiscogsRelationResult.Ambiguous -> return DiscogsEditionEnrichment.Ambiguous
            DiscogsRelationResult.InvalidCanonicalId,
            DiscogsRelationResult.InvalidResponse -> return DiscogsEditionEnrichment.Invalid
            DiscogsRelationResult.Unavailable -> return DiscogsEditionEnrichment.Unavailable
        }
        val cached = cache.get(canonical.musicBrainzReleaseId, link, nowMillis())
        val observation = if (cached != null) cached else {
            val record = when (val lookup = discogs.fetchRelease(link.id)) {
                is DiscogsLookupResult.Found -> lookup.record
                is DiscogsLookupResult.FoundMaster -> return DiscogsEditionEnrichment.Invalid
                DiscogsLookupResult.Disabled -> return DiscogsEditionEnrichment.Disabled
                DiscogsLookupResult.Missing -> return DiscogsEditionEnrichment.NoLink
                DiscogsLookupResult.InvalidId,
                DiscogsLookupResult.Malformed -> return DiscogsEditionEnrichment.Invalid
                DiscogsLookupResult.Unauthorized,
                is DiscogsLookupResult.Throttled,
                DiscogsLookupResult.Unavailable -> return DiscogsEditionEnrichment.Unavailable
            }
            DiscogsObservation(
                canonicalMbid = canonical.musicBrainzReleaseId,
                kind = DiscogsKind.RELEASE,
                record = record,
                fetchedAtMillis = nowMillis(),
            )
        }
        return when (DiscogsEditionMatcher.assess(
            canonical, link, observation.record, explicitReleaseRelation = true,
        )) {
            DiscogsEditionVerdict.VERIFIED -> {
                if (!observation.isDisplayableAt(nowMillis())) {
                    DiscogsEditionEnrichment.Unavailable
                } else {
                    if (cached == null) cache.put(observation)
                    DiscogsEditionEnrichment.Verified(observation)
                }
            }
            DiscogsEditionVerdict.CANDIDATE -> DiscogsEditionEnrichment.Candidate
            DiscogsEditionVerdict.CONFLICT -> DiscogsEditionEnrichment.Conflict
        }
    }
    /**
     * Optional search is attempted only after cheaper canonical relation paths.
     * Search requires a local personal token; without one, existing anonymous
     * behavior is unchanged. The result is always re-verified against the
     * exact MusicBrainz edition before it can become a UI fact.
     */
    private suspend fun observeFallbacks(
        canonical: CanonicalEditionEvidence,
    ): DiscogsEditionEnrichment {
        val masterResult = observeViaReleaseGroupMaster(canonical)
        if (masterResult is DiscogsEditionEnrichment.Verified) return masterResult
        val discovery = authenticatedDiscovery ?: return masterResult
        return when (val match = discovery.findForEdition(canonical)) {
            is DiscogsAuthenticatedMatch.Verified -> {
                val now = nowMillis()
                val observation = DiscogsObservation(
                    canonicalMbid = canonical.musicBrainzReleaseId,
                    kind = DiscogsKind.RELEASE,
                    record = match.record,
                    fetchedAtMillis = now,
                )
                if (!observation.isDisplayableAt(now)) DiscogsEditionEnrichment.Unavailable
                else {
                    cache.put(observation)
                    DiscogsEditionEnrichment.Verified(observation)
                }
            }
            is DiscogsAuthenticatedMatch.Ambiguous -> DiscogsEditionEnrichment.Ambiguous
            is DiscogsAuthenticatedMatch.Candidates ->
                if (masterResult is DiscogsEditionEnrichment.Conflict) masterResult
                else DiscogsEditionEnrichment.Candidate
            // A missing token, unavailable search, or no result never converts
            // an independently observed Master conflict into a fake match.
            else -> masterResult
        }
    }

    /**
     * ReleaseGroup → Master relationships can recover an *edition candidate*
     * without a direct Release URL. A Discogs Master only supplies the ID of a
     * possible Release; it never asserts exact edition identity by itself.
     * The main release is intentionally just the first candidate, not an
     * exhaustive authenticated catalog search.
     */
    private suspend fun observeViaReleaseGroupMaster(
        canonical: CanonicalEditionEvidence,
    ): DiscogsEditionEnrichment {
        val groupId = canonical.musicBrainzReleaseGroupId?.trim()?.takeIf {
            Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(it)
        } ?: return DiscogsEditionEnrichment.NoLink
        val masterLink = when (val result = links.findReleaseGroup(groupId)) {
            is DiscogsRelationResult.Linked -> result.link
            DiscogsRelationResult.NoLink -> return DiscogsEditionEnrichment.NoLink
            DiscogsRelationResult.Ambiguous -> return DiscogsEditionEnrichment.Ambiguous
            DiscogsRelationResult.InvalidCanonicalId,
            DiscogsRelationResult.InvalidResponse -> return DiscogsEditionEnrichment.Invalid
            DiscogsRelationResult.Unavailable -> return DiscogsEditionEnrichment.Unavailable
        }
        val master = when (val response = discogs.fetchMaster(masterLink.id)) {
            is DiscogsLookupResult.FoundMaster -> response.record
            DiscogsLookupResult.Missing -> return DiscogsEditionEnrichment.NoLink
            DiscogsLookupResult.Malformed, DiscogsLookupResult.InvalidId,
            is DiscogsLookupResult.Found -> return DiscogsEditionEnrichment.Invalid
            DiscogsLookupResult.Disabled -> return DiscogsEditionEnrichment.Disabled
            else -> return DiscogsEditionEnrichment.Unavailable
        }
        val editionId = master.mainReleaseId ?: return DiscogsEditionEnrichment.Candidate
        val editionLink = DiscogsLink(DiscogsKind.RELEASE, editionId)
        val cached = cache.get(canonical.musicBrainzReleaseId, editionLink, nowMillis())
        val observation = if (cached != null) cached else {
            val record = when (val response = discogs.fetchRelease(editionId)) {
                is DiscogsLookupResult.Found -> response.record
                DiscogsLookupResult.Missing -> return DiscogsEditionEnrichment.NoLink
                DiscogsLookupResult.Malformed, DiscogsLookupResult.InvalidId,
                is DiscogsLookupResult.FoundMaster -> return DiscogsEditionEnrichment.Invalid
                DiscogsLookupResult.Disabled -> return DiscogsEditionEnrichment.Disabled
                else -> return DiscogsEditionEnrichment.Unavailable
            }
            DiscogsObservation(
                canonicalMbid = canonical.musicBrainzReleaseId,
                kind = DiscogsKind.RELEASE,
                record = record,
                fetchedAtMillis = nowMillis(),
            )
        }
        return when (DiscogsEditionMatcher.assessUnlinked(canonical, observation.record)) {
            DiscogsEditionVerdict.VERIFIED -> {
                if (!observation.isDisplayableAt(nowMillis())) {
                    DiscogsEditionEnrichment.Unavailable
                } else {
                    if (cached == null) cache.put(observation)
                    DiscogsEditionEnrichment.Verified(observation)
                }
            }
            DiscogsEditionVerdict.CANDIDATE -> DiscogsEditionEnrichment.Candidate
            DiscogsEditionVerdict.CONFLICT -> DiscogsEditionEnrichment.Conflict
        }
    }
}
