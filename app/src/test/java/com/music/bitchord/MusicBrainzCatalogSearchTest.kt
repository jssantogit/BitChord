package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCatalogSearchTest {
    @Test
    fun recordingSearchKeepsOriginalAndSpedUpSeparateAndPaginates() = runBlocking {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        val search = MusicBrainzCatalogSearch(
            client = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                        calls.add(Triple(path, parameters, userAgent))
                        return """{"count":2,"offset":25,"recordings":[
                            {"id":"e614e432-ca77-41c1-82c1-4e3a24ebbb0c","title":"Magnetic",
                             "length":160000,"artist-credit":[{"name":"ILLIT"}]},
                            {"id":"3e619ba2-34f0-4649-83d8-05483e2fabe5","title":"Magnetic (sped up)",
                             "length":124000,"artist-credit":[{"name":"ILLIT"}]},
                            {"id":"bad-identity","title":"Magnetic","length":1000}
                        ]}"""
                    }
                },
            ),
        )
        val result = search.searchRecordings("  ILLIT   Magnetic  ", limit = 25, offset = 25)
        assertTrue(result is MusicBrainzCatalogSearchResult.Success)
        val page = (result as MusicBrainzCatalogSearchResult.Success).page
        assertEquals(2, page.items.size)
        assertEquals(2, page.total)
        assertEquals(25, page.offset)
        assertEquals(listOf("Magnetic", "Magnetic (sped up)"), page.items.map { it.title })
        assertEquals(listOf(160000L, 124000L), page.items.map { it.durationMs })
        assertEquals(listOf("ILLIT", "ILLIT"), page.items.map { it.artistDisplay })
        assertEquals("recording", calls.single().first)
        assertEquals("ILLIT Magnetic", calls.single().second["query"])
        assertEquals("25", calls.single().second["limit"])
        assertEquals("25", calls.single().second["offset"])
        assertTrue(calls.single().third.startsWith("Bitchord/"))
    }

    @Test
    fun emptyTextFailsClosedWithoutNetworkCall() = runBlocking {
        var calls = 0
        val search = MusicBrainzCatalogSearch(
            client = MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                    calls++
                    return "{}"
                }
            }),
        )
        assertTrue(search.searchRecordings("   ") is MusicBrainzCatalogSearchResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun artistAndReleaseGroupResultsPreserveDistinctMbids() = runBlocking {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String =
                when (path) {
                    "artist" -> """{"count":1,"offset":0,"artists":[{"id":"b51c672b-85e0-48fe-8648-470a2422229f","name":"aespa","disambiguation":"South Korean girl group"}]}"""
                    "release-group" -> """{"count":1,"offset":0,"release-groups":[{"id":"529f87a1-1081-48a4-8ee1-3c08de229858","title":"SUPER REAL ME","primary-type":"EP"}]}"""
                    else -> error("Unexpected MusicBrainz endpoint $path")
                }
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(transport = transport))
        val artists = search.searchArtists("aespa") as MusicBrainzCatalogSearchResult.Success
        val groups = search.searchReleaseGroups("SUPER REAL ME") as MusicBrainzCatalogSearchResult.Success
        assertEquals("b51c672b-85e0-48fe-8648-470a2422229f", artists.page.items.single().musicBrainzId)
        assertEquals("529f87a1-1081-48a4-8ee1-3c08de229858", groups.page.items.single().musicBrainzId)
        assertEquals("EP", groups.page.items.single().primaryType)
    }

    @Test
    fun allDefaultMusicBrainzClientsUseOneProcessWideRateLimiter() {
        val first = MusicBrainzClient()
        val second = MusicBrainzClient()
        assertSame(first.rateLimiter, second.rateLimiter)
    }
}
