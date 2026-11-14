package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialTrackLoad
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import com.music.bitchord.ui.appleNativeTrackSearchRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialTrackSearchTest {
    private class Source(private val payload: String) : ItunesCatalogTransport {
        var params: Map<String, String> = emptyMap()
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            params = parameters
            return payload
        }
    }

    @Test fun searchUsesAppleSongIdsWithoutVideosOrSyntheticPlayback() = runBlocking {
        val source = Source("""{"resultCount":4,"results":[
          {"wrapperType":"track","kind":"song","trackId":201,"trackName":"Magnetic",
           "artistName":"ILLIT","collectionId":301,"trackTimeMillis":165000},
          {"wrapperType":"track","kind":"song","trackId":202,"trackName":"Magnetic",
           "artistName":"Another Artist"},
          {"wrapperType":"track","kind":"music-video","trackId":203,"trackName":"Magnetic MV",
           "artistName":"ILLIT"},
          {"wrapperType":"collection","collectionId":204,"collectionName":"Magnetic",
           "artistName":"ILLIT"}
        ]}""")
        val loaded = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .searchTracks("Magnetic", storefront = "br", limit = 40)
        assertTrue(loaded is AppleEditorialTrackLoad.Success)
        val rows = appleNativeTrackSearchRows(loaded as AppleEditorialTrackLoad.Success)
        assertEquals(listOf("itunes:br:track:201", "itunes:br:track:202"),
            rows.map { it.key })
        assertEquals("itunes:br:album:301", rows[0].albumBrowseId)
        assertNull(rows[1].albumBrowseId)
        assertEquals(165000L, rows[0].durationMs)
        assertEquals("song", source.params["entity"])
        assertTrue(loaded.tracks.all { !it.isPlayable && it.previewUrl == null })
    }

    @Test fun identicalNamesKeepDifferentAppleTrackIdentities() = runBlocking {
        val source = Source("""{"resultCount":2,"results":[
          {"wrapperType":"track","kind":"song","trackId":1,
           "trackName":"Ditto","artistName":"NewJeans"},
          {"wrapperType":"track","kind":"song","trackId":2,
           "trackName":"Ditto","artistName":"NewJeans"}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .searchTracks("Ditto") as AppleEditorialTrackLoad.Success
        val rows = appleNativeTrackSearchRows(result)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.distinct().size)
        assertTrue(rows.all { it.albumBrowseId == null })
    }
}
