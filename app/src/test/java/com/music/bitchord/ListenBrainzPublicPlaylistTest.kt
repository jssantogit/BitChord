package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistHttp
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.parseListenBrainzPublicPlaylistSearch
import com.music.bitchord.data.catalog.metadata.parseListenBrainzPublicPlaylistDetail
import com.music.bitchord.data.catalog.metadata.publicPlaylistMbid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzPublicPlaylistTest {
    private val rock = "11111111-1111-4111-8111-111111111111"
    private val pop = "22222222-2222-4222-8222-222222222222"
    private val song = "33333333-3333-4333-8333-333333333333"
    private fun response() = """{"playlists":[
        {"playlist_mbid":"$rock","title":"Classic Rock Essentials",
         "description":"Guitar favorites","creator":"Curator","public":true},
        {"playlist_mbid":"$pop","title":"Popular Music","creator":"Curator","public":true},
        {"playlist_mbid":"44444444-4444-4444-8444-444444444444",
         "title":"Private Rock","public":false},
        {"playlist_mbid":"NO-ID","title":"Rock Essentials","public":true},
        {"playlist_mbid":"$rock","title":"Classic Rock Essentials","public":true}
    ]}"""

    @Test fun genreSearchFiltersFuzzyFalsePositivesAndPrivatePlaylists() {
        val found = parseListenBrainzPublicPlaylistSearch(response(), "rock")!!
        assertEquals(1, found.size)
        assertEquals("Classic Rock Essentials", found.single().title)
        assertEquals("Curator", found.single().creator)
        assertEquals("catalog:PUBLIC_PLAYLIST:$rock", found.single().browseId)
        assertEquals(rock, publicPlaylistMbid(found.single().browseId))
        assertNull(publicPlaylistMbid("catalog:PUBLIC_PLAYLIST:fake/../id"))
        assertTrue(parseListenBrainzPublicPlaylistSearch(response(), "pop")!!.isEmpty())
        assertNull(parseListenBrainzPublicPlaylistSearch("""{"playlists":"bad"}""", "rock"))
    }

    @Test fun publicOnlySearchMayOmitPublicFlagButCannotExposePrivate() {
        val body = """{"playlists":[
            {"playlist_mbid":"$rock","title":"Rock in the world"},
            {"playlist_mbid":"$pop","title":"Private Rock","public":false}
        ]}"""
        assertEquals(listOf(rock),
            parseListenBrainzPublicPlaylistSearch(body, "rock")!!.map { it.mbid })
    }

    @Test fun jspfDetailPreservesRealMbidsAndDoesNotPromoteInvalidUrls() {
        val selected = ListenBrainzPublicPlaylist(rock, "Classic Rock", "Curator", null)
        val body = """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$rock",
          "track":[
            {"title":"One","creator":"Singer",
             "identifier":"https://musicbrainz.org/recording/$song"},
            {"title":"Two","creator":"Singer","identifier":"https://youtube.com/abc"},
            {"title":"Three","creator":"Band","identifier":[
              "https://musicbrainz.org/recording/$pop", "https://example.org/other"]}
          ]}}"""
        val actual = parseListenBrainzPublicPlaylistDetail(body, selected)!!
        assertEquals(listOf("One", "Two", "Three"), actual.tracks.map { it.title })
        assertEquals(listOf(song, null, pop), actual.tracks.map { it.recordingMbid })
        assertNull(parseListenBrainzPublicPlaylistDetail(
            body.replace("playlist/$rock", "playlist/$pop"), selected))
        assertNull(parseListenBrainzPublicPlaylistDetail(
            body.replace("\"track\":", "\"different\":"), selected))
    }

    @Test fun homePublicDiscoveryAcceptsVerifiedGlobalPlaylistsWithoutDiscoverInTitle() {
        // Global Home discovery is not a genre nor a title search for the word
        // 'discover'. The old word gate incorrectly discarded every result.
        val found = parseListenBrainzPublicPlaylistSearch(response())!!
        assertEquals(listOf(rock, pop), found.map { it.mbid })
    }

    @Test fun genreSearchFallsBackToVerifiedPhraseAfterFuzzyEmptyResult() = runBlocking {
        val requests = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    requests += query
                    return ListenBrainzPublicPlaylistHttp(200, if (query == "metal") {
                        """{"playlists":[]}"""
                    } else {
                        """{"playlists":[
                          {"playlist_mbid":"$rock","title":"Heavy Metal Classics",
                           "creator":"real-author","public":true},
                          {"playlist_mbid":"$pop","title":"Heavy Metal Private",
                           "public":false},
                          {"playlist_mbid":"44444444-4444-4444-8444-444444444444",
                           "title":"Metallurgy","public":true}
                        ]}"""
                    })
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf(rock), client.searchGenre("metal")!!.map { it.mbid })
        assertEquals(listOf("metal", "heavy metal"), requests)
    }

    @Test fun homeSearchCanDisplayRealCommunityListsOnFirstGeneralQuery() = runBlocking {
        val queries = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    queries += query
                    return ListenBrainzPublicPlaylistHttp(200, response())
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(2, client.searchHome()!!.size)
        assertEquals(listOf("music"), queries)
    }

    @Test fun publicSearchWorksWithoutTokensAndRestrictsGenreQueries() = runBlocking {
        val requests = mutableListOf<String>()
        val fake = object : ListenBrainzPublicPlaylistTransport {
            override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                requests += query
                return ListenBrainzPublicPlaylistHttp(200, response())
            }
            override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                requests += mbid
                return ListenBrainzPublicPlaylistHttp(404, "")
            }
        }
        val client = ListenBrainzPublicPlaylistClient(
            fake, ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(1, client.searchGenre("rock")!!.size)
        assertEquals(0, client.searchGenre("hip-hop")!!.size)
        assertNull(client.searchGenre("lucene:inject"))
        assertEquals(listOf("rock", "hip hop"), requests)
        assertNull(client.detail(ListenBrainzPublicPlaylist(rock, "Test", "Curator", null)))
        assertEquals(listOf("rock", "hip hop", rock), requests)
    }
}
