package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistHttp
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.parseListenBrainzPublicPlaylistSearch
import com.music.bitchord.data.catalog.metadata.parseListenBrainzPublicRecordingArtwork
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

    /** Matches upstream Playlist.serialize_jspf() and serialize_playlists(),
     * not the flattened example used by old unit fixtures. */
    private fun realJspfSearchResponse() = """{"playlist_count":5,"count":5,"offset":0,"playlists":[
      {"playlist":{
         "identifier":"https://listenbrainz.org/playlist/$rock",
         "creator":"kpopfan","title":"Best K-pop songs",
         "annotation":"K-pop mix for dancing",
         "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true,"creator":"kpopfan"}},
         "track":[]}},
      {"playlist":{
         "identifier":"https://listenbrainz.org/playlist/$pop/",
         "creator":"curator","title":"Pop essentials",
         "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true}}}},
      {"playlist":{
         "identifier":"https://listenbrainz.org/playlist/44444444-4444-4444-8444-444444444444",
         "title":"Private K-pop","extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":false}}}},
      {"playlist":{
         "identifier":"https://example.invalid/playlist/55555555-5555-4555-8555-555555555555",
         "title":"Fake K-pop","extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true}}}},
      {"playlist":{
         "identifier":"https://listenbrainz.org/playlist/$rock",
         "creator":"kpopfan","title":"Best K-pop songs",
         "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true}}}}
    ]}"""

    @Test fun publicPlaylistDetailsReuseSuccessfulExactIdWithinFreshnessWindow() = runBlocking {
        var now = 1_000L
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$rock",
                            "title":"Rock","track":[
                            {"identifier":"https://musicbrainz.org/recording/$song",
                             "title":"Test song","creator":"Test artist"}]}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
        )
        val selected = ListenBrainzPublicPlaylist(rock, "Rock", "Curator", null)
        assertEquals(1, client.detail(selected)!!.tracks.size)
        assertEquals(1, client.detail(selected)!!.tracks.size)
        assertEquals(1, calls)
        now += 10 * 60_000L + 1L
        assertEquals(1, client.detail(selected)!!.tracks.size)
        assertEquals(2, calls)
    }

    @Test fun temporaryPublicDetailFailureIsRetriedInsteadOfCachedAsEmpty() = runBlocking {
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return if (calls == 1) ListenBrainzPublicPlaylistHttp(503, "")
                        else ListenBrainzPublicPlaylistHttp(200,
                            """{"playlist":{"track":[]}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        val selected = ListenBrainzPublicPlaylist(rock, "Rock", "Curator", null)
        assertNull(client.detail(selected))
        assertEquals(0, client.detail(selected)!!.tracks.size)
        assertEquals(2, calls)
    }

    @Test fun liveJspfSearchAcceptsPublicPlaylistIdentityWithoutFlattenedMbid() {
        val global = parseListenBrainzPublicPlaylistSearch(realJspfSearchResponse())!!
        assertEquals(listOf(rock, pop), global.map { it.mbid })
        assertEquals("kpopfan", global.first().creator)
        assertEquals("K-pop mix for dancing", global.first().description)
        assertEquals("catalog:PUBLIC_PLAYLIST:$rock", global.first().browseId)
        val kpop = parseListenBrainzPublicPlaylistSearch(realJspfSearchResponse(), "k-pop")!!
        assertEquals(listOf(rock), kpop.map { it.mbid })
        assertTrue(parseListenBrainzPublicPlaylistSearch(realJspfSearchResponse(), "metal")!!.isEmpty())
    }

    @Test fun liveJspfRejectsInvalidAndPrivateIdentifiers() {
        val source = realJspfSearchResponse()
        assertEquals(2, parseListenBrainzPublicPlaylistSearch(source)!!.size)
        assertNull(parseListenBrainzPublicPlaylistSearch(
            """{"playlists":{"playlist":{"title":"K-pop"}}}""", "k-pop"))
        val malicious = """{"playlists":[{"playlist":{
           "identifier":"https://listenbrainz.org/playlist/../../../anything",
           "title":"K-pop",
           "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true}}
        }}]}"""
        assertTrue(parseListenBrainzPublicPlaylistSearch(malicious, "k-pop")!!.isEmpty())
    }

    @Test fun clientUsesJspfMatchesWithoutPayingForMultipleSlowSearches() = runBlocking {
        val queries = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    queries += query
                    return ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
                }
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf(rock, pop), client.searchHome()!!.map { it.mbid })
        assertEquals(listOf("essentials"), queries)
        queries.clear()
        assertEquals(listOf(rock), client.searchGenre("k-pop")!!.map { it.mbid })
        assertEquals(listOf("k-pop"), queries)
    }

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
        assertEquals(listOf("essentials"), queries)
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
        // Hip-hop has no matching words in the mock response, so the second
        // bounded genre phrase must also be checked before reporting empty.
        assertEquals(listOf("rock", "hip hop", "hip hop music"), requests)
        assertNull(client.detail(ListenBrainzPublicPlaylist(rock, "Test", "Curator", null)))
        assertEquals(listOf("rock", "hip hop", "hip hop music", rock), requests)
    }

    @Test fun jspfTrackUsesOnlyVerifiedReleaseArtworkReferences() {
        val selected = ListenBrainzPublicPlaylist(rock, "Rock", "Curator", null)
        val body = """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$rock","track":[
          {"title":"Official cover","creator":"Band","identifier":"https://musicbrainz.org/recording/$song",
           "extension":{"https://musicbrainz.org/doc/jspf#track":{"release_identifier":"https://musicbrainz.org/release/$pop",
             "additional_metadata":{"caa_release_mbid":"$pop","caa_id":12001}}}},
          {"title":"Release cover","creator":"Band","identifier":"https://musicbrainz.org/recording/$pop",
           "extension":{"https://musicbrainz.org/doc/jspf#track":{"release_identifier":"https://musicbrainz.org/release/$rock"}}},
          {"title":"Malicious URL","creator":"Band",
           "extension":{"https://musicbrainz.org/doc/jspf#track":{"release_identifier":"https://evil.example/release/$rock",
             "additional_metadata":{"caa_release_mbid":"../../evil","caa_id":20}}}}
        ]}}"""
        val rows = parseListenBrainzPublicPlaylistDetail(body, selected)!!.tracks
        assertEquals("https://coverartarchive.org/release/$pop/12001-250.jpg", rows[0].artworkUrl)
        assertEquals("https://coverartarchive.org/release/$rock/front-250.jpg", rows[1].artworkUrl)
        assertNull(rows[2].artworkUrl)
    }

    @Test fun recordingMetadataArtworkMustHaveAValidRequestedMbidAndCaaReference() {
        val body = """{"$song":{"release":{"caa_release_mbid":"$pop","caa_id":987654321}},
          "$rock":{"release":{"caa_release_mbid":"../../etc","caa_id":4}},
          "invalid":{"release":{"caa_release_mbid":"$pop","caa_id":8}}}"""
        val covers = parseListenBrainzPublicRecordingArtwork(body, listOf(song, rock))
        assertEquals(mapOf(song to "https://coverartarchive.org/release/$pop/987654321-250.jpg"), covers)
        assertTrue(parseListenBrainzPublicRecordingArtwork("""{"$song":{"release":{"mbid":"$pop"}}}""", listOf(song)).isEmpty())
    }

    @Test fun jspfDescriptionDecodesVisibleHtmlEntitiesWithoutHtmlRendering() {
        val json = """{"playlists":[{"playlist":{"identifier":"https://listenbrainz.org/playlist/$rock",
          "title":"K-pop &amp; rock","annotation":"The new &amp; hottest &#39;K-pop&#39; is here!",
          "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":true}}}}]}"""
        val playlist = parseListenBrainzPublicPlaylistSearch(json)!!.single()
        assertEquals("K-pop & rock", playlist.title)
        assertEquals("The new & hottest 'K-pop' is here!", playlist.description)
    }

    @Test fun artistPlaylistMustContainARecordingCreditedToTheExactArtist() = runBlocking {
        val requested = mutableListOf<String>()
        val transport = object : ListenBrainzPublicPlaylistTransport {
            override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                requested += "search:$query"
                return ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
            }
            override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                requested += "detail:$mbid"
                val recording = if (mbid == rock) song else pop
                return ListenBrainzPublicPlaylistHttp(200,
                    """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$mbid",
                       "track":[{"title":"Credited","creator":"Artist",
                         "identifier":"https://musicbrainz.org/recording/$recording"}]}}""")
            }
        }
        val client = ListenBrainzPublicPlaylistClient(
            transport, ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(emptyList<ListenBrainzPublicPlaylist>(),
            client.searchArtistVerified("Real artist", emptySet()))
        assertTrue(requested.isEmpty())
        assertEquals(listOf(rock),
            client.searchArtistVerified("Real artist", setOf(song)).map { it.mbid })
        assertEquals(listOf("search:Real artist", "detail:$rock", "detail:$pop"), requested)
    }
    @Test fun freeTextPlaylistSearchChecksQueryWithoutFabricatingMatches() = runBlocking {
        val requested = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    requested += query
                    return ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf(rock), client.searchPublicQuery("K-pop").map { it.mbid })
        assertEquals(listOf("K-pop"), requested)
        assertTrue(client.searchPublicQuery("  ").isEmpty())
        assertEquals(listOf("K-pop"), requested)
    }

    @Test fun artistPlaylistSearchCacheFollowsExactRecordingEvidence() = runBlocking {
        var now = 1_000L
        var searches = 0
        var details = 0
        val transport = object : ListenBrainzPublicPlaylistTransport {
            override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                searches++
                return ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
            }
            override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                details++
                val recording = if (mbid == rock) song else pop
                return ListenBrainzPublicPlaylistHttp(200,
                    """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$mbid",
                      "track":[{"identifier":"https://musicbrainz.org/recording/$recording",
                        "title":"Credited song","creator":"Artist"}]}}""")
            }
        }
        val client = ListenBrainzPublicPlaylistClient(
            transport, ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
        )
        assertEquals(listOf(rock), client.searchArtistVerified("Artist", setOf(song)).map { it.mbid })
        now += 60_000
        assertEquals(listOf(rock), client.searchArtistVerified("Artist", setOf(song)).map { it.mbid })
        assertEquals(1, searches)
        assertEquals(2, details)
        // A new set of verified top recordings must not inherit another
        // artist-specific membership result from the previous view.
        assertEquals(listOf(pop), client.searchArtistVerified("Artist", setOf(pop)).map { it.mbid })
        assertEquals(2, searches)
        now += 60L * 60L * 1_000L + 1
        assertEquals(listOf(rock), client.searchArtistVerified("Artist", setOf(song)).map { it.mbid })
        assertEquals(3, searches)
    }

    @Test fun artistPlaylistServiceFailureIsCachedOnlyBriefly() = runBlocking {
        var now = 10_000L
        var requests = 0
        val transport = object : ListenBrainzPublicPlaylistTransport {
            override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                requests++
                return if (requests == 1) ListenBrainzPublicPlaylistHttp(503, "")
                    else ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
            }
            override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp =
                ListenBrainzPublicPlaylistHttp(200,
                    """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$mbid",
                      "track":[{"identifier":"https://musicbrainz.org/recording/$song"}]}}""")
        }
        val client = ListenBrainzPublicPlaylistClient(
            transport, ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
        )
        assertTrue(client.searchArtistVerified("Artist", setOf(song)).isEmpty())
        now += 20_000L
        assertTrue(client.searchArtistVerified("Artist", setOf(song)).isEmpty())
        assertEquals(1, requests)
        now += 91_000L
        assertEquals(listOf(rock, pop), client.searchArtistVerified("Artist", setOf(song))
            .map { it.mbid })
        assertEquals(2, requests)
    }

    @Test fun publicTextSearchFiltersBeforeTwelveItemDisplayLimit() = runBlocking {
        // Fuzzy upstream ordering may put a relevant playlist after twelve
        // unrelated matches. Exact match must be applied before truncation.
        val noise = (1..12).joinToString(",") { n ->
            val id = n.toString(16).padStart(8, '0') + "-1111-4111-8111-111111111111"
            """{"playlist_mbid":"$id","title":"Unrelated collection $n","public":true}"""
        }
        val body = """{"playlists":[$noise,
          {"playlist_mbid":"$rock","title":"ILLIT favorites","public":true}]}"""
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) = ListenBrainzPublicPlaylistHttp(
                    200, body,
                )
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf(rock), client.searchPublicQuery("illit").map { it.mbid })
    }


    @Test fun globalDiscoveryRetainsMoreThanTwelveVerifiedPublicPlaylists() {
        val rows = (1..22).joinToString(",") { n ->
            val id = n.toString(16).padStart(8, '0') + "-1111-4111-8111-111111111111"
            """{"playlist_mbid":"$id","title":"Collection number $n","public":true}"""
        }
        val parsed = parseListenBrainzPublicPlaylistSearch(
            """{"playlists":[$rows]}""",
        )!!
        assertEquals(22, parsed.size)
        assertEquals(22, parsed.map { it.mbid }.distinct().size)
    }


    @Test fun typedPublicSearchReusesVerifiedQueryForReentryButExpires() = runBlocking {
        var now = 1_000L
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
                override suspend fun searchWide(query: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return ListenBrainzPublicPlaylistHttp(200, realJspfSearchResponse())
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
        )
        assertEquals(listOf(rock), client.searchPublicQuery("K-pop").map { it.mbid })
        assertEquals(listOf(rock), client.searchPublicQuery("K-pop").map { it.mbid })
        assertEquals(1, calls)
        client.searchPublicQuery("Pop essentials")
        assertEquals(2, calls)
        now += 30 * 60_000L + 1L
        assertEquals(listOf(rock), client.searchPublicQuery("K-pop").map { it.mbid })
        assertEquals(3, calls)
    }

    @Test fun homeSkipsOnlyGenericFuzzyHitsAndRetriesDescriptiveQuery() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    calls += query
                    return ListenBrainzPublicPlaylistHttp(200, if (query == "essentials")
                        """{"playlists":[{"playlist_mbid":"$rock","title":"Music","public":true},
                            {"playlist_mbid":"$pop","title":"Rock","public":true}]}"""
                    else """{"playlists":[{"playlist_mbid":"$rock",
                        "title":"Classic rock essentials","public":true}]}""")
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf("Classic rock essentials"),
            client.searchHome()!!.map { it.title })
        assertEquals(listOf("essentials", "favorites"), calls)
    }

    @Test fun publicTypedSearchUsesWiderCandidatePoolWithoutShowingFuzzyFalseMatches() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    calls += "narrow"
                    return ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                }
                override suspend fun searchWide(query: String): ListenBrainzPublicPlaylistHttp {
                    calls += "wide"
                    return ListenBrainzPublicPlaylistHttp(200, """{"playlists":[
                      {"playlist_mbid":"$pop","title":"RANDOM fake collection","public":true},
                      {"playlist_mbid":"$rock","title":"Best ILLIT songs","public":true}
                    ]}""")
                }
                override suspend fun detail(mbid: String) =
                    ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(listOf(rock), client.searchPublicQuery("ILLIT").map { it.mbid })
        assertEquals(listOf("wide"), calls)
        assertTrue(client.searchPublicQuery("ab").isEmpty())
    }

}
