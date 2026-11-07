package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeArtistSimilarArtworkTest {
    @Test fun portraitLookupIsBoundedAndDeduplicatedByVerifiedArtistMbid() {
        val artists = (1..12).map { n ->
            ListenBrainzSimilarArtist(
                n.toString(16).padStart(8, '0') + "-1111-4111-8111-111111111111",
                "Artist $n",
            )
        }
        val targets = nativeArtistSimilarPhotoTargets(
            listOf(artists.first(), artists.first()) + artists,
        )
        assertEquals(8, targets.size)
        assertEquals(8, targets.map { it.mbid }.distinct().size)
        assertEquals(artists.first().mbid, targets.first().mbid)
    }

    @Test fun similarPortraitUriRequiresActualAppleArtworkHost() {
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/test.jpg",
            nativeArtistSimilarPortraitUri(
                "https://is1-ssl.mzstatic.com/image/thumb/test.jpg",
            ))
        assertNull(nativeArtistSimilarPortraitUri("http://is1-ssl.mzstatic.com/image.png"))
        assertNull(nativeArtistSimilarPortraitUri("https://mzstatic.com.evil.example/image.png"))
        assertNull(nativeArtistSimilarPortraitUri("https://example.com/artist.png"))
    }
}
