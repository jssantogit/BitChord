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
    fun paginationConsumesRawServerRowsEvenWhenSomeMbidsAreRejectedOrDuplicated() = runBlocking {
        val original = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
        val sped = "3e619ba2-34f0-4649-83d8-05483e2fabe5"
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String =
                    """{"count":12,"offset":0,"recordings":[
                        {"id":"not-an-mbid","title":"Bad"},
                        {"id":"$original","title":"Magnetic"},
                        {"id":"$original","title":"Magnetic"},
                        {"id":"$sped","title":"Magnetic (sped up)"}
                    ]}"""
            },
        ))
        val page = (search.searchRecordings("Magnetic") as
            MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>).page
        assertEquals(listOf(original, sped), page.items.map { it.musicBrainzId })
        assertEquals(4, page.consumedCount)
        assertEquals(4, page.nextOffset)
    }

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
        val page = (result as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>).page
        assertEquals(2, page.items.size)
        assertEquals(2, page.total)
        assertEquals(25, page.offset)
        assertEquals(null, page.nextOffset)
        assertEquals(listOf("Magnetic", "Magnetic (sped up)"), page.items.map { it.title })
        assertEquals(listOf(160000L, 124000L), page.items.map { it.durationMs })
        assertEquals(listOf("ILLIT", "ILLIT"), page.items.map { it.artistDisplay })
        assertEquals("recording", calls.single().first)
        assertEquals("ILLIT Magnetic", calls.single().second["query"])
        assertEquals("true", calls.single().second["dismax"])
        assertEquals("25", calls.single().second["limit"])
        assertEquals("25", calls.single().second["offset"])
        assertTrue(calls.single().third.startsWith("Bitchord/"))
    }

    @Test
    fun browsePaginationMustAdvanceByActualResponseRowsNotRequestedLimit() {
        val page = com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage(
            items = listOf("worldwide", "japan", "international"),
            total = 100, offset = 25,
        )
        assertEquals(28, page.nextOffset)
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
        val artists = search.searchArtists("aespa") as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit>
        val groups = search.searchReleaseGroups("SUPER REAL ME") as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit>
        assertEquals("b51c672b-85e0-48fe-8648-470a2422229f", artists.page.items.single().musicBrainzId)
        assertEquals("529f87a1-1081-48a4-8ee1-3c08de229858", groups.page.items.single().musicBrainzId)
        assertEquals("EP", groups.page.items.single().primaryType)
    }


    @Test
    fun artistScopedDiscoveryUsesValidatedMbidInsteadOfAmbiguousName() = runBlocking {
        val artistMbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
        val requested = mutableListOf<Triple<String, String?, String?>>()
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String,
                parameters: Map<String, String>,
                userAgent: String,
            ): String {
                requested.add(Triple(path, parameters["query"], parameters["dismax"]))
                return when (path) {
                    "recording" -> """{"count":1,"offset":0,"recordings":[{"id":"e614e432-ca77-41c1-82c1-4e3a24ebbb0c","title":"Magnetic","artist-credit":[{"name":"ILLIT"}]}]}"""
                    "release-group" -> """{"count":1,"offset":0,"release-groups":[{"id":"529f87a1-1081-48a4-8ee1-3c08de229858","title":"SUPER REAL ME","artist-credit":[{"name":"ILLIT"}]}]}"""
                    "release" -> """{"count":1,"offset":0,"releases":[{"id":"11d18da1-1081-48a4-8ee1-3c08de229858","title":"SUPER REAL ME","artist-credit":[{"name":"ILLIT"}]}]}"""
                    else -> error("Unexpected MusicBrainz endpoint")
                }
            }
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(transport = transport))
        val songs = search.searchRecordingsForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success
        val groups = search.searchReleaseGroupsForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success
        val editions = search.searchReleasesForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success
        assertEquals("Magnetic", songs.page.items.single().title)
        assertEquals("SUPER REAL ME", groups.page.items.single().title)
        assertEquals("SUPER REAL ME", editions.page.items.single().title)
        assertEquals(listOf("recording", "release-group", "release"), requested.map { it.first })
        assertTrue(requested.all { it.second == "arid:$artistMbid" && it.third == "false" })
    }

    @Test
    fun invalidArtistMbidCannotBecomeLuceneSyntaxOrSendNetwork() = runBlocking {
        var calls = 0
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                calls++
                return "{}"
            }
        }))
        assertTrue(search.searchRecordingsForArtistMbid("a OR artist:*") is MusicBrainzCatalogSearchResult.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun allDefaultMusicBrainzClientsUseOneProcessWideRateLimiter() {
        val first = MusicBrainzClient()
        val second = MusicBrainzClient()
        assertSame(first.rateLimiter, second.rateLimiter)
    }

    @Test fun duplicateMbidsWithDifferentMetadataConsumeRowsButDisplayOneIdentity() = runBlocking {
        val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"count":20,"offset":0,"release-groups":[
                    {"id":"$group","title":"SUPER REAL ME"},
                    {"id":"$group","title":"SUPER REAL ME (alternate metadata)"}
                ]}"""
            },
        ))
        val page = (search.searchReleaseGroups("SUPER REAL ME") as
            MusicBrainzCatalogSearchResult.Success<
                com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
            >).page
        assertEquals(listOf(group), page.items.map { it.musicBrainzId })
        assertEquals(2, page.consumedCount)
        assertEquals(2, page.nextOffset)
    }

}
