package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import org.junit.Assert.*
import org.junit.Test

class AppleExactEditionArtworkBridgeTest {
    private val id = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val upc = "8801234567890"
    private val art = "https://is1-ssl.mzstatic.com/image/thumb/Music/a.jpg"
    private val store = "https://music.apple.com/br/album/super-real-me/101"
    private fun track(number: Int, title: String, collectionId: Long = 101) =
        ItunesCatalogItem(
            ItunesCatalogItemType.TRACK, number.toLong() + 200, "br", title, "ILLIT",
            99, collectionId, null, null, null, 120_000L, number, 1,
        )

    private fun release(
        title: String = "SUPER REAL ME - EP", artist: String = "ILLIT",
        barcode: String? = null, trackCount: Int = 2,
        artwork: String? = art, link: String? = store,
    ) = ItunesCatalogItem(
        ItunesCatalogItemType.ALBUM, 101, "br", title, artist,
        99, 101, link, "2024-03-25", "K-Pop", null, null, null,
        artworkUrl = artwork, upc = barcode, trackCount = trackCount,
    )
    private fun apple(vararg rows: ItunesCatalogItem) =
        ItunesCatalogResult.Success(rows.toList(), possiblyTruncated = false)

    private val mb = MusicBrainzCatalogReleaseDetail(
        musicBrainzId = id,
        groupMbid = group, title = "SUPER REAL ME",
        artistDisplay = "ILLIT", country = "KR", releaseDate = "2024-03-25",
        tracks = listOf(
            MusicBrainzCatalogTrack(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "e614e432-ca77-41c1-82c1-4e3a24ebbb0c",
                "My World", 1, 1, 120_000,
            ),
            MusicBrainzCatalogTrack(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "3e619ba2-34f0-4649-83d8-05483e2fabe5",
                "Magnetic", 1, 2, 120_000,
            ),
        ),
        hasCompleteTracklist = true,
        status = "Official", barcode = upc,
        artistCreditNames = listOf("ILLIT"),
        completeness = MusicBrainzReleaseJsonCompleteness(
            countsExplicitAndExact = true, offsetsValid = true,
            positionsExplicitUnique = true,
        ),
    )

    private fun complete(
        album: ItunesCatalogItem = release(),
        rows: List<ItunesCatalogItem> = listOf(
            track(1, "My World"), track(2, "Magnetic"),
        ),
    ) = apple(album, *rows.toTypedArray())

    @Test fun exactUpcLookupCanProveAppleArtworkAndStoreLinkWithoutAppleUpcField() {
        val certified = appleVerifiedEditionArtwork(mb, complete(), "br")
        assertNotNull(certified)
        assertEquals(id, certified?.musicBrainzReleaseId)
        assertEquals(group, certified?.musicBrainzReleaseGroupId)
        assertEquals("itunes:br:album:101", certified?.appleScopedId)
        assertEquals(art, certified?.artworkUrl)
        assertEquals(store, certified?.storeUrl)
    }

    @Test fun conflictsNeverBorrowArtAcrossArtistTitleTrackOrUpc() {
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            album = release(artist = "Different Artist"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            album = release(title = "HOWSWEET & Bubblegum"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            album = release(barcode = "1111111111111"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            rows = listOf(track(1, "Magnetic"), track(2, "My World")),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            rows = listOf(track(1, "My World")),
        ), "br"))
    }

    @Test fun noProofOnAmbiguousIncompleteUnsafeOrUnattributedPromoArtwork() {
        assertNull(appleVerifiedEditionArtwork(
            mb, ItunesCatalogResult.Success(complete().items, true), "br",
        ))
        assertNull(appleVerifiedEditionArtwork(mb, apple(
            release(), release().copy(id = 102, collectionId = 102),
            track(1, "My World"), track(2, "Magnetic"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            album = release(link = "https://lookalike.music.apple.com/br/album/1"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(
            album = release(artwork = "https://attacker.example/cover.jpg"),
        ), "br"))
        assertNull(appleVerifiedEditionArtwork(mb.copy(
            hasCompleteTracklist = false,
        ), complete(), "br"))
        assertNull(appleVerifiedEditionArtwork(mb, complete(), "us"))
    }

    @Test fun sourceSelectionNeverDowngradesVerifiedCaaHeroToApple100Pixels() {
        val apple100 = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val apple600 = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/600x600bb.jpg"
        val caa500 = "https://coverartarchive.org/release/$id/front-500"
        assertEquals(apple100, bestCertifiedAppleOrArchiveCover(apple100, null))
        assertEquals(caa500, bestCertifiedAppleOrArchiveCover(apple100, caa500))
        assertEquals(apple600, bestCertifiedAppleOrArchiveCover(apple600, caa500))
        assertEquals(caa500, bestCertifiedAppleOrArchiveCover(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/a.jpg", caa500,
        ))
        assertEquals(caa500, bestCertifiedAppleOrArchiveCover(
            "https://attacker.example/artwork/600x600bb.jpg", caa500,
        ))
    }

    @Test fun applePromotionBadgeMustBeOfficialUnmodifiedAssetAndOnlyForStoreLink() {
        assertEquals(
            "https://www.apple.com/ca/itunes/link/images/link-badge-music.png",
            appleApprovedBadgeForStoreUrl(store),
        )
        assertEquals(
            "https://www.apple.com/ca/itunes/link/images/link-badge-itunes.png",
            appleApprovedBadgeForStoreUrl("https://itunes.apple.com/br/album/id101"),
        )
        assertNull(appleApprovedBadgeForStoreUrl(
            "https://music.apple.com.attacker.example/br/album/id101",
        ))
        assertNull(appleApprovedBadgeForStoreUrl(
            "http://music.apple.com/br/album/id101",
        ))
    }

    @Test fun groupOrRecordingCannotSilentlyTakeAnotherEditionArt() {
        val proof = appleVerifiedEditionArtwork(mb, complete(), "br")!!
        assertEquals(art, canonicalAppleArtworkFor(
            proof, requestedReleaseMbid = id, requestedGroupMbid = group,
        ))
        assertNull(canonicalAppleArtworkFor(
            proof, requestedReleaseMbid = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4",
            requestedGroupMbid = group,
        ))
        assertNull(canonicalAppleArtworkFor(
            proof, requestedReleaseMbid = id,
            requestedGroupMbid = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4",
        ))
    }
}
