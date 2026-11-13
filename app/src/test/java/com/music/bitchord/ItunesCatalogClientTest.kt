package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogEntity
import com.music.bitchord.data.catalog.metadata.ItunesCatalogFailure
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesCatalogClientTest {
    private class FakeTransport(
        private val response: String,
    ) : ItunesCatalogTransport {
        var path: String? = null
        var parameters: Map<String, String> = emptyMap()
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            this.path = path
            this.parameters = parameters
            return response
        }
    }

    @Test fun independentAlbumSearchIsStorefrontScopedAndNeverYieldsPlayableMedia() = runBlocking {
        val transport = FakeTransport(
            """{"resultCount":2,"results":[
                {"wrapperType":"collection","collectionType":"Album","collectionId":101,"artistId":8,"collectionName":"How Sweet","artistName":"NewJeans","collectionViewUrl":"https://music.apple.com/br/album/how-sweet/101","releaseDate":"2024-05-24T00:00:00Z","trackCount":2},
                {"wrapperType":"track","kind":"music-video","trackId":102,"trackName":"How Sweet MV","artistName":"NewJeans"}
            ]}"""
        )
        val result = ItunesCatalogClient(transport).search(
            "How Sweet", ItunesCatalogEntity.ALBUM, country = "BR", limit = 20
        )
        assertTrue(result is ItunesCatalogResult.Success)
        val items = (result as ItunesCatalogResult.Success).items
        assertEquals(1, items.size)
        assertEquals(ItunesCatalogItemType.ALBUM, items.single().type)
        assertEquals("itunes:br:album:101", items.single().scopedId)
        assertEquals("How Sweet", items.single().title)
        assertFalse(items.single().isPlayable)
        assertNull(items.single().artworkUrl)
        assertNull(items.single().previewUrl)
        assertEquals("search", transport.path)
        assertEquals("music", transport.parameters["media"])
        assertEquals("album", transport.parameters["entity"])
        assertEquals("br", transport.parameters["country"])
        assertEquals("20", transport.parameters["limit"])
    }

    @Test fun trackSearchRejectsVideoAndForeignUrlWhileDeduplicatingById() = runBlocking {
        val transport = FakeTransport(
            """{"resultCount":4,"results":[
                {"wrapperType":"track","kind":"song","trackId":201,"collectionId":101,"trackName":"Ditto","artistName":"NewJeans","trackViewUrl":"https://evil.example/music","trackTimeMillis":185000},
                {"wrapperType":"track","kind":"song","trackId":201,"collectionId":101,"trackName":"Ditto","artistName":"NewJeans"},
                {"wrapperType":"track","kind":"music-video","trackId":202,"trackName":"Ditto MV","artistName":"NewJeans"},
                {"wrapperType":"collection","collectionId":203,"collectionName":"Ditto","artistName":"NewJeans","collectionType":"musicVideo"}
            ]}"""
        )
        val result = ItunesCatalogClient(transport).search("Ditto", ItunesCatalogEntity.SONG)
        val item = (result as ItunesCatalogResult.Success).items.single()
        assertEquals("itunes:br:track:201", item.scopedId)
        assertEquals(185000L, item.durationMs)
        assertNull(item.storeUrl)
        assertFalse(item.isPlayable)
    }

    @Test fun exactAlbumLookupExcludesTracksBelongingToOtherCollections() = runBlocking {
        val transport = FakeTransport(
            """{"resultCount":4,"results":[
                {"wrapperType":"collection","collectionId":123,"artistId":7,"collectionName":"Get Up","artistName":"NewJeans","collectionType":"Album"},
                {"wrapperType":"track","kind":"song","trackId":301,"collectionId":123,"trackName":"Super Shy","artistName":"NewJeans","trackNumber":2},
                {"wrapperType":"track","kind":"song","trackId":302,"collectionId":999,"trackName":"Unrelated","artistName":"NewJeans"},
                {"wrapperType":"track","kind":"music-video","trackId":303,"collectionId":123,"trackName":"Video","artistName":"NewJeans"}
            ]}"""
        )
        val result = ItunesCatalogClient(transport).lookupAlbum(123)
        val items = (result as ItunesCatalogResult.Success).items
        assertEquals(listOf(123L,301L), items.map { it.id })
        assertEquals("lookup", transport.path)
        assertEquals("123", transport.parameters["id"])
        assertEquals("song", transport.parameters["entity"])
    }

    @Test fun upcLookupRemainsAnUnverifiedCandidateWithoutInventedMusicBrainzIds() = runBlocking {
        val transport = FakeTransport(
            """{"resultCount":1,"results":[
                {"wrapperType":"collection","collectionId":456,"collectionName":"EASY","artistName":"LE SSERAFIM","collectionType":"Album"}
            ]}"""
        )
        val result = ItunesCatalogClient(transport).lookupUpc("8809704427821")
        val item = (result as ItunesCatalogResult.Success).items.single()
        assertEquals("8809704427821", transport.parameters["upc"])
        assertNull(item.musicBrainzId)
        assertFalse(item.isPlayable)
    }

    @Test fun editorialArtworkIsAcceptedOnlyFromAppleCdnAndUpcIsReadWithoutCreatingPlayback() = runBlocking {
        val transport = FakeTransport(
            """{"resultCount":2,"results":[
                {"wrapperType":"collection","collectionType":"Album","collectionId":71,
                 "collectionName":"HOT - EP","artistName":"LE SSERAFIM",
                 "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music121/valid/100x100bb.jpg",
                 "upc":"8801234567890","trackCount":5},
                {"wrapperType":"collection","collectionType":"Album","collectionId":72,
                 "collectionName":"HOT - Single","artistName":"LE SSERAFIM",
                 "artworkUrl100":"https://is1-ssl.mzstatic.com.evil.example/picture.jpg",
                 "upc":"hacked","trackCount":1}
            ]}"""
        )
        val items = (ItunesCatalogClient(transport).search(
            "HOT", ItunesCatalogEntity.ALBUM,
        ) as ItunesCatalogResult.Success).items
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music121/valid/100x100bb.jpg",
            items[0].artworkUrl)
        assertEquals("8801234567890", items[0].upc)
        assertEquals(5, items[0].trackCount)
        assertNull(items[1].artworkUrl)
        assertNull(items[1].upc)
        assertTrue(items.all { !it.isPlayable && it.previewUrl == null })
    }

    @Test fun artistLookupSupportsTwoHundredAlbumsPlusTheArtistHeader() = runBlocking {
        val rows = (1..200).joinToString(",") { id ->
            """{"wrapperType":"collection","collectionType":"Album",
                "collectionId":$id,"artistId":99,"collectionName":"Release $id",
                "artistName":"Example Artist"}"""
        }
        val transport = FakeTransport(
            """{"resultCount":201,"results":[
                {"wrapperType":"artist","artistId":99,"artistName":"Example Artist"},$rows
            ]}"""
        )
        val result = ItunesCatalogClient(transport).lookupArtistAlbums(99, limit = 200)
        val items = (result as ItunesCatalogResult.Success).items
        assertEquals(201, items.size)
        assertEquals(200, items.count { it.type == ItunesCatalogItemType.ALBUM })
        assertEquals("200", transport.parameters["limit"])
    }

    @Test fun invalidInputsCannotReachTheAppleEndpoint() = runBlocking {
        val transport = FakeTransport("""{"resultCount":0,"results":[]}""")
        val client = ItunesCatalogClient(transport)
        assertEquals(ItunesCatalogFailure.INVALID_QUERY,
            (client.search(" ", ItunesCatalogEntity.ARTIST) as ItunesCatalogResult.Failure).reason)
        assertEquals(ItunesCatalogFailure.INVALID_QUERY,
            (client.search("NewJeans", ItunesCatalogEntity.ARTIST, country = "BRA")
                as ItunesCatalogResult.Failure).reason)
        assertEquals(ItunesCatalogFailure.INVALID_QUERY,
            (client.lookupAlbum(-1) as ItunesCatalogResult.Failure).reason)
        assertEquals(ItunesCatalogFailure.INVALID_QUERY,
            (client.lookupUpc("abc") as ItunesCatalogResult.Failure).reason)
        assertNull(transport.path)
    }

    @Test fun malformedResponseFailsClosedWithoutPassingThroughUntrustedRows() = runBlocking {
        val transport = FakeTransport("""{"resultCount":2,"results":"not-an-array"}""")
        val result = ItunesCatalogClient(transport).search("NewJeans", ItunesCatalogEntity.ARTIST)
        assertEquals(ItunesCatalogFailure.MALFORMED_RESPONSE,
            (result as ItunesCatalogResult.Failure).reason)
    }
}
