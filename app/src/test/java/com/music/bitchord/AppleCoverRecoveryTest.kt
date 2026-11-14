package com.music.bitchord

import com.music.bitchord.ui.components.appleVerifiedCdnCoverFallbackUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleCoverRecoveryTest {
    @Test fun verified1024And720CdnRenditionsRecoverTheirOriginal100pxAsset() {
        val base = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/source.jpg/"
        assertEquals(base + "100x100bb.jpg",
            appleVerifiedCdnCoverFallbackUrl(base + "1024x1024bb.jpg"))
        assertEquals(base + "100x100bb.jpg",
            appleVerifiedCdnCoverFallbackUrl(base + "720x720bb.jpg"))
        assertEquals(base + "100x100bb.jpg",
            appleVerifiedCdnCoverFallbackUrl(base + "600x600bb.jpg"))
    }

    @Test fun legacyAppleStoreAlbumArtworkKeepsSameAssetAndFormat() {
        val base = "https://a4.mzstatic.com/us/r1000/038/Music/source.jpg"
        assertEquals(base + ".100x100-75.jpg",
            appleVerifiedCdnCoverFallbackUrl(base + ".1024x1024-75.jpg"))
    }

    @Test fun rejectUntrustedLinksAndDoNotKeepRetryingOriginalThumbnail() {
        val valid = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/1024x1024bb.jpg"
        for (url in listOf(
            valid.replace("is1-ssl.mzstatic.com", "is1-ssl.mzstatic.com.evil.example"),
            valid.replace("https", "http"),
            valid.replace("https://", "https://user@"),
            valid + "?redirect=https://other.example",
            valid + "#fragment",
            valid.replace("1024x1024", "1024x720"),
            valid.replace("1024x1024", "2000x2000"),
            valid.replace("1024x1024", "100x100"),
            "https://coverartarchive.org/release/abc/front-500",
        )) assertNull(url, appleVerifiedCdnCoverFallbackUrl(url))
        assertNull(appleVerifiedCdnCoverFallbackUrl(null))
    }
}
