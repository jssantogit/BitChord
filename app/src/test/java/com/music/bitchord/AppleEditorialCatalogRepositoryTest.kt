package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogEntity
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import com.music.bitchord.data.catalog.metadata.ItunesCatalogSearchAttribute
import com.music.bitchord.data.catalog.metadata.ItunesPublicSearchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialCatalogRepositoryTest {
    private class Source(val json: String) : ItunesCatalogTransport {
        var parameters = emptyMap<String, String>()
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            this.parameters = parameters
            return json
        }
    }

    private fun album(id: Int, count: Int = 1) =
        """{"wrapperType":"collection","collectionType":"Album","collectionId":$id,
            "artistId":77,"artistName":"Artist","collectionName":"Release $id",
            "trackCount":$count,"releaseDate":"2024-03-25T00:00:00Z"}"""

    private fun track(id: Int, collection: Int) =
        """{"wrapperType":"track","kind":"song","trackId":$id,"collectionId":$collection,
            "artistName":"Artist","artistId":77,"trackName":"Song $id",
            "discNumber":1,"trackNumber":1}"""

    @Test fun searchApiSupportsOfficialTwoHundredResultLimitAndReportsPossibleTruncation() =
        runBlocking {
            val rows = (1..200).joinToString(",") { album(it) }
            val source = Source("""{"resultCount":200,"results":[$rows]}""")
            val result = ItunesCatalogClient(source).search(
                "Artist", ItunesCatalogEntity.ALBUM, limit = 200,
            )
            assertTrue(result is ItunesCatalogResult.Success)
            assertTrue((result as ItunesCatalogResult.Success).possiblyTruncated)
            assertEquals(200, result.items.size)
            assertEquals("200", source.parameters["limit"])
        }

    @Test fun artistCatalogReportsLookupCapAsPossibleIncompleteDiscography() = runBlocking {
        val source = Source("""{"resultCount":201,"results":[
            {"wrapperType":"artist","artistId":77,"artistName":"Artist"},
            ${(1..200).joinToString(",") { album(it) }}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .artistDiscography(artistId = 77)
        assertTrue(result is AppleEditorialLoad.Success)
        result as AppleEditorialLoad.Success
        assertEquals(200, result.releases.size)
        assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        assertEquals("200", source.parameters["limit"])
        assertTrue(result.releases.all { it.musicBrainzReleaseId == null })
    }

    @Test fun albumWithIncompleteTracklistIsVisibleButNeverDeclaredComplete() = runBlocking {
        val source = Source("""{"resultCount":2,"results":[
            ${album(123, count = 2)}, ${track(101, 123)}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123)
        assertTrue(result is AppleEditorialLoad.Success)
        result as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, result.state)
        assertEquals(1, result.releases.single().tracks.size)
    }

    @Test fun albumWithAllOrderedTracksReportsVerifiedSourceCompleteness() = runBlocking {
        val source = Source("""{"resultCount":2,"results":[
            ${album(123, count = 1)}, ${track(101, 123)}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123)
        assertEquals(AppleEditorialReadState.COMPLETE,
            (result as AppleEditorialLoad.Success).state)
    }

    @Test fun missingStorefrontAlbumIsDifferentFromTransportFailure() = runBlocking {
        val source = Source("""{"resultCount":0,"results":[]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(987)
        assertEquals(AppleEditorialReadState.NOT_IN_STOREFRONT,
            (result as AppleEditorialLoad.Success).state)
        assertTrue(result.releases.isEmpty())
    }

    @Test fun supplementalDiscoveryUsesDocumentedSearchAttributesAndExactArtistId() =
        runBlocking {
            val source = Source("""{"resultCount":3,"results":[
                ${album(501)},
                {"wrapperType":"collection","collectionType":"Album",
                 "collectionId":999,"artistId":88,"artistName":"Artist",
                 "collectionName":"Wrong artist"},
                {"wrapperType":"track","kind":"song","trackId":12,
                 "trackName":"Not album","artistId":77}
            ]}""")
            val catalog = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            val result = catalog.searchArtistAlbumCandidates(
                query = "Artist", artistId = 77, storefront = "br",
                attribute = ItunesCatalogSearchAttribute.ARTIST_TERM,
            ) as AppleEditorialLoad.Success
            assertEquals(listOf(501L), result.releases.map { it.appleCollectionId })
            assertEquals("artistTerm", source.parameters["attribute"])
            assertEquals("album", source.parameters["entity"])
            assertEquals("200", source.parameters["limit"])
            assertFalse(source.parameters.containsKey("offset"))
            catalog.searchArtistAlbumCandidates(
                query = "Release 501", artistId = 77, storefront = "br",
                attribute = ItunesCatalogSearchAttribute.ALBUM_TERM,
            )
            assertEquals("albumTerm", source.parameters["attribute"])
        }

    @Test fun searchPaginationUsesDocumentedSongTermNotUndocumentedOffset() = runBlocking {
        val source = Source("""{"resultCount":0,"results":[]}""")
        val response = ItunesPublicSearchSource(ItunesCatalogClient(source)).search(
            query = "Ditto",
            entity = ItunesCatalogEntity.SONG,
            country = "br",
            attribute = ItunesCatalogSearchAttribute.SONG_TERM,
        )
        assertTrue(response is ItunesCatalogResult.Success)
        assertEquals("song", source.parameters["entity"])
        assertEquals("songTerm", source.parameters["attribute"])
        assertEquals("200", source.parameters["limit"])
        assertFalse(source.parameters.containsKey("offset"))
    }

    @Test fun sameArtistHomonymsAndForeignAlbumIdsNeverEnterSelectedArtistShelf() =
        runBlocking {
            val source = Source("""{"resultCount":3,"results":[
                {"wrapperType":"artist","artistId":77,"artistName":"Artist"},
                ${album(123)},
                {"wrapperType":"collection","collectionType":"Album","collectionId":999,
                 "artistId":88,"artistName":"Artist","collectionName":"Wrong artist"}
            ]}""")
            val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
                .artistDiscography(77)
            result as AppleEditorialLoad.Success
            assertEquals(listOf(123L), result.releases.map { it.appleCollectionId })
            assertEquals(AppleEditorialReadState.COMPLETE, result.state)
        }
}
