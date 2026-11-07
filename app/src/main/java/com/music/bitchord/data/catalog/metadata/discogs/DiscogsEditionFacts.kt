package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.metadata.CanonicalEnrichmentField
import com.music.bitchord.data.catalog.metadata.CanonicalProviderFact
import com.music.bitchord.data.catalog.metadata.CatalogEvidenceBasis
import com.music.bitchord.data.catalog.metadata.CatalogExternalProvider
import com.music.bitchord.data.catalog.metadata.CatalogTargetKind
import com.music.bitchord.data.catalog.metadata.ExternalEditorialEvidence
import com.music.bitchord.data.catalog.metadata.selectCanonicalProviderFacts

/**
 * Narrow bridge to the existing source-provenance read contract. Discogs fields
 * remain advisory, short-lived and scoped to an exact MusicBrainz Release.
 * This is NOT an authoritative update to canonical release/recording tables.
 *
 * Attribution must remain attached to the original DiscogsEditionEnrichment
 * or DiscogsEditionPresentation when any of these facts are rendered.
 */
internal object DiscogsEditionFacts {
    fun from(
        result: DiscogsEditionEnrichment,
        nowMillis: Long,
    ): List<CanonicalProviderFact> {
        val verified = result as? DiscogsEditionEnrichment.Verified ?: return emptyList()
        val observation = verified.observation
        if (!observation.isDisplayableAt(nowMillis) ||
            observation.record.unofficial ||
            observation.kind != DiscogsKind.RELEASE
        ) return emptyList()
        val externalId = observation.record.link.id.toString()
        val identity = ExternalEditorialEvidence(
            provider = CatalogExternalProvider.DISCOGS,
            externalId = externalId,
            targetKind = CatalogTargetKind.RELEASE,
            canonicalMbid = observation.canonicalMbid,
            // This bridge is ONLY called on a matched, corroborated edition.
            basis = CatalogEvidenceBasis.DIRECT_MBID,
        )
        val expiry = observation.fetchedAtMillis + DiscogsObservation.TTL_MILLIS
        val facts = mutableListOf<CanonicalProviderFact>()
        fun add(field: CanonicalEnrichmentField, value: String?) {
            val v = value?.takeIf { it.isNotBlank() && it.length <= 250 } ?: return
            facts += CanonicalProviderFact(
                evidence = identity, field = field, value = v,
                recordedAtEpochMs = observation.fetchedAtMillis,
                expiresAtEpochMs = expiry,
            )
        }
        for ((name, catalog) in observation.record.labels.take(16)) {
            add(CanonicalEnrichmentField.EDITION_LABEL, name)
            add(CanonicalEnrichmentField.EDITION_CATALOG_NUMBER, catalog)
        }
        for (format in observation.record.formats.take(32)) {
            add(CanonicalEnrichmentField.EDITION_FORMAT, format)
        }
        add(CanonicalEnrichmentField.EDITION_COUNTRY, observation.record.country)
        observation.record.year?.let {
            add(CanonicalEnrichmentField.EDITION_YEAR, it.toString())
        }
        // The central filter retains the canonical MBID and source permissions.
        return selectCanonicalProviderFacts(facts, observation.canonicalMbid, nowMillis)
    }
}
