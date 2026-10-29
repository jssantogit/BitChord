package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CatalogExternalProvider
import com.music.bitchord.data.catalog.metadata.CatalogArtworkRight
import com.music.bitchord.data.catalog.metadata.CatalogEvidenceBasis
import com.music.bitchord.data.catalog.metadata.CatalogTargetKind
import com.music.bitchord.data.catalog.metadata.CanonicalEnrichmentField
import com.music.bitchord.data.catalog.metadata.CanonicalProviderFact
import com.music.bitchord.data.catalog.metadata.ExternalEditorialEvidence
import com.music.bitchord.data.catalog.metadata.selectCanonicalProviderFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalProviderEnrichmentPolicyTest {
    private val mbid = "b51c672b-85e0-48fe-8648-470a2422229f"
    private val lastfm = ExternalEditorialEvidence(
        CatalogExternalProvider.LAST_FM, "newjeans", CatalogTargetKind.ARTIST,
        mbid, CatalogEvidenceBasis.DIRECT_MBID,
    )
    private val listen = ExternalEditorialEvidence(
        CatalogExternalProvider.LISTENBRAINZ, mbid, CatalogTargetKind.ARTIST,
        mbid, CatalogEvidenceBasis.DIRECT_MBID,
    )

    @Test fun providerEnrichmentNeverPromotesPopularityOrGenreToOfficialDiscography() {
        val facts = listOf(
            CanonicalProviderFact(lastfm, CanonicalEnrichmentField.GENRE_TAG,
                "k-pop", recordedAtEpochMs = 1_000L),
            CanonicalProviderFact(listen, CanonicalEnrichmentField.LISTEN_COUNT,
                "5000", recordedAtEpochMs = 1_100L),
            CanonicalProviderFact(lastfm, CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE,
                "Album", recordedAtEpochMs = 1_200L),
            CanonicalProviderFact(lastfm, CanonicalEnrichmentField.LISTEN_COUNT,
                "9999", recordedAtEpochMs = 1_300L),
        )
        val visible = selectCanonicalProviderFacts(facts, targetMbid = mbid,
            nowEpochMs = 1_400L)
        assertEquals(setOf(CanonicalEnrichmentField.GENRE_TAG,
            CanonicalEnrichmentField.LISTEN_COUNT), visible.map { it.field }.toSet())
        assertTrue(visible.none { it.field == CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE })
    }

    @Test fun staleUnsafeOrUnverifiedAppleArtNeverOverridesCanonicalCAA() {
        val apple = ExternalEditorialEvidence(
            CatalogExternalProvider.APPLE_MUSIC, "1214574815",
            CatalogTargetKind.RELEASE, mbid, CatalogEvidenceBasis.DIRECT_MBID,
            storefront = "br", artworkRight = CatalogArtworkRight.ALLOWED,
        )
        val base = CanonicalProviderFact(apple, CanonicalEnrichmentField.ARTWORK,
            "https://example.org/album.jpg", recordedAtEpochMs = 1_000L,
            expiresAtEpochMs = 5_000L)
        val valid = selectCanonicalProviderFacts(listOf(
            base.copy(evidence = apple.copy(artworkRight = CatalogArtworkRight.UNKNOWN)),
            base.copy(evidence = apple.copy(basis = CatalogEvidenceBasis.TITLE_ONLY)),
            base.copy(expiresAtEpochMs = 1_100L),
            base,
        ), mbid, 2_000L)
        assertEquals(listOf(base), valid)
    }

    @Test fun canonicalEnrichmentFiltersOtherArtistAndNeverConvertsMissingDataToFact() {
        val good = CanonicalProviderFact(lastfm, CanonicalEnrichmentField.ARTIST_BIO,
            "Official biography", recordedAtEpochMs = 200L)
        val other = good.copy(evidence = lastfm.copy(canonicalMbid =
            "11111111-1111-4111-8111-111111111111"))
        val empty = good.copy(value = " ")
        assertEquals(listOf(good),
            selectCanonicalProviderFacts(listOf(other, empty, good), mbid, 300L))
    }
}
