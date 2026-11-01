package com.music.bitchord.data.catalog.metadata

/**
 * Future external-provider data is advisory. MusicBrainz remains the release
 * identity/type authority; a listening count never makes an album official.
 * No network I/O, tokens or provider SDKs are involved in this read model.
 */
internal enum class CanonicalEnrichmentField {
    ARTIST_BIO, GENRE_TAG, LISTEN_COUNT, ARTWORK,
    /** Intentionally rejected from enrichment: canonical MusicBrainz truth. */
    RELEASE_PRIMARY_TYPE,
}

internal data class CanonicalProviderFact(
    val evidence: ExternalEditorialEvidence,
    val field: CanonicalEnrichmentField,
    val value: String,
    val recordedAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
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
                CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE -> false
            }
    }
    return permitted.sortedWith(compareBy<CanonicalProviderFact> { it.field.name }
        .thenByDescending { it.recordedAtEpochMs }
        .thenBy { it.evidence.scopedExternalKey() })
        .distinctBy { listOf(it.field.name, it.evidence.scopedExternalKey(),
            it.value.trim()) }
}
