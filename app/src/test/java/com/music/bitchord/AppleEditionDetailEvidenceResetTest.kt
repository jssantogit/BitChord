package com.music.bitchord

import com.music.bitchord.ui.appleEditionDetailWithoutExternalEvidence
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialReleaseCategory
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleEditorialTrackRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleEditionDetailEvidenceResetTest {
    private fun native(id: String = "itunes:br:album:501") =
        AppleEditorialAlbumDetailPage(
            album = AppleEditorialSearchRow(
                browseId = id, title = "Native", artistName = "Artist",
                artworkUri = "https://is1-ssl.mzstatic.com/original.jpg",
                releaseDate = "2025-01-01",
                category = AppleEditorialReleaseCategory.UNCLASSIFIED,
                appleStoreUrl = "https://music.apple.com/br/album/native/501",
            ),
            tracks = listOf(AppleEditorialTrackRow(
                "itunes:br:track:101", "Song", "Artist", 1, 1,
            )),
            partialTracklist = false,
            appleStoreUrl = "https://music.apple.com/br/album/native/501",
        )

    @Test fun revokingMusicBrainzOrDiscogsRestoresOriginalStorefrontFields() {
        val original = native()
        val enriched = original.copy(
            album = original.album.copy(
                category = AppleEditorialReleaseCategory.EP,
                artworkUri = "https://is1-ssl.mzstatic.com/verified-1024.jpg",
            ),
            discogsAttributionUrl = "https://www.discogs.com/release/45",
            discogsAttributionText = "Discogs",
            discogsNonAffiliationNotice = "No affiliation",
        )
        val reset = appleEditionDetailWithoutExternalEvidence(enriched, original)
        assertEquals(original.album.browseId, reset.album.browseId)
        assertEquals(original.album.title, reset.album.title)
        assertEquals(original.album.category, reset.album.category)
        assertEquals(original.tracks, reset.tracks)
        assertEquals(original.appleStoreUrl, reset.appleStoreUrl)
        assertEquals(enriched.album.artworkUri, reset.album.artworkUri)
        assertNull(reset.discogsAttributionUrl)
        assertNull(reset.discogsAttributionText)
        assertNull(reset.discogsNonAffiliationNotice)
    }

    @Test fun unrelatedBaselineMustNeverReplaceCurrentAppleProduct() {
        val current = native("itunes:br:album:99").copy(
            album = native("itunes:br:album:99").album.copy(
                category = AppleEditorialReleaseCategory.EP,
            ),
            discogsAttributionUrl = "https://www.discogs.com/release/22",
        )
        val reset = appleEditionDetailWithoutExternalEvidence(current, native())
        assertEquals("itunes:br:album:99", reset.album.browseId)
        assertEquals(AppleEditorialReleaseCategory.UNCLASSIFIED, reset.album.category)
        assertNull(reset.discogsAttributionUrl)
    }
}
