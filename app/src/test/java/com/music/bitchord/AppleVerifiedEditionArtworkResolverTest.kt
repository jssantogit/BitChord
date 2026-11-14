package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppleVerifiedEditionArtworkResolverTest {
    private val id = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val other = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val barcode = "8801234567890"
    private val art = "https://is1-ssl.mzstatic.com/image/thumb/Music/a.jpg"
    private val store = "https://music.apple.com/br/album/super-real-me/101"
    private fun mb(id: String = this.id) = MusicBrainzCatalogReleaseDetail(
        musicBrainzId = id, groupMbid = group, title = "SUPER REAL ME",
        artistDisplay = "ILLIT", country = "KR", releaseDate = "2024-03-25",
        tracks = listOf(MusicBrainzCatalogTrack(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "e614e432-ca77-41c1-82c1-4e3a24ebbb0c",
            "Magnetic", 1, 1, 120_000,
        )),
        hasCompleteTracklist = true, status = "Official",
        barcode = barcode, artistCreditNames = listOf("ILLIT"),
        completeness = MusicBrainzReleaseJsonCompleteness(
            countsExplicitAndExact = true, offsetsValid = true, positionsExplicitUnique = true,
        ),
    )
    private fun response() = ItunesCatalogResult.Success(listOf(
        ItunesCatalogItem(
            ItunesCatalogItemType.ALBUM, 101, "br", "SUPER REAL ME - EP",
            "ILLIT", 99, 101, store, "2024-03-25", "K-Pop",
            null, null, null, artworkUrl = art, trackCount = 1,
        ),
        ItunesCatalogItem(
            ItunesCatalogItemType.TRACK, 201, "br", "Magnetic", "ILLIT",
            99, 101, null, null, null, 120_000, 1, 1,
        ),
    ))

    @Test fun oneExactReleaseCanResolveOnceAndReusePositiveCache() = runBlocking {
        var mbReads = 0
        var appleReads = 0
        var candidateReads = 0
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource {
                mbReads++
                mb()
            },
            upc = AppleArtworkUpcSource { code, country ->
                assertEquals(barcode, code)
                assertEquals("br", country)
                appleReads++
                response()
            },
            evidence = AppleAlbumEditionEvidenceSource {
                candidateReads++
                AppleAlbumEditionEvidence(listOf(mb()), emptyList())
            },
        )
        val first = resolver.resolve(id, "br")
        assertEquals(art, first?.artworkUrl)
        assertEquals(store, first?.storeUrl)
        assertEquals(first, resolver.resolve(id, "br"))
        assertEquals(1, mbReads)
        assertEquals(1, appleReads)
        assertEquals(1, candidateReads)
    }

    @Test fun anotherReleaseClaimingSameBarcodeCannotBorrowCertifiedAlbumArt() =
        runBlocking {
            val resolver = AppleVerifiedEditionArtworkResolver(
                edition = AppleArtworkEditionSource { mb() },
                upc = AppleArtworkUpcSource { _, _ -> response() },
                evidence = AppleAlbumEditionEvidenceSource {
                    AppleAlbumEditionEvidence(listOf(mb(id), mb(other)), emptyList())
                },
            )
            assertNull(resolver.resolve(id, "br"))
        }

    @Test fun unrelatedReleaseCoverLookupsMustNeverWaitForOneAnother() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource { release ->
                if (release == id) {
                    firstStarted.complete(Unit)
                    secondStarted.await()
                } else {
                    secondStarted.complete(Unit)
                    firstStarted.await()
                }
                // No UPC: each lookup stops after its independent edition read.
                mb(release).copy(barcode = null)
            },
            upc = AppleArtworkUpcSource { _, _ ->
                error("no UPC is available")
            },
        )
        withTimeout(2_000L) {
            val first = async { resolver.resolve(id) }
            val second = async { resolver.resolve(other) }
            assertNull(first.await())
            assertNull(second.await())
        }
    }

    @Test fun concurrentRequestsForOneReleaseReuseTheSameProofWork() = runBlocking {
        var reads = 0
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource {
                reads++
                delay(40)
                mb().copy(barcode = null)
            },
            upc = AppleArtworkUpcSource { _, _ ->
                error("no UPC is available")
            },
        )
        val a = async { resolver.resolve(id) }
        val b = async { resolver.resolve(id) }
        assertNull(a.await())
        assertNull(b.await())
        assertEquals(1, reads)
    }

    @Test fun explicitMusicBrainzReleaseAppleAlbumLinkProvesNoBarcodeEdition() = runBlocking {
        var directReads = 0
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource {
                mb().copy(barcode = null, linkedAppleAlbumIds = listOf(101L))
            },
            upc = AppleArtworkUpcSource { _, _ ->
                error("barcode lookup cannot run without a barcode")
            },
            linked = AppleArtworkLinkedAlbumSource { appleId, country ->
                assertEquals(101L, appleId)
                assertEquals("br", country)
                directReads++
                response()
            },
        )
        val verified = resolver.resolve(id, "br")
        assertEquals(art, verified?.artworkUrl)
        assertEquals(store, verified?.storeUrl)
        assertEquals("itunes:br:album:101", verified?.appleScopedId)
        assertEquals(verified, resolver.resolve(id, "br"))
        assertEquals(1, directReads)
    }

    @Test fun linkedAppleIdNeverBorrowedFromAnotherProductOrTracklist() = runBlocking {
        for (appleResponse in listOf(
            response().copy(items = response().items.map { item ->
                if (item.type == ItunesCatalogItemType.ALBUM)
                    item.copy(id = 202, collectionId = 202)
                else item
            }),
            response().copy(items = response().items.map { item ->
                if (item.type == ItunesCatalogItemType.TRACK)
                    item.copy(title = "Different Song")
                else item
            }),
            response().copy(possiblyTruncated = true),
        )) {
            val resolver = AppleVerifiedEditionArtworkResolver(
                edition = AppleArtworkEditionSource {
                    mb().copy(barcode = null, linkedAppleAlbumIds = listOf(101L))
                },
                upc = AppleArtworkUpcSource { _, _ -> error("no UPC") },
                linked = AppleArtworkLinkedAlbumSource { _, _ -> appleResponse },
            )
            assertNull(resolver.resolve(id, "br"))
        }
    }

    @Test fun competingExplicitAppleLinksAreInconclusiveNotFirstResultWins() = runBlocking {
        var directCalls = 0
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource {
                mb().copy(barcode = null, linkedAppleAlbumIds = listOf(101L, 102L))
            },
            upc = AppleArtworkUpcSource { _, _ -> error("no UPC") },
            linked = AppleArtworkLinkedAlbumSource { _, _ ->
                directCalls++
                response()
            },
        )
        assertNull(resolver.resolve(id, "br"))
        assertEquals(0, directCalls)
    }

    @Test fun failingOrMissingBarcodeNeverRequestsApple() = runBlocking {
        var calls = 0
        val resolver = AppleVerifiedEditionArtworkResolver(
            edition = AppleArtworkEditionSource { mb().copy(barcode = null) },
            upc = AppleArtworkUpcSource { _, _ ->
                calls++
                response()
            },
            evidence = AppleAlbumEditionEvidenceSource {
                error("must not enumerate candidates without UPC")
            },
        )
        assertNull(resolver.resolve(id, "br"))
        assertEquals(0, calls)
        assertNull(resolver.resolve("not-an-mbid", "br"))
    }

    @Test fun unrelatedRequestedIdentityFailsBeforeAnyAppleProofIsPublished() =
        runBlocking {
            val resolver = AppleVerifiedEditionArtworkResolver(
                edition = AppleArtworkEditionSource { mb(other) },
                upc = AppleArtworkUpcSource { _, _ -> response() },
                evidence = AppleAlbumEditionEvidenceSource {
                    AppleAlbumEditionEvidence(listOf(mb(other)), emptyList())
                },
            )
            assertNull(resolver.resolve(id, "br"))
        }
}
