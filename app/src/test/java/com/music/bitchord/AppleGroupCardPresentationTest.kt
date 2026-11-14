package com.music.bitchord

import com.music.bitchord.ui.screens.appleGroupCardBadgeFor
import org.junit.Assert.*
import org.junit.Test

class AppleGroupCardPresentationTest {
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val url = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
    private val store = "https://music.apple.com/br/album/101"
    private val card = "catalog:RELEASE_GROUP:$group"

    @Test fun onlyExactVisibleCardArtGetsItsOwnAppleStoreBadge() {
        val source = mapOf(group to (url to store))
        val allowed = appleGroupCardBadgeFor(card, url, source)
        assertEquals(url, allowed?.artworkUrl)
        assertEquals(store, allowed?.storeUrl)
        assertEquals(
            "https://www.apple.com/ca/itunes/link/images/link-badge-music.png",
            allowed?.badgeUrl,
        )
        assertNull(appleGroupCardBadgeFor(card, null, source))
        assertNull(appleGroupCardBadgeFor(card,
            "https://coverartarchive.org/release/a/front-500", source))
        assertNull(appleGroupCardBadgeFor(
            "catalog:RELEASE:541adf5a-48d4-4f7b-a7bb-afd3cea5af27", url, source))
        assertNull(appleGroupCardBadgeFor("catalog:RELEASE_GROUP:bad", url, source))
        assertNull(appleGroupCardBadgeFor(card, url, emptyMap()))
    }

    @Test fun forgedOrExternalArtworkCannotAcquireApplePromoBadge() {
        assertNull(appleGroupCardBadgeFor(card, url,
            mapOf(group to (url to "https://music.apple.com.attacker.test/br/album/101"))))
        val malicious = "https://attacker.test/cover.jpg"
        assertNull(appleGroupCardBadgeFor(card, malicious,
            mapOf(group to (malicious to store))))
    }
}
