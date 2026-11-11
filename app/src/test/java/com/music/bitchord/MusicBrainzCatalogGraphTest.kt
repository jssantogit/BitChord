package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCatalogGraphTest {
    @Test
    fun graphPaginationUsesRawRowsNotOnlyUsableHits() = runBlocking {
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String =
                """{"release-count":20,"release-offset":0,"releases":[
                    {"id":"bad","title":"Invalid"},
                    {"id":"$worldwide","title":"SUPER REAL ME",
                     "cover-art-archive":{"front":false}},
                    {"id":"$worldwide","title":"SUPER REAL ME"},
                    {"id":"271b31f8-8ab6-483c-97d9-8f0fabb2d4f4","title":"SUPER REAL ME",
                     "cover-art-archive":{"front":true}}
                ]}"""
        }))
        val page = (graph.browseGroupReleases(group) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>).value
        assertEquals(2, page.items.size)
        assertEquals(false, page.items.first().hasFrontCover)
        assertEquals(true, page.items.last().hasFrontCover)
        assertEquals(4, page.consumedCount)
        assertEquals(4, page.nextOffset)
    }

    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val worldwide = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val original = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val sped = "3e619ba2-34f0-4649-83d8-05483e2fabe5"

    @Test fun artistOfficialEditionBatchProvidesExactGroupEdgesForFastArtworkEnrichment() = runBlocking {
        val artist = "b51c672b-85e0-48fe-8648-470a2422229f"
        var calls = 0
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String {
                    calls++
                    assertEquals("release", path)
                    assertEquals(artist, parameters["artist"])
                    assertEquals("official", parameters["status"])
                    assertEquals("release-groups+media", parameters["inc"])
                    return """{"release-count":3,"release-offset":0,"releases":[
                        {"id":"$worldwide","title":"SUPER REAL ME","status":"Official",
                         "cover-art-archive":{"front":true,"artwork":true},
                         "release-group":{"id":"$group"}},
                        {"id":"bad-id","title":"SUPER REAL ME","status":"Official",
                         "release-group":{"id":"$group"}},
                        {"id":"271b31f8-8ab6-483c-97d9-8f0fabb2d4f4","title":"EP video",
                         "status":"Bootleg","release-group":{"id":"$group"}}
                    ]}"""
                }
            },
        ))
        val output = graph.browseOfficialReleasesForArtist(artist, limit = 100) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>
        assertEquals(1, calls)
        assertEquals(3, output.value.consumedCount)
        assertEquals(2, output.value.items.size)
        assertEquals(group, output.value.items.first().releaseGroupMbid)
        assertEquals(true, output.value.items.first().hasFrontCover)
        assertEquals(listOf(worldwide), output.value.items
            .filter { it.status.equals("Official", ignoreCase = true) }
            .map { it.musicBrainzId })
    }

    @Test fun frontPresenceFlagAloneDoesNotClaimThatImageTypesAreApproved() {
        // Positive metadata only allows the archive JSON to be checked.
        // It is not enough to construct a URL or classify a Poster as a sleeve.
        val release = MusicBrainzReleaseHit(worldwide, "SUPER REAL ME", "XW",
            "2024-03-25", "ILLIT", status = "Official", hasFrontCover = true)
        assertEquals(true, release.hasFrontCover)
        assertEquals(false, release.copy(hasFrontCover = false).hasFrontCover)
        assertEquals(null, release.copy(hasFrontCover = null).hasFrontCover)
    }


    @Test fun artistToGroupToReleaseToMediumTrackGraphKeepsIdentityBoundaries() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                requests += path to parameters
                return when (path) {
                    "release-group" -> """{"release-group-count":1,"release-group-offset":0,
                        "release-groups":[{"id":"$group","title":"SUPER REAL ME","primary-type":"EP",
                        "secondary-types":["Remix","Compilation"],
                        "artist-credit":[{"name":"ILLIT"}]}]}"""
                    "release" -> """{"release-count":1,"release-offset":0,
                        "releases":[{"id":"$worldwide","title":"SUPER REAL ME","date":"2024-03-25","country":"XW"}]}"""
                    "release/$worldwide" -> """{"id":"$worldwide","title":"SUPER REAL ME","date":"2024-03-25",
                        "country":"XW","release-group":{"id":"$group","title":"SUPER REAL ME"},
                        "artist-credit":[{"name":"ILLIT"}],"media":[{"position":1,"tracks":[
                            {"id":"14f273b6-813b-4239-b0f8-c4b839b902c6",
                             "position":2,"title":"Magnetic","length":160000,
                             "artist-credit":[{"name":"ILLIT feat. Guest"}],
                             "recording":{"id":"$original","title":"Magnetic","length":160000,
                             "artist-credit":[{"name":"ILLIT"}]}},
                            {"id":"7d950448-969b-4d73-a00f-fec131238b5d",
                             "position":8,"title":"Magnetic (sped up)","length":124000,
                             "recording":{"id":"$sped","title":"Magnetic (sped up)","length":124000}}
                        ]}]}"""
                    else -> error("Unexpected endpoint $path")
                }
            }
        }
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = transport))
        val artistGroups = graph.browseReleaseGroupsForArtist("b51c672b-85e0-48fe-8648-470a2422229f") as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>>
        val editions = graph.browseGroupReleases(group) as MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>
        val detail = graph.lookupRelease(worldwide) as MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>
        assertEquals(group, artistGroups.value.items.single().musicBrainzId)
        assertEquals("Remix|Compilation", artistGroups.value.items.single().secondaryTypes)
        // Mirror MusicBrainz's official website artist release-group policy
        // instead of fetching promotional/bootleg-only discography groups.
        assertEquals("website-default", requests.first().second["release-group-status"])
        assertEquals(worldwide, editions.value.items.single().musicBrainzId)
        assertEquals(group, detail.value.groupMbid)
        assertEquals(2, detail.value.tracks.size)
        assertEquals(listOf(2, 8), detail.value.tracks.map { it.trackNumber })
        assertEquals(listOf(original, sped), detail.value.tracks.map { it.recordingMbid })
        assertEquals("ILLIT feat. Guest", detail.value.tracks.first().artistDisplay)
        assertEquals("ILLIT", detail.value.tracks.first().recordingArtistDisplay)
        assertEquals(2, detail.value.tracks.map { it.trackMbid }.distinct().size)
        assertEquals("recordings+artist-credits+release-groups", requests.last().second["inc"])
    }

    @Test fun browseMediaFormatsIdentifyOnlyExplicitlyVideoOnlyOfficialEditions() = runBlocking {
        val video = "11111111-1111-4111-8111-111111111111"
        val audio = "22222222-2222-4222-8222-222222222222"
        val mixed = "33333333-3333-4333-8333-333333333333"
        val ambiguous = "44444444-4444-4444-8444-444444444444"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    assertEquals("release", path)
                    assertEquals(group, parameters["release-group"])
                    assertEquals("media", parameters["inc"])
                    return """{"release-count":4,"release-offset":0,"releases":[
                        {"id":"$video","title":"Same title","status":"Official",
                         "media":[{"format":"DVD-Video"}]},
                        {"id":"$audio","title":"Same title","status":"Official",
                         "media":[{"format":"Digital Media"}]},
                        {"id":"$mixed","title":"Same title","status":"Official",
                         "media":[{"format":"DVD-Video"},{"format":"CD"}]},
                        {"id":"$ambiguous","title":"Same title","status":"Official",
                         "media":[{"format":"Blu-ray"}]}
                    ]}"""
                }
            },
        ))
        val page = (graph.browseGroupReleases(group) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>).value
        assertEquals(4, page.items.size)
        assertEquals(true, page.items[0].isVideoOnlyMedia)
        // Digital Media is container-agnostic: video recordings can use it.
        assertEquals(null, page.items[1].isVideoOnlyMedia)
        assertEquals(false, page.items[2].isVideoOnlyMedia)
        assertEquals(null, page.items[3].isVideoOnlyMedia)
    }

    @Test fun lookupKeepsVideoRecordingEvidenceDistinctFromDigitalAudio() = runBlocking {
        val videoTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val videoRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val audioTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val audioRecording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    assertEquals("release/$worldwide", path)
                    return """{"id":"$worldwide","title":"Same title",
                        "release-group":{"id":"$group"},
                        "media":[{"format":"Digital Media","tracks":[
                            {"id":"$videoTrack","title":"Music video",
                             "recording":{"id":"$videoRecording","title":"Music video",
                                          "video":true}},
                            {"id":"$audioTrack","title":"Original audio",
                             "recording":{"id":"$audioRecording","title":"Original audio",
                                          "video":false}}
                        ]}]}"""
                }
            },
        ))
        val detail = (graph.lookupRelease(worldwide) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals(false, detail.isVideoOnlyContent)
        assertEquals(2, detail.tracks.size)
    }

    @Test fun digitalEditionWithOnlyVideoRecordingsStaysTypedButNotAudio() = runBlocking {
        val videoTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val videoRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Official single",
                    "release-group":{"id":"$group"},
                    "media":[{"format":"Digital Media","tracks":[{
                        "id":"$videoTrack","title":"Music video",
                        "recording":{"id":"$videoRecording","title":"Music video",
                                     "video":true}
                    }]}]}"""
            },
        ))
        val detail = (graph.lookupRelease(worldwide) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals(true, detail.isVideoOnlyContent)
        assertEquals(videoRecording, detail.tracks.single().recordingMbid)
    }


    @Test fun partialTracklistCannotCertifyVideoOnlyFromVisibleRecordingFlags() = runBlocking {
        val videoTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val videoRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Video evidence incomplete",
                    "release-group":{"id":"$group"},
                    "media":[{"format":"Digital Media","track-count":2,"tracks":[{
                        "id":"$videoTrack","title":"Only visible video",
                        "recording":{"id":"$videoRecording","title":"Video","video":true}
                    }]}]}"""
            },
        ))
        val detail = (graph.lookupRelease(worldwide) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals(1, detail.tracks.size)
        // There is another unreturned Track; never claim every Recording is video.
        assertEquals(null, detail.isVideoOnlyContent)
    }

    @Test fun contradictoryVideoFormatAndRecordingFlagsRemainInconclusive() = runBlocking {
        val trackMbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recordingMbid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        for (format in listOf("DVD-Video", "CD")) {
            val videoFlag = format == "CD"
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
                object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String = """{"id":"$worldwide","title":"Conflicting evidence",
                        "release-group":{"id":"$group"},
                        "media":[{"format":"$format","track-count":1,"tracks":[{
                            "id":"$trackMbid","title":"Conflicting recording",
                            "recording":{"id":"$recordingMbid","video":$videoFlag}
                        }]}]}"""
                },
            ))
            val detail = (graph.lookupRelease(worldwide) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            assertEquals(format, null, detail.isVideoOnlyContent)
        }
    }

    @Test fun invalidMbidRejectsBeforeNetworkAndFailedResponseDoesNotInventTracks() = runBlocking {
        var calls = 0
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                calls++
                return """{"id":"541adf5a-48d4-4f7b-a7bb-afd3cea5af27","title":"SUPER REAL ME","media":[{"tracks":[
                    {"id":"incorrect","recording":{"id":"invalid"}}]}]}"""
            }
        }))
        assertTrue(graph.lookupRelease("not-a-mbid") is MusicBrainzCatalogGraphResult.Failure)
        assertEquals(0, calls)
        val parsed = graph.lookupRelease(worldwide) as MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>
        assertEquals(emptyList<String>(), parsed.value.tracks.map { it.recordingMbid })
    }

    @Test fun graphPreservesNestedArtistCreditsWhenCreditDisplayNamesAreMissing() = runBlocking {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String = """{"id":"$worldwide","title":"SUPER REAL ME",
                "artist-credit":[{"artist":{"name":"ILLIT"}}],
                "media":[{"position":1,"tracks":[
                    {"id":"14f273b6-813b-4239-b0f8-c4b839b902c6","title":"Magnetic",
                    "recording":{"id":"$original","title":"Magnetic",
                    "artist-credit":[
                        {"artist":{"name":"ILLIT"},"joinphrase":" feat. "},
                        {"artist":{"name":"Guest"}}
                    ]}}
                ]}]}"""
        }
        val detail = (MusicBrainzCatalogGraph(
            MusicBrainzClient(transport = transport),
        ).lookupRelease(worldwide) as MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals("ILLIT", detail.artistDisplay)
        assertEquals("ILLIT feat. Guest", detail.tracks.single().artistDisplay)
        assertEquals("ILLIT feat. Guest", detail.tracks.single().recordingArtistDisplay)
    }


    @Test fun browseDeduplicatesByEditionMbidWithoutChangingServerCursor() = runBlocking {
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"release-count":30,"release-offset":0,"releases":[
                    {"id":"$worldwide","title":"SUPER REAL ME"},
                    {"id":"$worldwide","title":"SUPER REAL ME (conflicting title)"}
                ]}"""
            },
        ))
        val page = (graph.browseGroupReleases(group) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>).value
        assertEquals(listOf(worldwide), page.items.map { it.musicBrainzId })
        assertEquals(2, page.consumedCount)
        assertEquals(2, page.nextOffset)
    }

    @Test fun conflictingTrackMbidInOneReleaseFailsClosedBeforePersistence() = runBlocking {
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"SUPER REAL ME","media":[{"tracks":[
                    {"id":"14f273b6-813b-4239-b0f8-c4b839b902c6",
                     "title":"Magnetic","recording":{"id":"$original"}},
                    {"id":"14f273b6-813b-4239-b0f8-c4b839b902c6",
                     "title":"Magnetic (sped up)","recording":{"id":"$sped"}}
                ]}]}"""
            },
        ))
        val result = graph.lookupRelease(worldwide)
        assertTrue(result is MusicBrainzCatalogGraphResult.Failure)
        assertEquals(
            com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
            (result as MusicBrainzCatalogGraphResult.Failure).reason,
        )
    }

    @Test fun recordingCoverRequiresExplicitReleaseRelation() = runBlocking {
        val requests = mutableListOf<String>()
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    requests += path + ":" + parameters["inc"]
                    return """{"id":"$original","releases":[
                        {"id":"$sped","status":"Bootleg","date":"2024-03-01"},
                        {"id":"$worldwide","status":"Official","date":"2024-03-25"}
                    ]}"""
                }
            },
        ))
        assertEquals(listOf(worldwide, sped), graph.lookupRecordingCoverReleases(original))
        assertEquals(listOf("recording/$original:releases"), requests)
        assertEquals(emptyList<String>(), graph.lookupRecordingCoverReleases("invalid"))
        assertEquals(1, requests.size)
    }

    @Test fun unrelatedRecordingCannotBorrowAnotherRecordingsCover() = runBlocking {
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$sped","releases":[{"id":"$worldwide","status":"Official"}]}"""
            },
        ))
        assertEquals(emptyList<String>(), graph.lookupRecordingCoverReleases(original))
    }

}
