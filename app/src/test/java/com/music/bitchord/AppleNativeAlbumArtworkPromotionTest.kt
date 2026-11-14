package com.music.bitchord

import com.music.bitchord.ui.appleNativeAlbumMayProbeLargeCover
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeAlbumArtworkPromotionTest {
    private val url = "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/100x100bb.jpg"
    private fun detail(link: String?, art: String?) = AppleEditorialAlbumDetailPage(
        album = AppleEditorialSearchRow(
            browseId = "itunes:br:album:101",
            title = "Supernatural", artistName = "NewJeans",
            artworkUri = art, releaseDate = "2024-06-21",
            appleStoreUrl = link, appleStoreBadgeUrl = null,
        ),
        tracks = emptyList(), partialTracklist = false,
        appleStoreUrl = link, appleStoreBadgeUrl = null,
    )

    @Test fun stillProbes1024WithoutAnUnwantedAppleStoreBadge() {
        assertTrue(appleNativeAlbumMayProbeLargeCover(detail(null, url)))
    }

    @Test fun neverPromotesArbitraryOrMissingImagesAsAppleArt() {
        assertFalse(appleNativeAlbumMayProbeLargeCover(detail(null, null)))
        assertFalse(appleNativeAlbumMayProbeLargeCover(
            detail("https://music.apple.com/br/album/101", "https://evil.example/image.jpg")))
    }
}
