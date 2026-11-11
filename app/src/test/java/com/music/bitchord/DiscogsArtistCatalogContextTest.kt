package com.music.bitchord

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class DiscogsArtistCatalogContextTest {
    private fun row(title: String, offline: Boolean = false) = CanonicalCatalogSearchRow(
        musicBrainzId = "00000000-0000-4000-8000-000000000001",
        kind = CanonicalSearchKind.RELEASE_GROUP,
        title = title,
        subtitle = "Artist",
        fromOfflineCache = offline,
    )

    @Test fun onlyExactCompleteOnlineArtistDiscographyMayClaimAllTitlesKnown() {
        val id = "catalog:ARTIST:00000000-0000-4000-8000-000000000002"
        val verified = discogsArtistCatalogContext(id, id,
            "00000000-0000-4000-8000-000000000002",
            listOf(row("A"), row("B")), null, 2)
        assertTrue(verified.complete)
        assertEquals(setOf("A", "B"), verified.titles)
        assertFalse(discogsArtistCatalogContext(id, id, "artist",
            listOf(row("A")), 1, 2).complete)
        assertFalse(discogsArtistCatalogContext("another", id, "artist",
            listOf(row("A")), null, 1).complete)
        assertFalse(discogsArtistCatalogContext(id, id, "artist",
            listOf(row("A", offline = true)), null, 1).complete)
    }
}
