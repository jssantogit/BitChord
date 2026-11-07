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
                "Gravadora · Belift Lab (BEL-001)",
                "Formato · CD · EP",
                "País da edição · KR",
                "Ano da edição · 2024",
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
        assertEquals(listOf("Ano da edição · 2024"), footer!!.lines)

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
