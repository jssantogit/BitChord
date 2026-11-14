package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeArtistPublicTopArtworkTest {
    private val image =
        "https://is1-ssl.mzstatic.com/image/thumb/Music/a/600x600bb.jpg"
    private val store = "https://music.apple.com/br/album/hot/101"
    private val badge =
        "https://www.apple.com/ca/itunes/link/images/link-badge-music.png"
    private val recording = CanonicalCatalogSearchRow(
        musicBrainzId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        kind = CanonicalSearchKind.RECORDING,
        title = "Song A", subtitle = "Artist",
        coverReleaseMbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        artworkUri = image, appleStoreUrl = store, appleStoreBadgeUrl = badge,
    )

    @Test fun onlyCertifiedAppleArtworkAndMatchingStoreBadgeAreDisplayed() {
        assertEquals(image, nativePublicTopVerifiedAppleArtwork(recording))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(appleStoreUrl = null)))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(appleStoreBadgeUrl = null)))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(appleStoreBadgeUrl = "https://evil.invalid/logo.png")))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(artworkUri =
                "https://coverartarchive.org/release/abc/front-500")))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(artworkUri =
                "https://is1-ssl.mzstatic.com.evil.invalid/image.jpg")))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(appleStoreUrl =
                "https://music.apple.com.attacker.invalid/br/album/hot/101")))
    }

    @Test fun publicTopWithoutAppleProofNeverReactivatesLegacyCaaLookup() {
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(artworkUri = null)))
        assertNull(nativePublicTopVerifiedAppleArtwork(
            recording.copy(artworkUri =
                "https://ia902000.us.archive.org/12/items/mbid/front.jpg")))
    }
}
