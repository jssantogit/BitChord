package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsCanonicalProviderPolicyTest {
    private val releaseMbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val discogsEdition = ExternalEditorialEvidence(
        provider = CatalogExternalProvider.DISCOGS,
        externalId = "12345",
        targetKind = CatalogTargetKind.RELEASE,
        canonicalMbid = releaseMbid,
        basis = CatalogEvidenceBasis.DIRECT_MBID,
        artworkRight = CatalogArtworkRight.ALLOWED,
    )

    @Test fun discogsSupplementaryFactsAreAllowedOnlyForVerifiedEditionNotGroup() {
        val label = CanonicalProviderFact(
            evidence = discogsEdition,
            field = CanonicalEnrichmentField.EDITION_LABEL,
            value = "Belift Lab",
            recordedAtEpochMs = 100,
            expiresAtEpochMs = 200,
        )
        assertEquals(listOf(label), selectCanonicalProviderFacts(listOf(label), releaseMbid, 150))
        assertTrue(selectCanonicalProviderFacts(listOf(label.copy(
            evidence = discogsEdition.copy(targetKind = CatalogTargetKind.RELEASE_GROUP),
        )), releaseMbid, 150).isEmpty())
        assertTrue(selectCanonicalProviderFacts(listOf(label.copy(
            evidence = discogsEdition.copy(basis = CatalogEvidenceBasis.TITLE_ONLY),
        )), releaseMbid, 150).isEmpty())
        assertTrue(selectCanonicalProviderFacts(listOf(label), releaseMbid, 200).isEmpty())
    }

    @Test fun discogsCanNeverSupplyArtworkOrCanonicalTypeEvenIfRightsClaimed() {
        assertFalse(discogsEdition.canSupplyArtwork())
        val facts = listOf(
            CanonicalProviderFact(discogsEdition, CanonicalEnrichmentField.ARTWORK,
                "https://img.discogs.com/art.jpg", 100L, 200L),
            CanonicalProviderFact(discogsEdition, CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE,
                "Album", 100L, 200L),
            CanonicalProviderFact(discogsEdition, CanonicalEnrichmentField.LISTEN_COUNT,
                "99999", 100L, 200L),
            CanonicalProviderFact(discogsEdition, CanonicalEnrichmentField.EDITION_FORMAT,
                "CD", 100L, 200L),
        )
        assertEquals(listOf(CanonicalEnrichmentField.EDITION_FORMAT),
            selectCanonicalProviderFacts(facts, releaseMbid, 150L).map { it.field })
    }

    @Test fun invalidOrCrossProviderEditionClaimsDoNotBecomeValidDiscogsFacts() {
        val fact = CanonicalProviderFact(
            discogsEdition, CanonicalEnrichmentField.EDITION_YEAR, "2024", 100L, 200L)
        val invalid = listOf(
            fact.copy(evidence = discogsEdition.copy(canonicalMbid = "n/a")),
            fact.copy(evidence = discogsEdition.copy(provider = CatalogExternalProvider.LISTENBRAINZ)),
            fact.copy(value = "1970; DROP TABLE"),
            fact.copy(value = "0"),
        )
        assertTrue(selectCanonicalProviderFacts(invalid, releaseMbid, 150L).isEmpty())
        assertEquals(listOf(fact), selectCanonicalProviderFacts(listOf(fact), releaseMbid, 150L))
    }
}
