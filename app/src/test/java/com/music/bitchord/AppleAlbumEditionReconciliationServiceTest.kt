package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleAlbumEditionReconciliationServiceTest {
    private val releaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupId = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val trackId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val recordingId = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"

    private fun apple(title: String = "SUPER REAL ME",
        upc: String? = "8801234567890", trackCount: Int = 1,
    ): ItunesEditorialRelease = ItunesEditorialCatalog.assemble("br", listOf(
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = 101, storefront = "br",
            title = title, artistName = "ILLIT", artistId = 99, collectionId = 101,
            storeUrl = null, releaseDate = "2024-03-25", genre = "K-Pop",
            durationMs = null, trackNumber = null, discNumber = null,
            artworkUrl = "https://is1-ssl.mzstatic.com/apple.jpg",
            upc = upc, trackCount = trackCount,
        ),
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK, id = 201, storefront = "br",
            title = "Magnetic", artistName = "ILLIT", artistId = 99, collectionId = 101,
            storeUrl = null, releaseDate = null, genre = null,
            durationMs = 120_000, trackNumber = 1, discNumber = 1,
        ),
    )).single()

    private fun mb(title: String = "SUPER REAL ME", barcode: String = "8801234567890") =
        MusicBrainzCatalogReleaseDetail(
            musicBrainzId = releaseId, groupMbid = groupId,
            title = title, artistDisplay = "ILLIT", country = "KR",
            releaseDate = "2024-03-25", barcode = barcode,
            tracks = listOf(MusicBrainzCatalogTrack(
                trackId, recordingId, "Magnetic", 1, 1, 120_000,
            )), status = "Official", artistCreditNames = listOf("ILLIT"),
            hasCompleteTracklist = true,
            completeness = MusicBrainzReleaseJsonCompleteness(
                countsExplicitAndExact = true, offsetsValid = true,
                positionsExplicitUnique = true,
            ),
        )

    private fun group(type: String = "EP") = MusicBrainzReleaseGroupHit(
        musicBrainzId = groupId, title = "SUPER REAL ME", primaryType = type,
        firstReleaseDate = "2024-03-25", artistDisplay = "ILLIT",
    )

    @Test fun exactRuntimeProofClassifiesWithoutReplacingAnyAppleProductFields() = runBlocking {
        val initial = apple()
        val service = AppleAlbumEditionReconciliationService(
            source = AppleAlbumEditionEvidenceSource { AppleAlbumEditionEvidence(
                editions = listOf(mb()), groups = listOf(group()),
            ) },
        )
        val proof = service.resolve(initial)
        assertNotNull(proof)
        assertEquals(ItunesEditorialReleaseFormat.EP, proof?.classification?.format)
        assertEquals(AppleEditorialFormatStatus.DECIDED, proof?.classification?.status)
        assertEquals(releaseId, proof?.release?.musicBrainzReleaseId)
        assertEquals(groupId, proof?.release?.musicBrainzReleaseGroupId)
        assertEquals(mapOf("itunes:br:track:201" to recordingId),
            proof?.release?.musicBrainzRecordingIds)
        assertEquals(initial.title, proof?.release?.title)
        assertEquals(initial.artistId, proof?.release?.artistId)
        assertEquals(initial.appleScopedId, proof?.release?.appleScopedId)
        assertEquals(initial.tracks, proof?.release?.tracks)
        assertEquals(initial.artworkUrl, proof?.release?.artworkUrl)
        assertEquals(initial.upc, proof?.release?.upc)
    }

    @Test fun wrongEditionAndIncompleteTracklistNeverAttachMbids() = runBlocking {
        var requested = 0
        val service = AppleAlbumEditionReconciliationService(
            source = AppleAlbumEditionEvidenceSource {
                requested++
                AppleAlbumEditionEvidence(listOf(mb(barcode = "1111111111111")),
                    listOf(group()))
            },
        )
        assertNull(service.resolve(apple()))
        assertEquals(1, requested)
        assertNull(service.resolve(apple(trackCount = 3)))
        assertNull(service.resolve(apple(upc = null)))
        assertEquals(1, requested) // Incomplete and missing UPC fail before network.
    }

    @Test fun explicitAppleSingleRemainsSingleWhenMbGroupClaimsEp() = runBlocking {
        val service = AppleAlbumEditionReconciliationService(
            source = AppleAlbumEditionEvidenceSource {
                AppleAlbumEditionEvidence(
                    editions = listOf(mb()), groups = listOf(group("EP")),
                )
            },
        )
        val result = service.resolve(apple(title = "SUPER REAL ME - Single"))
        assertEquals(ItunesEditorialReleaseFormat.SINGLE, result?.classification?.format)
        assertEquals(setOf(ItunesEditorialReleaseFormat.EP),
            result?.classification?.disputedExternalFormats)
    }

    @Test fun foreignOrContradictoryAppleIdentityCannotBeReassembledAsValidEdition() = runBlocking {
        var called = false
        val service = AppleAlbumEditionReconciliationService(
            source = AppleAlbumEditionEvidenceSource {
                called = true
                AppleAlbumEditionEvidence(listOf(mb()), listOf(group()))
            },
        )
        assertNull(service.resolve(apple().copy(
            appleScopedId = "itunes:us:album:101",
        )))
        assertNull(service.resolve(apple().copy(
            tracks = apple().tracks + apple().tracks.first().copy(title = "Contradictory"),
        )))
        assertFalse(called)
    }
}
