package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsEditionPresentationTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val link = DiscogsLink(DiscogsKind.RELEASE, 123)

    @Test fun onlyVerifiedFreshEditionCanSupplyAttributableSupplementaryFields() {
        val record = DiscogsRecord(
            link = link, title = "Artist - Album", artistNames = listOf("Artist"),
            country = "South Korea", year = 2024,
            labels = listOf("Label A" to "CAT-123"),
            barcodes = listOf("0012345678905"), formats = listOf("CD", "EP"),
            tracklist = listOf("1" to "Intro"), unofficial = false,
        )
        val observed = DiscogsObservation(mbid, DiscogsKind.RELEASE, record, fetchedAtMillis = 1_000)
        val evidence = DiscogsEditionEnrichment.Verified(observed)
        val display = DiscogsEditionPresentation.from(evidence, nowMillis = 1_001)
        assertNotNull(display)
        assertEquals("Data provided by Discogs.", display!!.attributionText)
        assertEquals("https://www.discogs.com/release/123", display.attributionUrl)
        assertEquals("South Korea", display.country)
        assertEquals(2024, display.editionYear)
        assertEquals(listOf("Label A (CAT-123)"), display.labelLines)
        assertEquals(listOf("CD", "EP"), display.formats)
        assertFalse(display.toString().contains("http://images"))
        assertNull(DiscogsEditionPresentation.from(evidence, nowMillis =
            1_000 + DiscogsObservation.TTL_MILLIS))
    }

    @Test fun candidateConflictAndUnavailableNeverSurfaceDetailsOrAttribution() {
        assertNull(DiscogsEditionPresentation.from(DiscogsEditionEnrichment.Candidate, 1))
        assertNull(DiscogsEditionPresentation.from(DiscogsEditionEnrichment.Conflict, 1))
        assertNull(DiscogsEditionPresentation.from(DiscogsEditionEnrichment.Disabled, 1))
        assertNull(DiscogsEditionPresentation.from(DiscogsEditionEnrichment.Unavailable, 1))
    }

    @Test fun unofficialPressingNeverBecomesACommercialVerificationLabel() {
        val record = DiscogsRecord(
            link, "Artist - Album", listOf("Artist"), "KR", 2024,
            emptyList(), emptyList(), listOf("Vinyl", "Unofficial Release"),
            emptyList(), unofficial = true
        )
        val observed = DiscogsObservation(mbid, DiscogsKind.RELEASE, record, 1_000)
        val display = DiscogsEditionPresentation.from(
            DiscogsEditionEnrichment.Verified(observed), nowMillis = 1_000,
        )
        assertNull("Do not display unofficial Discogs metadata as verified edition",
            display)
    }

    @Test fun sourceUrlIsAlwaysGeneratedFromTypedIdAndCannotEmbedSecrets() {
        val record = DiscogsRecord(
            link, "Album", listOf("Artist"), null, null,
            emptyList(), emptyList(), emptyList(), emptyList(), unofficial = false
        )
        val view = DiscogsEditionPresentation.from(
            DiscogsEditionEnrichment.Verified(DiscogsObservation(mbid, DiscogsKind.RELEASE, record, 0)),
            1
        )
        assertNotNull(view)
        assertEquals("https://www.discogs.com/release/123", view!!.attributionUrl)
        assertTrue(view.labelLines.isEmpty())
    }
}
