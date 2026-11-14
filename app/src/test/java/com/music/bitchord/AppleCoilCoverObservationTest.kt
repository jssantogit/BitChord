package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.shouldObserveAppleImageRequest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class AppleCoilCoverObservationTest {
    @Test fun observesActualAppleImageRequestsNotForgeableHostsOrStoreBadges() {
        val image =
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/a.jpg/1024x1024bb.jpg"
        assertTrue(shouldObserveAppleImageRequest(image))
        assertTrue(shouldObserveAppleImageRequest(URI(image)))
        assertFalse(shouldObserveAppleImageRequest(
            image.replace("is1-ssl.mzstatic.com", "is1-ssl.mzstatic.com.evil.example")))
        assertFalse(shouldObserveAppleImageRequest(image.replace("https:", "http:")))
        assertFalse(shouldObserveAppleImageRequest(
            "https://www.apple.com/ca/itunes/link/images/link-badge-music.png"))
        assertFalse(shouldObserveAppleImageRequest(
            "https://coverartarchive.org/release/abc/front-500"))
        assertFalse(shouldObserveAppleImageRequest(null))
    }
}
