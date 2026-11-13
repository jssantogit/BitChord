package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialArtistLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.appleNativeArtistDetail
import com.music.bitchord.ui.appleNativeArtistSearchRows
import com.music.bitchord.ui.screens.AppleEditorialSearchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialArtistSearchTest {
    private class Source(val json: String) : ItunesCatalogTransport {
        var params: Map<String, String> = emptyMap()
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            params = parameters
            return json
        }
    }

    @Test fun appleArtistSearchReturnsAppleScopedIdentitiesNotMusicBrainzNamesakes() = runBlocking {
        val transport = Source("""{"resultCount":3,"results":[
            {"wrapperType":"artist","artistId":31,"artistName":"ILLIT"},
            {"wrapperType":"artist","artistId":32,"artistName":"ILLIT"},
            {"wrapperType":"collection","collectionId":55,"collectionName":"ILLIT",
             "artistName":"Other","collectionType":"Album"}
        ]}""")
        val loaded = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
            .searchArtists("ILLIT", storefront = "br", limit = 50)
        assertTrue(loaded is AppleEditorialArtistLoad.Success)
        val rows = appleNativeArtistSearchRows(loaded as AppleEditorialArtistLoad.Success)
        assertEquals(listOf("itunes:br:artist:31", "itunes:br:artist:32"),
            rows.map { it.browseId })
        assertTrue(rows.all { it.kind == AppleEditorialSearchKind.ARTIST })
        assertEquals("musicArtist", transport.params["entity"])
        assertEquals("br", transport.params["country"])
    }

    @Test fun artistDetailUsesArtistIdAndOnlyItsStorefrontAlbums() {
        val releases = listOf(
            edition(101, "br", 31),
            edition(102, "br", 31),
            edition(103, "br", 32),
            edition(104, "us", 31),
        )
        val result = appleNativeArtistDetail(
            AppleEditorialLoad.Success(releases, AppleEditorialReadState.POSSIBLY_TRUNCATED),
            artistBrowseId = "itunes:br:artist:31",
            artistName = "ILLIT",
        )
        assertEquals("ILLIT", result?.artist?.title)
        assertEquals(listOf("itunes:br:album:101", "itunes:br:album:102"),
            result?.albums?.map { it.browseId })
        assertEquals(true, result?.possiblyTruncated)
    }

    @Test fun artistDetailNeverAdoptsOtherArtistsCollectionWithoutMatchingId() {
        val loaded = AppleEditorialLoad.Success(
            listOf(edition(101, "br", 32)), AppleEditorialReadState.COMPLETE,
        )
        val result = appleNativeArtistDetail(
            loaded, artistBrowseId = "itunes:br:artist:31", artistName = "ILLIT",
        )
        assertTrue(result?.albums?.isEmpty() == true)
        assertNull(appleNativeArtistDetail(
            loaded, artistBrowseId = "catalog:ARTIST:31", artistName = "ILLIT",
        ))
    }

    private fun edition(id: Long, region: String, artist: Long) = ItunesEditorialRelease(
        appleScopedId = "itunes:$region:album:$id",
        appleCollectionId = id,
        storefront = region,
        title = "Release $id",
        artistName = "ILLIT",
        artistId = artist,
        releaseDate = "2024-03-25",
        genre = "K-Pop",
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = null,
        trackCount = 2,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )
}
