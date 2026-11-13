package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.appleNativeAlbumDetail
import com.music.bitchord.ui.appleNativeSearchRows
import com.music.bitchord.ui.appleNativeAlbumSearchRowsInSourceOrder
import com.music.bitchord.ui.screens.AppleEditorialReleaseCategory
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialNativeSearchBridgeTest {
    private fun edition(
        id: Long = 101,
        title: String = "HOT - EP",
        country: String = "br",
        trackCount: Int = 2,
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:$country:album:$id",
        appleCollectionId = id,
        storefront = country,
        title = title,
        artistName = "LE SSERAFIM",
        artistId = 77,
        releaseDate = "2025-03-14",
        genre = "K-Pop",
        format = ItunesEditorialReleaseFormat.EP,
        artworkUrl = "https://is1-ssl.mzstatic.com/cover.jpg",
        trackCount = trackCount,
        tracks = listOf(
            track(2, id, 2, "Second song", country),
            track(1, id, 1, "First song", country),
        ),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    private fun track(id: Long, albumId: Long, number: Int, title: String, country: String) =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK,
            id = id,
            storefront = country,
            title = title,
            artistName = "LE SSERAFIM",
            artistId = 77,
            collectionId = albumId,
            storeUrl = null,
            releaseDate = null,
            genre = null,
            durationMs = 180000,
            trackNumber = number,
            discNumber = 1,
        )

    @Test fun AppleAlbumHeaderCarriesOnlyItsOwnApprovedStorePromotionBadge() {
        val url = "https://music.apple.com/br/album/hot/101"
        val verified = appleNativeAlbumDetail(
            AppleEditorialLoad.Success(listOf(edition().copy(storeUrl = url)),
                AppleEditorialReadState.COMPLETE),
            "itunes:br:album:101",
        )!!
        assertEquals(url, verified.appleStoreUrl)
        assertEquals("https://www.apple.com/ca/itunes/link/images/link-badge-music.png",
            verified.appleStoreBadgeUrl)
        val unsafe = appleNativeAlbumDetail(
            AppleEditorialLoad.Success(listOf(edition().copy(
                storeUrl = "https://music.apple.com.attacker.example/br/album/hot",
            )), AppleEditorialReadState.COMPLETE),
            "itunes:br:album:101",
        )!!
        assertNull(unsafe.appleStoreUrl)
        assertNull(unsafe.appleStoreBadgeUrl)
    }

    @Test fun appSearchRowsPreserveExactAppleProductIdentityAndArtwork() {
        val source = AppleEditorialLoad.Success(
            releases = listOf(edition(101), edition(102, "HOT - Single")),
            state = AppleEditorialReadState.COMPLETE,
        )
        val rows = appleNativeSearchRows(source)
        assertEquals(listOf("itunes:br:album:101", "itunes:br:album:102"),
            rows.map { it.browseId })
        assertEquals(listOf("HOT - EP", "HOT - Single"), rows.map { it.title })
        assertEquals("https://is1-ssl.mzstatic.com/cover.jpg", rows[0].artworkUri)
        assertTrue(rows.all { it.artistName == "LE SSERAFIM" })
        assertEquals(listOf(
            AppleEditorialReleaseCategory.EP,
            AppleEditorialReleaseCategory.EP,
        ), rows.map { it.category })
    }

    @Test fun appleSearchPaginationPreservesRemoteRankingWhenNewReleasesArrive() {
        fun item(id: Long, date: String) = ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = id, storefront = "br",
            title = "Release $id", artistName = "Artist", artistId = 77,
            collectionId = id, storeUrl = null, releaseDate = date,
            genre = null, durationMs = null, trackNumber = null, discNumber = null,
        )
        val rows = appleNativeAlbumSearchRowsInSourceOrder(listOf(
            item(1, "2001-01-01"), item(2, "2026-01-01"),
            item(3, "2012-01-01"),
        ), "br")
        assertEquals(listOf("itunes:br:album:1", "itunes:br:album:2",
            "itunes:br:album:3"), rows.map { it.browseId })
    }

    @Test fun artistProofUsesOnlyAnExactAppleSearchRowNotNamesOrAlbumLinks() {
        fun artist(id: Long, country: String = "br", title: String = "Same Name") =
            ItunesCatalogItem(
                type = ItunesCatalogItemType.ARTIST, id = id, storefront = country,
                title = title, artistName = title, artistId = id,
                collectionId = null,
                storeUrl = "https://music.apple.com/$country/artist/name/$id",
                releaseDate = null, genre = null, durationMs = null,
                trackNumber = null, discNumber = null,
            )
        val requested = com.music.bitchord.ui.screens.AppleEditorialSearchRow(
            browseId = "itunes:br:artist:77",
            title = "Same Name", artistName = "Same Name",
            artworkUri = null, releaseDate = null,
            kind = com.music.bitchord.ui.screens.AppleEditorialSearchKind.ARTIST,
        )
        val correct = artist(77)
        assertEquals(correct.storeUrl,
            com.music.bitchord.ui.appleExactArtistStoreUrl(listOf(
                artist(88), artist(77, "us"), correct,
            ), requested))
        assertNull(com.music.bitchord.ui.appleExactArtistStoreUrl(
            listOf(artist(88), artist(77, "us")), requested))
        assertNull(com.music.bitchord.ui.appleExactArtistStoreUrl(
            listOf(correct), requested.copy(title = "Unverified Name")))
        assertNull(com.music.bitchord.ui.appleExactArtistStoreUrl(
            listOf(correct, correct.copy(storeUrl = "https://music.apple.com/br/artist/other/77")),
            requested))
        assertNull(com.music.bitchord.ui.appleExactArtistStoreUrl(
            listOf(correct), requested.copy(browseId = "itunes:br:album:77")))
    }

    @Test fun runtimeEditionProofUpdatesOnlyAlbumCategoryAndPreservesAppleDetail() {
        // The shared edition fixture defaults to EP. This case specifically
        // verifies enrichment from an *unclassified* Apple display name.
        val album = edition(title = "HOT").copy(
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        )
        val source = AppleEditorialLoad.Success(
            listOf(album), AppleEditorialReadState.COMPLETE,
        )
        val before = appleNativeAlbumDetail(source, album.appleScopedId)!!
        val matched = com.music.bitchord.data.catalog.metadata
            .AppleMusicBrainzEditionMatch.Verified(
                appleScopedId = album.appleScopedId,
                musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
                musicBrainzReleaseGroupId = "529f87a1-1081-48a4-8ee1-3c08de229858",
                recordingLinks = emptyList(),
            )
        val proof = com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry(
            release = album,
            match = matched,
            classification = com.music.bitchord.data.catalog.metadata
                .AppleEditorialFormatResolution(
                    format = ItunesEditorialReleaseFormat.EP,
                    status = com.music.bitchord.data.catalog.metadata
                        .AppleEditorialFormatStatus.DECIDED,
                    sources = setOf(com.music.bitchord.data.catalog.metadata
                        .AppleEditorialFormatSource.MUSICBRAINZ),
                    disputedExternalFormats = emptySet(),
                ),
            discogsEdition = null,
        )
        val after = com.music.bitchord.ui.appleNativeReconciledAlbumDetail(
            source, album.appleScopedId, proof,
        )!!
        assertEquals(AppleEditorialReleaseCategory.UNCLASSIFIED, before.album.category)
        assertEquals(AppleEditorialReleaseCategory.EP, after.album.category)
        assertEquals(before.tracks, after.tracks)
        assertEquals(before.album.artworkUri, after.album.artworkUri)
        assertEquals(before.album.title, after.album.title)
        assertEquals(before.partialTracklist, after.partialTracklist)
        assertEquals(before, com.music.bitchord.ui.appleNativeReconciledAlbumDetail(
            source, album.appleScopedId,
            proof.copy(match = com.music.bitchord.data.catalog.metadata
                .AppleMusicBrainzEditionMatch.NoMatch),
        ))
        assertEquals(before, com.music.bitchord.ui.appleNativeReconciledAlbumDetail(
            source, album.appleScopedId,
            proof.copy(release = album.copy(appleCollectionId = 102)),
        ))
        val discogs = com.music.bitchord.data.catalog.metadata.discogs
            .DiscogsEditionPresentation(
                country = "South Korea", editionYear = 2024,
                labelLines = listOf("BELIFT LAB"), formats = listOf("EP"),
                attributionText = "Data provided by Discogs.",
                attributionUrl = "https://www.discogs.com/release/25",
            )
        val attributed = com.music.bitchord.ui.appleNativeReconciledAlbumDetail(
            source, album.appleScopedId, proof.copy(discogsEdition = discogs),
        )!!
        assertEquals("https://www.discogs.com/release/25",
            attributed.discogsAttributionUrl)
        assertEquals("Data provided by Discogs.", attributed.discogsAttributionText)
        assertTrue(attributed.discogsNonAffiliationNotice.orEmpty()
            .contains("not affiliated"))
        val unsafe = com.music.bitchord.ui.appleNativeReconciledAlbumDetail(
            source, album.appleScopedId, proof.copy(discogsEdition = discogs.copy(
                attributionUrl = "https://evil.example/release/25",
            )),
        )!!
        assertNull(unsafe.discogsAttributionUrl)
    }

    @Test fun detailedAppleTracklistKeepsItsOwnIdentityAndNeverReturnsPlayableSong() {
        val result = appleNativeAlbumDetail(
            AppleEditorialLoad.Success(listOf(edition()), AppleEditorialReadState.COMPLETE),
            requestedBrowseId = "itunes:br:album:101",
        )
        assertNotNull(result)
        assertEquals("HOT - EP", result?.album?.title)
        assertEquals(listOf("First song", "Second song"), result?.tracks?.map { it.title })
        assertEquals(listOf("itunes:br:track:1", "itunes:br:track:2"),
            result?.tracks?.map { it.key })
        assertEquals(false, result?.partialTracklist)
    }

    @Test fun incompleteAppleAlbumIsStillBrowsableWithExplicitPartialWarning() {
        val result = appleNativeAlbumDetail(
            AppleEditorialLoad.Success(
                listOf(edition().copy(trackCount = 4)),
                AppleEditorialReadState.PARTIAL_TRACKLIST,
            ),
            "itunes:br:album:101",
        )
        assertEquals(true, result?.partialTracklist)
    }

    @Test fun crossStorefrontRouteCannotOpenIdenticallyNumberedForeignRelease() {
        val load = AppleEditorialLoad.Success(
            listOf(edition(country = "us")), AppleEditorialReadState.COMPLETE,
        )
        assertNull(appleNativeAlbumDetail(load, "itunes:br:album:101"))
        assertNotNull(appleNativeAlbumDetail(load, "itunes:us:album:101"))
        assertNull(appleNativeAlbumDetail(load, "catalog:RELEASE_GROUP:101"))
    }
}
