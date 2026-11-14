package com.music.bitchord

import com.music.bitchord.data.canvas.exactAppleArtistPageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A portrait belongs to one exact Apple artist, never a name match. */
class AppleArtistStorePageIdentityTest {
    @Test fun acceptsAnExactStorefrontArtistPageWithoutTrackingQuery() {
        assertEquals(
            "https://music.apple.com/br/artist/newjeans/1656329288",
            exactAppleArtistPageUrl(
                "itunes:br:artist:1656329288",
                "https://music.apple.com/br/artist/newjeans/1656329288?uo=4&at=affiliate",
            ),
        )
    }

    @Test fun namesakesDoNotBorrowAnotherAppleArtistPortrait() {
        assertNull(exactAppleArtistPageUrl(
            "itunes:br:artist:1656329288",
            "https://music.apple.com/br/artist/newjeans/123456",
        ))
        assertNull(exactAppleArtistPageUrl(
            "itunes:br:artist:1656329288",
            "https://music.apple.com/us/artist/newjeans/1656329288",
        ))
    }

    @Test fun rejectsExternalRedirectsUnsafeUrisAndNonArtists() {
        listOf(
            "https://music.apple.com.attacker.example/br/artist/newjeans/1656329288",
            "http://music.apple.com/br/artist/newjeans/1656329288",
            "https://music.apple.com:444/br/artist/newjeans/1656329288",
            "https://music.apple.com/br/album/newjeans/1656329288",
            "https://music.apple.com/br/artist/newjeans/1656329288/extra",
            "https://user@music.apple.com/br/artist/newjeans/1656329288",
        ).forEach { assertNull(exactAppleArtistPageUrl("itunes:br:artist:1656329288", it)) }
        assertNull(exactAppleArtistPageUrl(
            "itunes:br:album:1656329288",
            "https://music.apple.com/br/artist/newjeans/1656329288",
        ))
        assertNull(exactAppleArtistPageUrl("itunes:br:artist:1656329288", null))
    }
}
