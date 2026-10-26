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
                    "release-group" -> """{"count":1,"offset":0,"release-groups":[{"id":"529f87a1-1081-48a4-8ee1-3c08de229858","title":"SUPER REAL ME","primary-type":"EP","secondary-types":["Live","Remix"]}]}"""
                    else -> error("Unexpected MusicBrainz endpoint $path")
                }
        }
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(transport = transport))
        val artists = search.searchArtists("aespa") as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit>
        val groups = search.searchReleaseGroups("SUPER REAL ME") as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit>
        assertEquals("b51c672b-85e0-48fe-8648-470a2422229f", artists.page.items.single().musicBrainzId)
        assertEquals("529f87a1-1081-48a4-8ee1-3c08de229858", groups.page.items.single().musicBrainzId)
        assertEquals("EP", groups.page.items.single().primaryType)
        assertEquals("Live|Remix", groups.page.items.single().secondaryTypes)
    }


    @Test fun recordingSearchKeepsVerifiedReleaseCoverAssociationWithoutGuessingTitle() = runBlocking {
        val recordingId = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
        val release1 = "11111111-1111-4111-8111-111111111111"
        val release2 = "22222222-2222-4222-8222-222222222222"
        val transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String =
                """{"count":1,"offset":0,"recordings":[{"id":"$recordingId","title":"Magnetic",
                "releases":[{"id":"$release2","status":"Official","date":"2025-01-01","release-group":{"primary-type":"EP"}},
                            {"id":"$release1","status":"Official","date":"2024-03-25","release-group":{"primary-type":"EP"}},
                            {"id":"untrusted","date":"2023-01-01"}]}]}"""
        }
        val result = MusicBrainzCatalogSearch(MusicBrainzClient(transport = transport))
            .searchRecordings("Magnetic") as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>
        assertEquals(release1, result.page.items.single().coverReleaseMbid)
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
        val songs = search.searchRecordingsForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>
        val groups = search.searchReleaseGroupsForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit>
        val editions = search.searchReleasesForArtistMbid(artistMbid) as MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit>
        assertEquals("Magnetic", songs.page.items.single().title)
        assertEquals("SUPER REAL ME", groups.page.items.single().title)
        assertEquals("SUPER REAL ME", editions.page.items.single().title)
        assertEquals(listOf("recording", "release-group", "release"), requested.map { it.first })
        assertTrue(requested.all { (path, query, dismax) ->
            query == (if (path == "recording") "arid:$artistMbid AND status:official"
                else "arid:$artistMbid") && dismax == "false"
        })
    }

    @Test
    fun releaseSearchCarriesOnlyValidatedReleaseGroupIdentityForArtFallback() = runBlocking {
        val release = "11111111-1111-4111-8111-111111111111"
        val group = "22222222-2222-4222-8222-222222222222"
        val invalid = "33333333-3333-4333-8333-333333333333"
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String) =
                    """{"count":2,"offset":0,"releases":[
                        {"id":"$release","title":"SUPER REAL ME","release-group":{"id":"$group"}},
                        {"id":"$invalid","title":"Unverified","release-group":{"id":"not-an-mbid"}}
                    ]}"""
            },
        ))
        val rows = (search.searchReleases("SUPER REAL ME") as
            MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit>)
            .page.items
        assertEquals(group, rows.first().releaseGroupMbid)
        assertEquals(null, rows.last().releaseGroupMbid)
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

    @Test fun recordingSearchRejectsUnofficialVideoAndRemixCandidatesForCuratedPresentation() = runBlocking {
        val artist = "NewJeans"
        val original = "11111111-1111-4111-8111-111111111111"
        val video = "22222222-2222-4222-8222-222222222222"
        val flip = "33333333-3333-4333-8333-333333333333"
        val performance = "44444444-4444-4444-8444-444444444444"
        val officialRemix = "55555555-5555-4555-8555-555555555555"
        val officialVideoRelease = "77777777-7777-4777-8777-777777777777"
        val release = "66666666-6666-4666-8666-666666666666"
        val search = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String,String>, userAgent: String) =
                    """{"count":6,"offset":0,"recordings":[
                      {"id":"$original","title":"Hurt","artist-credit":[{"name":"$artist"}],
                       "releases":[{"id":"$release","status":"Official","release-group":{"primary-type":"EP"}}]},
                      {"id":"$video","title":"Hurt","video":true,
                       "releases":[{"id":"$release","status":"Official","release-group":{"primary-type":"EP"}}]},
                      {"id":"$flip","title":"OMG (Jersey flip)",
                       "releases":[{"id":"$release","status":"Bootleg","release-group":{"primary-type":"Single"}}]},
                      {"id":"$performance","title":"Cool With You (performance ver.)",
                       "releases":[{"id":"$release","status":"Official","release-group":{"primary-type":"Single"}}]},
                      {"id":"$officialRemix","title":"Hurt (250 Remix)",
                       "releases":[{"id":"$release","status":"Official","release-group":{"primary-type":"Album"}}]},
                      {"id":"$officialVideoRelease","title":"Hurt",
                       "releases":[{"id":"$release","status":"Official","release-group":{"primary-type":"Other"}}]}
                    ]}"""
            }
        ))
        val rows = (search.searchRecordings(artist) as
            MusicBrainzCatalogSearchResult.Success<com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit>).page.items
        assertEquals(listOf("Hurt", "Hurt (250 Remix)"),
            rows.filter { com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRecording(it) }.map { it.title })
        assertEquals(release, rows.first().coverReleaseMbid)
        assertEquals(null, rows[1].coverReleaseMbid)
        assertEquals(null, rows[2].coverReleaseMbid)
        // A clean title and an Official release are insufficient: the
        // only known edition may be a film/video classified as Other.
        assertEquals(null, rows.last().coverReleaseMbid)
        assertTrue(!com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRecording(rows.last()))
    }

    @Test fun musicVideoPracticeAndUnverifiedRecordingAreNotPromotedToOfficialAudio() {
        val good = com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit(
            "11111111-1111-4111-8111-111111111111", "Super Shy", "NewJeans", 180000,
            hasOfficialAudioRelease = true,
        )
        val check: (com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit) -> Boolean = { com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRecording(it) }
        assertTrue(check(good))
        assertTrue(!check(good.copy(title = "New Jeans (dance practice fix ver.)")))
        assertTrue(!check(good.copy(title = "Hype Boy (DANIELLE&HAERIN ver.)")))
        assertTrue(!check(good.copy(title = "OMG (ric3show3r \"who is he?\" Jersey flip)")))
        assertTrue(!check(good.copy(title = "Hype Boy (sped up)")))
        assertTrue(!check(good.copy(isVideo = true)))
        assertTrue(!check(good.copy(hasOfficialAudioRelease = false)))
    }

}
