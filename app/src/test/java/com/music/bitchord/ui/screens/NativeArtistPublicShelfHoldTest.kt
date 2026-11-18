package com.music.bitchord.ui.screens

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Public artist shelves must route their exact catalog identity to the existing lifted menu. */
class NativeArtistPublicShelfHoldTest {
    @Test fun publicArtistShelfForwardsOriginalCatalogRow() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "artist-mbid", kind = CanonicalSearchKind.ARTIST,
            title = "Example", subtitle = "",
        )
        var forwarded: CanonicalCatalogSearchRow? = null
        requireNotNull(nativeArtistPublicShelfHoldAction(row) { forwarded = it }).invoke()
        assertSame(row, forwarded)
    }

    @Test fun missingMenuOwnerDoesNotInstallFakeHoldHandler() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "album-mbid", kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Example", subtitle = "",
        )
        assertNull(nativeArtistPublicShelfHoldAction(row, null))
    }
}
