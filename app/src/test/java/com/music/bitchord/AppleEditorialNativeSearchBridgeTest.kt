package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.appleNativeAlbumDetail
import com.music.bitchord.ui.appleNativeSearchRows
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
