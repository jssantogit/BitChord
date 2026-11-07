package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLink
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsObservation
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAlbumEditorialFooterTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val link = DiscogsLink(DiscogsKind.RELEASE, 12345)

    private fun edition(
        country: String? = "KR",
        labels: List<Pair<String, String>> = listOf("Belift Lab" to "BEL-001"),
        formats: List<String> = listOf("CD", "EP"),
        unofficial: Boolean = false,
    ): DiscogsEditionEnrichment.Verified {
        val record = DiscogsRecord(
            link = link, title = "SUPER REAL ME", artistNames = listOf("ILLIT"),
            country = country, year = 2024, labels = labels,
            barcodes = emptyList(), formats = formats,
            tracklist = listOf("1" to "Magnetic"), unofficial = unofficial,
        )
        return DiscogsEditionEnrichment.Verified(
            DiscogsObservation(mbid, DiscogsKind.RELEASE, record, fetchedAtMillis = 1_000L)
        )
    }

    @Test fun verifiedEditionAppearsAfterExistingAlbumSummaryWithAdjacentAttribution() {
        val footer = nativeAlbumEditorialFooter(edition(), nowMillis = 1_100L)
        assertEquals(
            listOf(
                "Gravadora: Belift Lab (BEL-001)",
                "Formato: CD · EP",
                "País: Coreia do Sul",
                "Ano: 2024",
            ), footer!!.lines,
        )
        assertEquals("Data provided by Discogs.", footer.attributionText)
        assertEquals("https://www.discogs.com/release/12345", footer.attributionUrl)
        assertEquals(1_000L + DiscogsObservation.TTL_MILLIS, footer.expiresAtMillis)
    }

    @Test fun missingOrInvalidEvidenceDoesNotAddASecondFooterOrPlaceholder() {
        assertNull(nativeAlbumEditorialFooter(null, nowMillis = 1_100L))
        assertNull(nativeAlbumEditorialFooter(DiscogsEditionEnrichment.Candidate, 1_100L))
        assertNull(nativeAlbumEditorialFooter(DiscogsEditionEnrichment.Conflict, 1_100L))
        assertNull(nativeAlbumEditorialFooter(DiscogsEditionEnrichment.Disabled, 1_100L))
        assertNull(nativeAlbumEditorialFooter(edition(unofficial = true), 1_100L))
        assertNull(nativeAlbumEditorialFooter(edition(),
            1_000L + DiscogsObservation.TTL_MILLIS))
        assertNull(nativeAlbumEditorialFooter(edition(), 999L))
    }

    @Test fun onlyUsefulSourceFactsAreShownAndTheyAreBounded() {
        val footer = nativeAlbumEditorialFooter(
            edition(country = " ", labels = emptyList(), formats = emptyList()),
            nowMillis = 1_100L,
        )
        assertEquals(listOf("Ano: 2024"), footer!!.lines)

        val overflowing = nativeAlbumEditorialFooter(
            edition(
                labels = (1..60).map { "Label $it" to "CAT-$it" },
                formats = (1..30).map { "Format $it" },
            ), nowMillis = 1_100L,
        )!!
        assertTrue(overflowing.lines.size <= 4)
        assertTrue(overflowing.lines.all { it.length <= 320 })
        assertFalse(overflowing.lines.any { it.contains("Label 60") })
    }

    @Test fun extraVerifiedLabelsAndFormatsAreAvailableWithoutCrowdingTheSummary() {
        val footer = nativeAlbumEditorialFooter(
            edition(
                labels = listOf("Belift Lab" to "BEL-001",
                    "Second Label" to "CAT-02", "Third Label" to "CAT-03"),
                formats = listOf("CD", "EP", "Stereo", "Limited",
                    "Reissue", "Digipak"),
            ),
            nowMillis = 1_100L,
        )!!
        assertEquals(4, footer.lines.size)
        assertEquals("Gravadora: Belift Lab (BEL-001)", footer.lines.first())
        assertEquals(
            listOf("Gravadora: Second Label (CAT-02)",
                "Gravadora: Third Label (CAT-03)",
                "Formato: Reissue · Digipak"),
            footer.additionalLines,
        )
        assertEquals("https://www.discogs.com/release/12345", footer.attributionUrl)
        assertTrue(nativeAlbumEditorialFooter(edition(), 1_100L)!!.additionalLines.isEmpty())
    }

    @Test fun expiringOnlyEditorialContentMustRemoveItsEntireAlbumFooterContainer() {
        val source = edition()
        val metadata = CanonicalAlbumFooterMetadata(
            date = null, summary = null, editorialEvidence = source,
        )
        val valid = nativeAlbumEditorialFooter(source, nowMillis = 1_100L)
        assertTrue(nativeAlbumMetadataFooterHasContent(metadata, valid))
        // When the observation expires on an already-open screen, nothing
        // (including empty wrapper padding) may remain.
        assertFalse(nativeAlbumMetadataFooterHasContent(metadata, null))
        assertTrue(nativeAlbumMetadataFooterHasContent(
            metadata.copy(date = "2024"), null,
        ))
        assertTrue(nativeAlbumMetadataFooterHasContent(
            metadata.copy(summary = "5 músicas"), null,
        ))
    }

    @Test fun everyAlbumHasUsefulEditionOverviewEvenWhenDiscogsHasNoLink() {
        val local = CanonicalAlbumFooterMetadata(
            date = "2024", summary = "4 músicas", showAlbumAbout = true,
        )
        val overview = nativeAlbumAboutOverview(local, null, null)!!
        assertEquals("Sobre esta edição", overview.title)
        assertTrue(overview.facts.contains("Data: 2024"))
        assertTrue(overview.facts.contains("4 músicas"))
        assertNull(overview.status)
        val withoutLink = nativeAlbumAboutOverview(
            local.copy(
                editionFacts = listOf("Formato: CD", "Gravadora: Belift Lab"),
                hasMusicBrainzEdition = true,
                editorialEvidence = DiscogsEditionEnrichment.NoLink,
            ),
            null,
            DiscogsEditionEnrichment.NoLink,
        )!!
        assertTrue(withoutLink.facts.contains("Formato: CD"))
        assertTrue(withoutLink.facts.contains("Gravadora: Belift Lab"))
        assertNull(withoutLink.status)
        val candidate = nativeAlbumAboutOverview(
            local, null, DiscogsEditionEnrichment.Candidate,
        )!!
        assertTrue(candidate.status!!.contains("evidências suficientes"))
        assertFalse(candidate.status.contains("vínculo encontrado"))
        val unavailable = nativeAlbumAboutOverview(
            local, null, DiscogsEditionEnrichment.Unavailable,
        )!!
        assertFalse(unavailable.status!!.contains("serviço temporariamente"))

    }

    @Test fun unverifiableProviderFactsNeverAppearAsVerifiedEditionDetails() {
        val metadata = CanonicalAlbumFooterMetadata(
            date = null, summary = null, showAlbumAbout = true,
            hasMusicBrainzEdition = true,
        )
        for (state in listOf(
            DiscogsEditionEnrichment.Candidate,
            DiscogsEditionEnrichment.Conflict,
            DiscogsEditionEnrichment.Ambiguous,
            DiscogsEditionEnrichment.Unavailable,
        )) {
            val overview = nativeAlbumAboutOverview(metadata, null, state)!!
            assertTrue(overview.facts.isEmpty())
            if (state == DiscogsEditionEnrichment.Unavailable) {
                assertTrue(overview.status?.startsWith(
                    "Informações complementares temporariamente indisponíveis",
                ) == true)
            } else {
                assertTrue(overview.status?.startsWith("Discogs ·") == true)
            }
            assertTrue(nativeAlbumMetadataFooterHasContent(metadata, null))
        }
    }

    @Test fun verifiedDiscogsKeepsRealAttributesAndAttributionInTheSameOverview() {
        val evidence = edition()
        val metadata = CanonicalAlbumFooterMetadata(
            date = null, summary = null, showAlbumAbout = true,
            editorialEvidence = evidence,
            hasMusicBrainzEdition = true,
        )
        val editorial = nativeAlbumEditorialFooter(evidence, 1_100L)
        val about = nativeAlbumAboutOverview(metadata, editorial, evidence)!!
        assertNull(about.status)
        assertTrue(nativeAlbumMetadataFooterHasContent(metadata, editorial))
        assertEquals("Data provided by Discogs.", editorial!!.attributionText)
    }

    @Test fun noUsefulEditionMetadataMeansNoEditorialFooter() {
        val empty = DiscogsEditionEnrichment.Verified(
            DiscogsObservation(
                mbid, DiscogsKind.RELEASE,
                DiscogsRecord(
                    link = link, title = "Album", artistNames = listOf("Artist"),
                    country = null, year = null, labels = emptyList(),
                    barcodes = emptyList(), formats = emptyList(),
                    tracklist = emptyList(), unofficial = false,
                ), fetchedAtMillis = 1_000L,
            ),
        )
        assertNull(nativeAlbumEditorialFooter(empty, 1_100L))
    }
}
