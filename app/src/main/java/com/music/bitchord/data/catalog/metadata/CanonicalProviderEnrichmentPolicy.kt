package com.music.bitchord.data.catalog.metadata

/**
 * Future external-provider data is advisory. MusicBrainz remains the release
 * identity/type authority; a listening count never makes an album official.
 * No network I/O, tokens or provider SDKs are involved in this read model.
 */
internal enum class CanonicalEnrichmentField {
    ARTIST_BIO, GENRE_TAG, LISTEN_COUNT, ARTWORK,
    /** Provider-scoped supplementary data about an exact edition, not ReleaseGroup truth. */
    EDITION_LABEL, EDITION_CATALOG_NUMBER, EDITION_FORMAT, EDITION_COUNTRY, EDITION_YEAR,
    /** Intentionally rejected from enrichment: canonical MusicBrainz truth. */
    RELEASE_PRIMARY_TYPE,
}

internal data class CanonicalProviderFact(
    val evidence: ExternalEditorialEvidence,
    val field: CanonicalEnrichmentField,
    val value: String,
    val recordedAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    /** Immutable, source-specific attribution. Discogs facts require BOTH fields. */
    val attributionText: String? = null,
    val attributionUrl: String? = null,
)

internal fun selectCanonicalProviderFacts(
    observations: List<CanonicalProviderFact>,
    targetMbid: String,
    nowEpochMs: Long,
): List<CanonicalProviderFact> {
    val permitted = observations.filter { fact ->
        fact.evidence.canonicalMbid.equals(targetMbid, ignoreCase = true) &&
            fact.evidence.assess() == CatalogEvidenceDecision.VERIFIED &&
            fact.value.isNotBlank() &&
            fact.recordedAtEpochMs in 0..nowEpochMs &&
            (fact.expiresAtEpochMs == null || fact.expiresAtEpochMs > nowEpochMs) &&
            (fact.evidence.provider != CatalogExternalProvider.DISCOGS ||
                (fact.attributionText == "Data provided by Discogs." &&
                    fact.attributionUrl ==
                    "https://www.discogs.com/release/" + fact.evidence.externalId &&
                    // No external caller may make Discogs API facts permanently fresh.
                    fact.expiresAtEpochMs?.let { expiry ->
                        expiry > fact.recordedAtEpochMs &&
                            expiry - fact.recordedAtEpochMs <= 5L * 60L * 60L * 1000L
                    } == true)) &&
            when (fact.field) {
                CanonicalEnrichmentField.ARTIST_BIO ->
                    fact.evidence.targetKind == CatalogTargetKind.ARTIST &&
                        fact.evidence.provider == CatalogExternalProvider.LAST_FM
                CanonicalEnrichmentField.GENRE_TAG ->
                    fact.evidence.targetKind in setOf(
                        CatalogTargetKind.ARTIST, CatalogTargetKind.RELEASE_GROUP,
                    ) && fact.evidence.provider in setOf(
                        CatalogExternalProvider.LAST_FM, CatalogExternalProvider.APPLE_MUSIC,
                    )
                CanonicalEnrichmentField.LISTEN_COUNT ->
                    fact.evidence.provider == CatalogExternalProvider.LISTENBRAINZ &&
                        fact.value.toLongOrNull()?.let { it >= 0 } == true
                CanonicalEnrichmentField.ARTWORK ->
                    fact.evidence.provider == CatalogExternalProvider.APPLE_MUSIC &&
                        fact.evidence.canSupplyArtwork() &&
                        fact.value.startsWith("https://", ignoreCase = true)
                CanonicalEnrichmentField.EDITION_LABEL,
                CanonicalEnrichmentField.EDITION_CATALOG_NUMBER,
                CanonicalEnrichmentField.EDITION_FORMAT,
                CanonicalEnrichmentField.EDITION_COUNTRY ->
                    fact.evidence.targetKind == CatalogTargetKind.RELEASE &&
                        fact.evidence.provider == CatalogExternalProvider.DISCOGS &&
                        fact.expiresAtEpochMs != null &&
                        fact.value.length <= 250
                CanonicalEnrichmentField.EDITION_YEAR ->
                    fact.evidence.targetKind == CatalogTargetKind.RELEASE &&
                        fact.evidence.provider == CatalogExternalProvider.DISCOGS &&
                        fact.expiresAtEpochMs != null &&
                        fact.value.toIntOrNull()?.let { it in 1800..2200 } == true
                CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE -> false
            }
    }
    return permitted.sortedWith(compareBy<CanonicalProviderFact> { it.field.name }
        .thenByDescending { it.recordedAtEpochMs }
        .thenBy { it.evidence.scopedExternalKey() })
        .distinctBy { listOf(it.field.name, it.evidence.scopedExternalKey(),
            it.value.trim()) }
}
