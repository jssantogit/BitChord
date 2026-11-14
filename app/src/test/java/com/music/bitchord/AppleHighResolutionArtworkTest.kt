package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleArtworkDimensionSource
import com.music.bitchord.data.catalog.metadata.AppleHighResolutionArtwork
import com.music.bitchord.data.catalog.metadata.appleHighResolutionArtworkCandidate
import com.music.bitchord.data.catalog.metadata.AppleVerifiedEditionArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class AppleHighResolutionArtworkTest {
    private val thumb =
        "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/ab/cd/source.jpg/100x100bb.jpg"
    private val large =
        "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/ab/cd/source.jpg/720x720bb.jpg"

    @Test fun derivesOnlyExactAppleCdnImageVariantWithoutChangingSourceIdentity() {
        assertEquals(large, appleHighResolutionArtworkCandidate(thumb, 720))
        assertEquals(
            "https://a4.mzstatic.com/us/r1000/038/Music/album.600x600-75.jpg",
            appleHighResolutionArtworkCandidate(
                "https://a4.mzstatic.com/us/r1000/038/Music/album.100x100-75.jpg",
                600,
            ),
        )
        assertNull(appleHighResolutionArtworkCandidate(large, 600))
        assertNull(appleHighResolutionArtworkCandidate(thumb, 5000))
    }

    @Test fun rejectsAllUntrustedSizeRewritesAndMalformedAppleHosts() {
        for (untrusted in listOf(
            thumb.replace("is1-ssl.mzstatic.com", "is1-ssl.mzstatic.com.evil.example"),
            thumb.replace("https://", "http://"),
            thumb.replace("https://", "https://user@"),
            thumb.replace("/100x100bb.jpg", "/100x100bb.jpg?auth=123"),
            thumb.replace("/100x100bb.jpg", "/100x100bb.jpg#frag"),
            thumb.replace("/100x100bb.jpg", "/100x200bb.jpg"),
            thumb.replace("/100x100bb.jpg", "/100x100bb.svg"),
            "https://a.mzstatic.com/image/thumb/album/100x100bb.jpg",
            "https://example.com/image/thumb/album/100x100bb.jpg",
        )) assertNull(appleHighResolutionArtworkCandidate(untrusted, 720))
    }

    @Test fun verifiedDimensionsPromoteHighResolutionWithoutChangingProductLink() = runBlocking {
        var measured = 0
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource { url ->
                assertEquals(large, url)
                measured++
                720 to 720
            },
        )
        val proof = AppleVerifiedEditionArtwork(
            musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            musicBrainzReleaseGroupId = "529f87a1-1081-48a4-8ee1-3c08de229858",
            appleScopedId = "itunes:br:album:101", artworkUrl = thumb,
            storeUrl = "https://music.apple.com/br/album/101",
        )
        assertEquals(proof.copy(artworkUrl = large), service.upgrade(proof, 720))
        assertEquals(proof, proof.copy(artworkUrl = thumb))
        assertEquals(proof.copy(artworkUrl = large), service.upgrade(proof, 720))
        assertEquals(1, measured)
    }

    @Test fun brokenTooSmallOrNonSquareResponseRetainsOriginalAppleThumbnail() = runBlocking {
        val invalid = listOf<Pair<Int, Int>?>(null, 100 to 100, 720 to 480, 0 to 0)
        for (dimensions in invalid) {
            val service = AppleHighResolutionArtwork(
                dimensions = AppleArtworkDimensionSource { dimensions },
            )
            assertEquals(thumb, service.upgrade(thumb, 720))
        }
    }

    @Test fun usesVerified600PxWhen720PxVariantDoesNotExist() = runBlocking {
        val fallback600 = thumb.replace("100x100bb.jpg", "600x600bb.jpg")
        val asked = mutableListOf<String>()
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource { url ->
                asked += url
                if (url == fallback600) 600 to 600 else null
            },
        )
        assertEquals(fallback600, service.upgrade(thumb, 720))
        assertEquals(
            listOf(large, fallback600), asked,
        )
    }

    @Test fun valid1024HeroHasPriorityOverAllSmallerVariants() = runBlocking {
        val hero1024 = thumb.replace("100x100bb.jpg", "1024x1024bb.jpg")
        assertEquals(hero1024, appleHighResolutionArtworkCandidate(thumb, 1024))
        var requests = 0
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource {
                assertEquals(hero1024, it)
                requests++
                1024 to 1024
            },
        )
        assertEquals(hero1024, service.upgrade(thumb, 1024))
        assertEquals(hero1024, service.upgrade(thumb, 1024))
        assertEquals(1, requests)
    }

    @Test fun hero1024FallsBackToVerified720WithoutMakingFourSlowRequests() = runBlocking {
        val hero1024 = thumb.replace("100x100bb.jpg", "1024x1024bb.jpg")
        val asked = mutableListOf<String>()
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource { url ->
                asked += url
                if (url == large) 720 to 720 else null
            },
        )
        assertEquals(large, service.upgrade(thumb, 1024))
        assertEquals(listOf(hero1024, large), asked)
    }

    @Test fun alreadyLargeArtworkNeverDowngradesAfterFailedBiggerProbe() = runBlocking {
        val hero1024 = thumb.replace("100x100bb.jpg", "1024x1024bb.jpg")
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource { null },
        )
        assertEquals(large, service.upgrade(large, 1024))
        assertEquals(hero1024, appleHighResolutionArtworkCandidate(large, 1024))
    }

    @Test fun concurrentRequestsForSameArtworkShareOneNetworkProbe() = runBlocking {
        var called = 0
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource {
                called++
                delay(40)
                1024 to 1024
            },
        )
        val a = async { service.upgrade(thumb, 1024) }
        val b = async { service.upgrade(thumb, 1024) }
        val hero = thumb.replace("100x100bb.jpg", "1024x1024bb.jpg")
        assertEquals(hero, a.await())
        assertEquals(hero, b.await())
        assertEquals(1, called)
    }

    @Test fun cancellationIsPropagatedWithoutCachingFailure() = runBlocking {
        var calls = 0
        val service = AppleHighResolutionArtwork(
            dimensions = AppleArtworkDimensionSource {
                calls++
                if (calls == 1) throw CancellationException("screen closed")
                720 to 720
            },
        )
        try {
            service.upgrade(thumb, 720)
            fail("Cancel should abort probe")
        } catch (_: CancellationException) { }
        assertEquals(large, service.upgrade(thumb, 720))
        assertEquals(2, calls)
    }
}
