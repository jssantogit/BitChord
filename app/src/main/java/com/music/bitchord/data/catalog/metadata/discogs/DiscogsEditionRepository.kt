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
) {
    suspend fun observe(canonical: CanonicalEditionEvidence): DiscogsEditionEnrichment {
        if (!enabled) return DiscogsEditionEnrichment.Disabled
        val link = when (val result = links.findRelease(canonical.musicBrainzReleaseId)) {
            is DiscogsRelationResult.Linked -> result.link
            DiscogsRelationResult.NoLink -> return DiscogsEditionEnrichment.NoLink
            DiscogsRelationResult.Ambiguous -> return DiscogsEditionEnrichment.Ambiguous
            DiscogsRelationResult.InvalidCanonicalId,
            DiscogsRelationResult.InvalidResponse -> return DiscogsEditionEnrichment.Invalid
            DiscogsRelationResult.Unavailable -> return DiscogsEditionEnrichment.Unavailable
        }
        val cached = cache.get(canonical.musicBrainzReleaseId, link, nowMillis())
        val observation = if (cached != null) cached else {
            val record = when (val lookup = discogs.fetchRelease(link.id)) {
                is DiscogsLookupResult.Found -> lookup.record
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
        return when (DiscogsEditionMatcher.assess(canonical, link, observation.record)) {
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
