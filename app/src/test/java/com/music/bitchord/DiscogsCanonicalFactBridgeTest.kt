package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsCanonicalFactBridgeTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val link = DiscogsLink(DiscogsKind.RELEASE, 12345)

    private fun release(unofficial: Boolean = false) = DiscogsRecord(
        link = link, title = "ILLIT - SUPER REAL ME",
        artistNames = listOf("ILLIT"), country = "KR",
        year = 2024, labels = listOf("Belift Lab" to "BEL-001"),
        barcodes = listOf("036000291452"),
        formats = listOf("CD", "EP"),
        tracklist = listOf("1" to "My World"),
        unofficial = unofficial
    )

    @Test fun freshVerifiedReleaseMapsToTimedSourceFactsWithoutCanonicalOverwrite() {
        val observation = DiscogsObservation(mbid, DiscogsKind.RELEASE, release(), 1_000L)
        val facts = DiscogsEditionFacts.from(
            DiscogsEditionEnrichment.Verified(observation), nowMillis = 1_001L)
        assertTrue(facts.isNotEmpty())
        assertEquals(setOf(
            CanonicalEnrichmentField.EDITION_LABEL,
            CanonicalEnrichmentField.EDITION_CATALOG_NUMBER,
            CanonicalEnrichmentField.EDITION_FORMAT,
            CanonicalEnrichmentField.EDITION_COUNTRY,
            CanonicalEnrichmentField.EDITION_YEAR,
        ), facts.map { it.field }.toSet())
        assertTrue(facts.all { it.evidence.provider == CatalogExternalProvider.DISCOGS })
        assertTrue(facts.all { it.evidence.targetKind == CatalogTargetKind.RELEASE })
        assertTrue(facts.all { it.evidence.canonicalMbid == mbid })
        assertTrue(facts.all { it.expiresAtEpochMs == 1_000L + DiscogsObservation.TTL_MILLIS })
        assertTrue(selectCanonicalProviderFacts(facts, mbid, 1_001L).size == facts.size)
        assertTrue(facts.none { it.field == CanonicalEnrichmentField.ARTWORK ||
            it.field == CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE ||
            it.field == CanonicalEnrichmentField.LISTEN_COUNT })
    }

    @Test fun expiredAmbiguousOrUnofficialSourceNeverReturnsCanonicalFacts() {
        val ok = DiscogsEditionEnrichment.Verified(DiscogsObservation(
            mbid, DiscogsKind.RELEASE, release(), 1_000L))
        assertTrue(DiscogsEditionFacts.from(ok,
            1_000L + DiscogsObservation.TTL_MILLIS).isEmpty())
        assertTrue(DiscogsEditionFacts.from(DiscogsEditionEnrichment.Ambiguous, 1_002L).isEmpty())
        assertTrue(DiscogsEditionFacts.from(DiscogsEditionEnrichment.Candidate, 1_002L).isEmpty())
        assertTrue(DiscogsEditionFacts.from(DiscogsEditionEnrichment.Verified(
            DiscogsObservation(mbid, DiscogsKind.RELEASE, release(unofficial = true), 1_000L)
        ), 1_001L).isEmpty())
    }
}
