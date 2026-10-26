package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.canonicalNativeBrowseId
import com.music.bitchord.ui.canonicalNativePageType
import com.music.bitchord.ui.canonicalNativeSections
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalNativeNavigationTest {
    private val artist = CanonicalCatalogSearchRow("artist-id", CanonicalSearchKind.ARTIST, "ILLIT", null)
    private val group = CanonicalCatalogSearchRow("group-id", CanonicalSearchKind.RELEASE_GROUP,
        "SUPER REAL ME", "ILLIT", releaseKind = "EP")
    private val edition = CanonicalCatalogSearchRow("release-id", CanonicalSearchKind.RELEASE,
        "SUPER REAL ME", "ILLIT")

    @Test fun artistAndAlbumKeepNativeDetailTypesWithoutLegacyVideoIds() {
        assertEquals(BrowseType.ARTIST, canonicalNativePageType(artist.kind))
        assertEquals(BrowseType.ALBUM, canonicalNativePageType(group.kind))
        assertEquals(BrowseType.ALBUM, canonicalNativePageType(edition.kind))
        assertNull(canonicalNativePageType(CanonicalSearchKind.RECORDING))
    }

    @Test fun artistDiscographyUsesNativeShelvesWithIdentitySafeNavigation() {
        val sections = canonicalNativeSections(
            parentKind = CanonicalSearchKind.ARTIST,
            rows = listOf(group),
            heading = "Lançamentos",
        )
        assertEquals(1, sections.size)
        assertEquals("Lançamentos", sections.single().title)
        val card = sections.single().items.single()
        assertEquals(canonicalNativeBrowseId(group), card.browseId)
        assertNull(card.videoId)
        assertEquals("EP", card.subtitle)
    }

    @Test fun editionsStayDistinctAndNeverPretendToBePlayableTracks() {
        val alternate = edition.copy(musicBrainzId = "japan-release-id")
        val sections = canonicalNativeSections(
            parentKind = CanonicalSearchKind.RELEASE_GROUP,
            rows = listOf(edition, alternate),
            heading = "Edições",
        )
        assertEquals(2, sections.single().items.size)
        assertEquals(2, sections.single().items.map { it.browseId }.distinct().size)
        assertTrue(sections.single().items.all { it.videoId == null })
    }
}
