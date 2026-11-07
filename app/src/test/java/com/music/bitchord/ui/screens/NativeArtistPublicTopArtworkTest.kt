package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeArtistPublicTopArtworkTest {
    @Test fun topSongsReuseOnlyVerifiedReleaseIdentityAndDeduplicateCoverRequests() {
        val edition = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val known = CanonicalCatalogSearchRow(
            musicBrainzId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            kind = CanonicalSearchKind.RECORDING,
            title = "Song A", subtitle = "Artist",
            coverReleaseMbid = edition,
        )
        val another = known.copy(musicBrainzId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val noProof = known.copy(
            musicBrainzId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            coverReleaseMbid = null,
        )
        val hasArt = known.copy(artworkUri = "https://cdn.example/image.jpg")
        assertEquals(
            listOf(edition),
            nativePublicTopCoverReleaseIds(listOf(known, another, noProof, hasArt)),
        )
    }
}
