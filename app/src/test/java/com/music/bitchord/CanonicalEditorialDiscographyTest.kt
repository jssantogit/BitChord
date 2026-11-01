package com.music.bitchord

import com.music.bitchord.ui.CanonicalEditorialDiscography
import com.music.bitchord.ui.CanonicalEditorialShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEditorialDiscographyTest {
    @Test fun officialFormatComesFromPrimaryTypeNotTrackCountOrName() {
        val remixAlbum = CanonicalEditorialDiscography.classify("Album", "Remix")
        assertEquals(CanonicalEditorialShelf.ALBUMS, remixAlbum.shelf)
        assertEquals("Album", remixAlbum.format)
        assertEquals(listOf("Remix"), remixAlbum.secondaryTypes)
        assertEquals(CanonicalEditorialShelf.SINGLES_EPS,
            CanonicalEditorialDiscography.classify("Single", null).shelf)
        assertEquals(CanonicalEditorialShelf.SINGLES_EPS,
            CanonicalEditorialDiscography.classify("EP", null).shelf)
        val unknown = CanonicalEditorialDiscography.classify("Other", null)
        assertEquals(CanonicalEditorialShelf.UNCLASSIFIED, unknown.shelf)
        assertNull(unknown.format)
        assertEquals(CanonicalEditorialShelf.UNCLASSIFIED,
            CanonicalEditorialDiscography.classify(null, "Soundtrack").shelf)
    }

    @Test fun secondaryTypesDoNotInventAlbumsAndPreserveMultipleRealQualifications() {
        val compilation = CanonicalEditorialDiscography.classify(
            "Album", "Compilation|Live|Soundtrack|Compilation")
        assertEquals(CanonicalEditorialShelf.ALBUMS, compilation.shelf)
        assertEquals(listOf("Compilation", "Live", "Soundtrack"), compilation.secondaryTypes)
        assertEquals(CanonicalEditorialShelf.UNCLASSIFIED,
            CanonicalEditorialDiscography.classify("Broadcast", "Live").shelf)
        assertTrue(CanonicalEditorialDiscography.classify("EP", "Remix").isPublishable)
        assertEquals(listOf("Remix"),
            CanonicalEditorialDiscography.classify("EP", "Remix|Bogus").secondaryTypes)
    }
}
