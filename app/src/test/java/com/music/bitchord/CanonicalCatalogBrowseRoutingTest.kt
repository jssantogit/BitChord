package com.music.bitchord

import com.music.bitchord.ui.canonicalRowFromBrowseTarget
import com.music.bitchord.ui.legacyBrowsePlaybackAllowed
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCatalogBrowseRoutingTest {
    private val group = "9750354e-78df-4058-8758-b5a7a2eccf66"

    @Test fun typedCatalogAlbumOpensAsReleaseGroupWithoutYouTubeOrFakePlayback() {
        val result = canonicalRowFromBrowseTarget(
            "catalog:RELEASE_GROUP:$group", "How Sweet", "NewJeans", "https://example.org/cover.jpg",
        )!!
        assertEquals(group, result.musicBrainzId)
        assertEquals(CanonicalSearchKind.RELEASE_GROUP, result.kind)
        assertEquals("How Sweet", result.title)
        assertEquals("https://example.org/cover.jpg", result.artworkUri)
        assertNull(result.releaseKind)
        assertFalse(legacyBrowsePlaybackAllowed("catalog:RELEASE_GROUP:$group"))
        assertFalse(legacyBrowsePlaybackAllowed("catalog:ARTIST:$group"))
        assertFalse(legacyBrowsePlaybackAllowed("catalog:RELEASE:$group"))
        assertTrue(legacyBrowsePlaybackAllowed("local:album:abc"))
    }

    @Test fun albumMenuCannotInventCatalogPageForUnknownOrMalformedIdentity() {
        assertNull(canonicalRowFromBrowseTarget("catalog:GENRE:pop", "Pop", "", null))
        assertNull(canonicalRowFromBrowseTarget("catalog:RELEASE_GROUP:wrong", "Album", "", null))
        assertNull(canonicalRowFromBrowseTarget("catalog:RELEASE_GROUP:$group", "  ", "", null))
        assertNull(canonicalRowFromBrowseTarget("MPREb123", "Album", "", null))
    }
}
