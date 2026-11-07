package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAlbumEditionMetadataFormattingTest {
    @Test fun countryCodesAreHumanReadableRatherThanRawIdentifiers() {
        assertEquals("País: Mundial", nativeAlbumEditionDisplayFact("País da edição · XW"))
        assertEquals("País: Coreia do Sul", nativeAlbumEditionDisplayFact("País da edição · KR"))
        assertEquals("País: Brasil", nativeAlbumEditionDisplayFact("País da edição · BR"))
        assertEquals("País: Europa", nativeAlbumEditionDisplayFact("País da edição · XE"))
        assertEquals("País: Coreia do Sul", nativeAlbumEditionDisplayFact("País da edição · South Korea"))
        assertEquals("País: Estados Unidos", nativeAlbumEditionDisplayFact("País da edição · United States"))
        assertEquals("País: Mundial", nativeAlbumEditionDisplayFact("País da edição · Worldwide"))
        assertNull(nativeAlbumEditionDisplayFact("País da edição · ZZ"))
    }

    @Test fun conciseMetadataUsesLabelsDatesAndKnownFormatNames() {
        assertEquals("Gravadora: BELIFT LAB", nativeAlbumEditionDisplayFact("Gravadora · BELIFT LAB"))
        assertEquals("Formato: Mídia digital", nativeAlbumEditionDisplayFact("Formato · Digital Media"))
        assertEquals("Estado da publicação: Oficial",
            nativeAlbumEditionDisplayFact("Estado da publicação · Oficial"))
        assertEquals("Data: 26 de outubro de 2026",
            nativeAlbumEditionDisplayFact("Data da edição · 2026-10-26"))
    }

    @Test fun releaseSpecificDateReplacesRatherThanDuplicatesGenericYear() {
        val metadata = CanonicalAlbumFooterMetadata(
            date = "2026",
            summary = "4 músicas, 11 minutos",
            hasMusicBrainzEdition = true,
            showAlbumAbout = true,
            editionFacts = listOf(
                "Data da edição · 2026-10-26",
                "País da edição · XW",
                "Gravadora · BELIFT LAB",
            ),
        )
        val overview = nativeAlbumAboutOverview(metadata, null, DiscogsEditionEnrichment.NoLink)!!
        assertEquals(1, overview.facts.count { it.startsWith("Data:") })
        assertTrue(overview.facts.contains("Data: 26 de outubro de 2026"))
        assertEquals("4 músicas, 11 minutos", overview.facts.first())
        assertNull(overview.status)
    }

    @Test fun optionalEditionDisplayCanBeHiddenWithoutRemovingStandardAlbumSummary() {
        val onlyEdition = CanonicalAlbumFooterMetadata(
            date = null, summary = null, showAlbumAbout = true,
            editionFacts = listOf("Gravadora · BELIFT LAB"),
        )
        assertFalse(nativeAlbumMetadataFooterHasContent(
            onlyEdition, null, showAlbumEditionMetadata = false,
        ))
        assertTrue(nativeAlbumMetadataFooterHasContent(
            onlyEdition.copy(summary = "4 músicas"), null,
            showAlbumEditionMetadata = false,
        ))
    }
}
