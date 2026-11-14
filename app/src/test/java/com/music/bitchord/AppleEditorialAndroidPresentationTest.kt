package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialClassification
import com.music.bitchord.data.catalog.metadata.AppleMusicBrainzEditionMatch
import com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.AppleCatalogBrowseRoute
import com.music.bitchord.ui.AppleEditorialShelfHeadings
import com.music.bitchord.ui.appleEditorialShelves
import com.music.bitchord.ui.appleFirstHomeShelves
import com.music.bitchord.ui.appleEditorialReleasesWithProbedArtwork
import com.music.bitchord.ui.appleArtistAlbumRowsWithProbedArtwork
import com.music.bitchord.ui.appleArtworkTargetPxForShelf
import com.music.bitchord.ui.appleKeepVerifiedArtworkAcrossPaging
import com.music.bitchord.ui.appleReleasesPreservingVerifiedArtwork
import com.music.bitchord.ui.appleSearchRowsPreservingVerifiedArtwork
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialAndroidPresentationTest {
    private fun release(
        id: Long,
        title: String,
        type: ItunesEditorialReleaseFormat,
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id,
        storefront = "br",
        title = title,
        artistName = "ILLIT",
        artistId = 99,
        releaseDate = "2024-03-25",
        genre = "K-Pop",
        format = type,
        artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/cover.jpg",
        trackCount = 1,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    private fun entry(release: ItunesEditorialRelease) = AppleTriSourceEditorialEntry(
        release = release,
        match = AppleMusicBrainzEditionMatch.NoMatch,
        classification = AppleEditorialClassification.resolve(release, emptyList()),
        discogsEdition = null,
    )

    @Test fun publicSearchPaginationKeepsVerifiedArtOnlyForExactUnchangedAppleAlbum() {
        val original = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val enhanced = original.replace("100x100bb.jpg", "600x600bb.jpg")
        val earlier = AppleEditorialSearchRow(
            browseId = "itunes:br:album:101", title = "HOT",
            artistName = "LE SSERAFIM", artworkUri = enhanced,
            releaseDate = "2024-01-01",
        )
        val updated = earlier.copy(artworkUri = original, releaseDate = "2024-01-02")
        val next = earlier.copy(
            browseId = "itunes:br:album:102",
            artworkUri = "https://is1-ssl.mzstatic.com/image/thumb/Music/b/100x100bb.jpg",
        )
        val merged = appleSearchRowsPreservingVerifiedArtwork(
            listOf(updated, next), listOf(earlier),
        )
        assertEquals(enhanced, merged[0].artworkUri)
        assertEquals(updated.releaseDate, merged[0].releaseDate)
        assertEquals(next, merged[1])
        assertEquals(listOf(updated.copy(artworkUri =
            "https://is1-ssl.mzstatic.com/image/thumb/Music/reissue/100x100bb.jpg")),
            appleSearchRowsPreservingVerifiedArtwork(
                listOf(updated.copy(artworkUri =
                    "https://is1-ssl.mzstatic.com/image/thumb/Music/reissue/100x100bb.jpg")),
                listOf(earlier),
            ),
        )
    }

    @Test fun homeRefreshKeepsVerified1024ButDropsOutdatedCoverIdentity() {
        val thumb = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val hero = thumb.replace("100x100bb.jpg", "1024x1024bb.jpg")
        val old = release(101, "HOT", ItunesEditorialReleaseFormat.EP)
            .copy(artworkUrl = hero)
        val updated = release(101, "HOT", ItunesEditorialReleaseFormat.EP)
            .copy(artworkUrl = thumb, genre = "K-Pop")
        val sameNameDifferentProduct =
            release(102, "HOT", ItunesEditorialReleaseFormat.EP).copy(
                artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/Music/b/100x100bb.jpg",
            )
        val patch = appleReleasesPreservingVerifiedArtwork(
            listOf(updated, sameNameDifferentProduct), listOf(old),
        )
        assertEquals(hero, patch[0].artworkUrl)
        assertEquals(updated.genre, patch[0].genre)
        assertEquals(sameNameDifferentProduct, patch[1])
        assertEquals(listOf(updated.copy(artworkUrl =
            "https://is1-ssl.mzstatic.com/image/thumb/Music/new/100x100bb.jpg")),
            appleReleasesPreservingVerifiedArtwork(
                listOf(updated.copy(artworkUrl =
                    "https://is1-ssl.mzstatic.com/image/thumb/Music/new/100x100bb.jpg")),
                listOf(old),
            ),
        )
    }

    @Test fun artistPaginationRetains1024ArtworkButRejectsForeignEditionSleeve() {
        val original = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val high1024 = original.replace("100x100bb.jpg", "1024x1024bb.jpg")
        val high720 = original.replace("100x100bb.jpg", "720x720bb.jpg")
        val other = "https://is1-ssl.mzstatic.com/image/thumb/Music/b/1024x1024bb.jpg"
        assertEquals(high1024, appleKeepVerifiedArtworkAcrossPaging(original, high1024))
        assertEquals(high720, appleKeepVerifiedArtworkAcrossPaging(original, high720))
        assertEquals(original, appleKeepVerifiedArtworkAcrossPaging(original, other))
        assertEquals(original, appleKeepVerifiedArtworkAcrossPaging(original, null))
        assertEquals(original, appleKeepVerifiedArtworkAcrossPaging(
            original, "https://attacker.example/art.jpg",
        ))
    }

    @Test fun artworkPolicyCapsOnlyProminentCardsAt1024Pixels() {
        assertEquals(1024, appleArtworkTargetPxForShelf(0, 4))
        assertEquals(1024, appleArtworkTargetPxForShelf(3, 4))
        assertEquals(600, appleArtworkTargetPxForShelf(4, 4))
        assertEquals(600, appleArtworkTargetPxForShelf(0, 0))
        assertEquals(600, appleArtworkTargetPxForShelf(-1, 4))
        assertEquals(600, appleArtworkTargetPxForShelf(4, 100))
    }

    @Test fun artistDiscographyOnlyReplacesSameAppleAlbumIdAndOriginalImage() {
        val tiny = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val large = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/600x600bb.jpg"
        val first = AppleEditorialSearchRow(
            browseId = "itunes:br:album:101", title = "HOT", artistName = "ILLIT",
            artworkUri = tiny, releaseDate = null,
        )
        val second = first.copy(browseId = "itunes:br:album:102")
        val rows = listOf(first, second)
        val updated = appleArtistAlbumRowsWithProbedArtwork(
            rows, first.browseId, tiny, large,
        )
        assertEquals(large, updated.first().artworkUri)
        assertSame(second, updated.last())
        assertEquals(rows, appleArtistAlbumRowsWithProbedArtwork(
            rows, "itunes:br:album:102", "https://cdn.test/foreign.jpg", large,
        ))
        assertEquals(rows, appleArtistAlbumRowsWithProbedArtwork(
            rows, "catalog:RELEASE:101", tiny, large,
        ))
    }

    @Test fun verifiedLargeAppleImageOnlyPatchesTheExactProduct() {
        val tiny = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val large = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/600x600bb.jpg"
        val other = "https://is1-ssl.mzstatic.com/image/thumb/Music/b/100x100bb.jpg"
        val albums = listOf(
            release(101, "HOT", ItunesEditorialReleaseFormat.EP).copy(artworkUrl = tiny),
            release(102, "HOT", ItunesEditorialReleaseFormat.SINGLE).copy(artworkUrl = other),
        )
        val updated = appleEditorialReleasesWithProbedArtwork(
            albums, "itunes:br:album:101", tiny, large,
        )
        assertEquals(large, updated[0].artworkUrl)
        assertSame(albums[1], updated[1])
        assertEquals(albums, appleEditorialReleasesWithProbedArtwork(
            albums, "itunes:br:album:102", tiny, large,
        ))
        assertEquals(albums, appleEditorialReleasesWithProbedArtwork(
            albums, "itunes:br:album:101", tiny, "https://untrusted.test/art.jpg",
        ))
        assertEquals(albums, appleEditorialReleasesWithProbedArtwork(
            albums, "catalog:RELEASE_GROUP:101", tiny, large,
        ))
    }

    @Test fun homeShelfCardsCarryExactAppleProductBadgeAndRejectForgedLinks() {
        val realStore = "https://music.apple.com/br/album/hot/101"
        val clean = release(101, "HOT", ItunesEditorialReleaseFormat.EP)
            .copy(storeUrl = realStore)
        val forged = release(102, "HOT", ItunesEditorialReleaseFormat.SINGLE)
            .copy(storeUrl = "https://music.apple.com.attacker.example/br/album/102")
        val shelves = appleFirstHomeShelves(
            listOf(clean, forged), emptyList(), "Recentes", "Descobrir", "2026-11-14",
        )
        val cards = shelves.flatMap { it.items }
        assertEquals(2, cards.size)
        assertEquals(realStore, cards[0].appleStoreUrl)
        assertEquals(
            "https://www.apple.com/ca/itunes/link/images/link-badge-music.png",
            cards[0].appleStoreBadgeUrl,
        )
        assertNull(cards[1].appleStoreUrl)
        assertNull(cards[1].appleStoreBadgeUrl)
    }

    @Test fun typedItunesRoutesNeverMasqueradeAsMusicBrainzBrowseIds() {
        val route = AppleCatalogBrowseRoute.parse("itunes:br:album:123")
        assertNotNull(route)
        assertEquals("br", route?.storefront)
        assertEquals(123L, route?.id)
        assertEquals(ItunesCatalogItemType.ALBUM, route?.kind)
        assertNull(AppleCatalogBrowseRoute.parse("catalog:RELEASE_GROUP:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:0"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:00123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:BR:album:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:123:extra"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:-123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:release-group:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:999999999999999999999999"))
    }

    @Test fun albumAndSingleWithSameNameKeepDifferentAppleProductRoutesAndNoPlayability() {
        val labels = AppleEditorialShelfHeadings(
            albums = "Álbuns", eps = "EPs", singles = "Singles",
            unclassified = "Lançamentos",
        )
        val shelves = appleEditorialShelves(
            listOf(
                entry(release(101, "HOT", ItunesEditorialReleaseFormat.EP)),
                entry(release(102, "HOT", ItunesEditorialReleaseFormat.SINGLE)),
                entry(release(103, "HOT", ItunesEditorialReleaseFormat.ALBUM)),
                entry(release(104, "Unknown", ItunesEditorialReleaseFormat.UNCLASSIFIED)),
            ),
            headings = labels,
        )
        assertEquals(listOf("Álbuns", "EPs", "Singles", "Lançamentos"),
            shelves.map { it.title })
        val cards = shelves.flatMap { it.items }
        assertEquals(4, cards.size)
        assertEquals(setOf(
            "itunes:br:album:101", "itunes:br:album:102",
            "itunes:br:album:103", "itunes:br:album:104",
        ), cards.mapNotNull { it.browseId }.toSet())
        assertTrue(cards.all { it.videoId == null })
        assertTrue(cards.all { it.subtitle.contains("ILLIT") })
        assertEquals(4, cards.mapNotNull { it.browseId }.distinct().size)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/cover.jpg",
            cards.first().thumbnailUrl)
    }

    @Test fun shelvesRemainLimitedAndDoNotAssumeUnknownIsAnAlbum() {
        val many = (1L..30L).map { id ->
            entry(release(id, "Release $id", ItunesEditorialReleaseFormat.UNCLASSIFIED))
        }
        val shelves = appleEditorialShelves(
            many,
            headings = AppleEditorialShelfHeadings(
                albums = "Albums", eps = "EPs", singles = "Singles",
                unclassified = "Releases",
            ),
            maxPerShelf = 12,
        )
        assertEquals(1, shelves.size)
        assertEquals("Releases", shelves.single().title)
        assertEquals(12, shelves.single().items.size)
        assertTrue(shelves.single().items.all { it.videoId == null })
    }
}
