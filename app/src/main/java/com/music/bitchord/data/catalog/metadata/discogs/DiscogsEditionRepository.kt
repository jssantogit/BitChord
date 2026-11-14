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
        // A complete Master with two plausible versions, or an incomplete
        // Master list, cannot be made unique by a different search index.
        // In particular, token search may omit a color/pressing variant that
        // the typed Master already showed. Fail closed before searching.
        if (masterResult is DiscogsEditionEnrichment.Verified ||
            masterResult == DiscogsEditionEnrichment.Ambiguous ||
            masterResult == DiscogsEditionEnrichment.Candidate ||
            masterResult == DiscogsEditionEnrichment.Unavailable ||
            masterResult == DiscogsEditionEnrichment.Invalid ||
            masterResult == DiscogsEditionEnrichment.Disabled
        ) return masterResult
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
        // A Discogs Master groups *versions*. Its main_release is a display
        // representative, NOT evidence that no alternate pressing can match.
        // Only an exhaustive one-page bounded list can establish uniqueness.
        val mainReleaseId = master.mainReleaseId ?: return DiscogsEditionEnrichment.Candidate
        val ids = when (val versions = discogs.fetchMasterVersions(masterLink.id)) {
            is DiscogsMasterVersionsResult.Found -> versions.releaseIds
            DiscogsMasterVersionsResult.Incomplete -> return DiscogsEditionEnrichment.Candidate
            DiscogsMasterVersionsResult.Disabled -> return DiscogsEditionEnrichment.Disabled
            DiscogsMasterVersionsResult.Malformed -> return DiscogsEditionEnrichment.Invalid
            DiscogsMasterVersionsResult.Unavailable -> return DiscogsEditionEnrichment.Unavailable
        }
        if (mainReleaseId !in ids) return DiscogsEditionEnrichment.Candidate
        val observations = ArrayList<DiscogsObservation>(ids.size)
        for (id in ids) {
            val editionLink = DiscogsLink(DiscogsKind.RELEASE, id)
            val cached = cache.get(canonical.musicBrainzReleaseId, editionLink, nowMillis())
            val observation = if (cached != null) cached else {
                val record = when (val response = discogs.fetchRelease(id)) {
                    is DiscogsLookupResult.Found -> response.record
                    DiscogsLookupResult.Disabled -> return DiscogsEditionEnrichment.Disabled
                    DiscogsLookupResult.Malformed, DiscogsLookupResult.InvalidId,
                    is DiscogsLookupResult.FoundMaster -> return DiscogsEditionEnrichment.Invalid
                    else -> return DiscogsEditionEnrichment.Unavailable
                }
                DiscogsObservation(
                    canonicalMbid = canonical.musicBrainzReleaseId,
                    kind = DiscogsKind.RELEASE,
                    record = record,
                    fetchedAtMillis = nowMillis(),
                )
            }
            // Every version must explicitly belong to the linked Master.
            // Otherwise the untrusted list cannot prove a complete set.
            if (observation.record.link != editionLink ||
                observation.record.masterId != masterLink.id ||
                !observation.isDisplayableAt(nowMillis())
            ) return DiscogsEditionEnrichment.Candidate
            observations += observation
        }
        val decisions = observations.map {
            it to DiscogsEditionMatcher.assessUnlinked(canonical, it.record)
        }
        val verified = decisions.filter { it.second == DiscogsEditionVerdict.VERIFIED }
            .map { it.first }
        val pending = decisions.any { it.second == DiscogsEditionVerdict.CANDIDATE }
        return when {
            verified.size > 1 || (verified.isNotEmpty() && pending) ->
                DiscogsEditionEnrichment.Ambiguous
            verified.size == 1 -> {
                val chosen = verified.single()
                cache.put(chosen)
                DiscogsEditionEnrichment.Verified(chosen)
            }
            pending -> DiscogsEditionEnrichment.Candidate
            else -> DiscogsEditionEnrichment.Conflict
        }
    }
}
