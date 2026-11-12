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

    @Test fun browseExplicitEmptySecondaryTypesDiffersFromOmittedTypes() = runBlocking {
        val artist = "b51c672b-85e0-48fe-8648-470a2422229f"
        var includeEmpty = true
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    val field = if (includeEmpty) ""","secondary-types":[]""" else ""
                    return """{"release-group-count":1,"release-group-offset":0,
                        "release-groups":[{"id":"$group","title":"Official album",
                        "primary-type":"Album"$field}]}"""
                }
            },
        ))
        val knownEmpty = (graph.browseReleaseGroupsForArtist(artist) as
            MusicBrainzCatalogGraphResult.Success<
                MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>
            >).value.items.single()
        assertEquals("", knownEmpty.secondaryTypes)
        includeEmpty = false
        val notSupplied = (graph.browseReleaseGroupsForArtist(artist) as
            MusicBrainzCatalogGraphResult.Success<
                MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>
            >).value.items.single()
        assertEquals(null, notSupplied.secondaryTypes)
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
        val result = graph.lookupRelease(worldwide)
        // Another unreturned Track cannot certify every Recording is video
        // or be promoted into a complete cached edition.
        assertTrue(result is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun invalidRecordingIdentityCannotBecomePositiveVideoOnlyEvidence() = runBlocking {
        val trackMbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Malformed video-only source",
                    "release-group":{"id":"$group"},
                    "media":[{"format":"Digital Media","track-count":1,"tracks":[{
                        "id":"$trackMbid","title":"Unverified video",
                        "recording":{"id":"not-an-mbid","video":true}
                    }]}]}"""
            },
        ))
        val result = graph.lookupRelease(worldwide)
        // The declared track exists, but its recording identity is invalid.
        assertTrue(result is MusicBrainzCatalogGraphResult.Failure)
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

    @Test fun invalidMbidRejectsBeforeNetworkAndExplicitEmptyMediaDoesNotInventTracks() = runBlocking {
        var calls = 0
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                calls++
                return """{"id":"541adf5a-48d4-4f7b-a7bb-afd3cea5af27","title":"SUPER REAL ME","media":[]}"""
            }
        }))
        assertTrue(graph.lookupRelease("not-a-mbid") is MusicBrainzCatalogGraphResult.Failure)
        assertEquals(0, calls)
        val parsed = graph.lookupRelease(worldwide) as MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>
        assertEquals(emptyList<String>(), parsed.value.tracks.map { it.recordingMbid })
    }

    // Synthetic malformed responses for incomplete or incompatible release structure.
    @Test fun incompleteMediaAndInvalidExplicitFieldTypesCannotBecomeEmptySuccess() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val validTrack = """{"id":"$track","position":1,"title":"Track","recording":{"id":"$recording"}}"""
        val invalidResponses = listOf(
            // A missing media field is not proof of a release with no media.
            """{"id":"$worldwide","title":"Missing media"}""",
            """{"id":"$worldwide","title":"Wrong media type","media":"not-an-array"}""",
            // A medium without tracks is incomplete; explicit [] remains valid.
            """{"id":"$worldwide","title":"Missing tracks","media":[{}]}""",
            """{"id":"$worldwide","title":"Wrong tracks type","media":[{"tracks":"not-an-array"}]}""",
            """{"id":"$worldwide","title":"Negative count","media":[{"track-count":-1,"tracks":[$validTrack]}]}""",
            """{"id":"$worldwide","title":"Non-numeric count","media":[{"track-count":"many","tracks":[$validTrack]}]}""",
        )

        invalidResponses.forEach { response ->
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
                object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String = response
                },
            ))
            assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
        }
    }

    // Synthetic valid release structure: explicit empty arrays represent no track rows.
    @Test fun explicitEmptyMediaAndTracksRemainValid() = runBlocking {
        val responses = listOf(
            """{"id":"$worldwide","title":"No media","media":[]}""",
            """{"id":"$worldwide","title":"Empty medium","media":[{"position":1,"tracks":[] }]}""",
        )
        responses.forEach { response ->
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
                object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String = response
                },
            ))
            val detail = (graph.lookupRelease(worldwide) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            assertEquals(emptyList<String>(), detail.tracks.map { it.trackMbid })
        }
    }

    // Synthetic malformed positions: only omission permits positional fallback.
    @Test fun explicitlyInvalidMediumAndTrackPositionsCannotUseFallbacks() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val trackJson = """{"id":"$track","title":"Track","recording":{"id":"$recording"}}"""
        val invalidResponses = listOf(
            """{"id":"$worldwide","title":"Null medium position","media":[{"position":null,"tracks":[$trackJson]}]}""",
            """{"id":"$worldwide","title":"Text medium position","media":[{"position":"one","tracks":[$trackJson]}]}""",
            """{"id":"$worldwide","title":"Negative medium position","media":[{"position":-1,"tracks":[$trackJson]}]}""",
            """{"id":"$worldwide","title":"Null track position","media":[{"tracks":[{"id":"$track","position":null,"title":"Track","recording":{"id":"$recording"}}]}]}""",
            """{"id":"$worldwide","title":"Text track position","media":[{"tracks":[{"id":"$track","position":"first","title":"Track","recording":{"id":"$recording"}}]}]}""",
            """{"id":"$worldwide","title":"Negative track position","media":[{"tracks":[{"id":"$track","position":-1,"title":"Track","recording":{"id":"$recording"}}]}]}""",
        )

        invalidResponses.forEach { response ->
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
                object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String = response
                },
            ))
            assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
        }
    }

    // Synthetic schema-valid zero positions: retain the explicit values rather than inventing fallbacks.
    @Test fun zeroMediumAndTrackPositionsArePreservedAsNonNegativeValues() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Synthetic zero positions","media":[
                    {"position":0,"tracks":[
                        {"id":"$track","position":0,"title":"Track","recording":{"id":"$recording"}}
                    ]}
                ]}"""
            },
        ))

        val detail = (graph.lookupRelease(worldwide) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals(0, detail.tracks.single().mediumNumber)
        assertEquals(0, detail.tracks.single().trackNumber)
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


    @Test fun declaredTrackCountLargerThanReturnedRowsMustNotBecomeEditionDetail() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Truncated edition",
                    "media":[{"position":1,"track-count":2,"tracks":[{
                        "id":"$track","title":"Only first track",
                        "recording":{"id":"$recording","title":"Only first track"}
                    }]}]}"""
            },
        ))
        // Partial source data must not be accepted as a complete edition, cached
        // as a complete tracklist or preferred over a previously cached edition.
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun declaredTrackCountWithUndecodableRecordingCannotBecomeCompleteDetail() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Two declared tracks",
                    "media":[{"position":1,"track-count":2,"tracks":[
                        {"id":"$track","title":"Valid","recording":{"id":"$recording"}},
                        {"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                         "title":"Undecodable","recording":{"id":"not-an-mbid"}}
                    ]}]}"""
            },
        ))
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun missingTracksOfOneDeclaredMediumCannotPublishOtherMediumAsComplete() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Two media edition",
                    "media":[
                        {"position":1,"track-count":1,"tracks":[{
                            "id":"$track","title":"Disc one",
                            "recording":{"id":"$recording"}
                        }]},
                        {"position":2,"track-count":1}
                    ]}"""
            },
        ))
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun declaredTrackCountCannotBeSatisfiedByDuplicateTrackIds() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Duplicate track identities",
                    "media":[{"track-count":2,"tracks":[
                        {"id":"$track","title":"Same","recording":{"id":"$recording"}},
                        {"id":"$track","title":"Same","recording":{"id":"$recording"}}
                    ]}]}"""
            },
        ))
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    // Synthetic MusicBrainz-shaped response for an undecodable Track row.
    @Test fun undecodableTrackWithoutDeclaredCountCannotPublishPartialEdition() = runBlocking {
        val validTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val validRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val invalidTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Incomplete edition",
                    "media":[{"position":1,"tracks":[
                        {"id":"$validTrack","position":2,"title":"Available",
                         "recording":{"id":"$validRecording","title":"Available"}},
                        {"id":"$invalidTrack","position":9,"title":"Missing recording identity",
                         "recording":{"title":"Undecodable"}}
                    ]}]}"""
            },
        ))

        // A partial result must not be handed to the cache writer, where it
        // could replace a previously complete offline tracklist.
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    // Synthetic MusicBrainz-shaped responses for missing/duplicate Track IDs.
    @Test fun invalidOrDuplicateTrackIdentityWithoutDeclaredCountCannotPublishPartialEdition() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val otherTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val fixtures = listOf(
            // A missing Track MBID is currently filtered from the decoded list.
            """{"id":"$worldwide","title":"Missing Track identity","media":[{"tracks":[
                {"id":"$track","position":3,"title":"Available","recording":{"id":"$recording"}},
                {"position":8,"title":"Missing Track ID","recording":{"id":"$recording"}}
            ]}]}""",
            // Identical duplicate Track MBIDs currently collapse via distinctBy.
            """{"id":"$worldwide","title":"Duplicate Track identity","media":[{"tracks":[
                {"id":"$track","position":3,"title":"Available","recording":{"id":"$recording"}},
                {"id":"$track","position":3,"title":"Available","recording":{"id":"$recording"}},
                {"id":"$otherTrack","position":12,"title":"Also available","recording":{"id":"$recording"}}
            ]}]}""",
        )

        fixtures.forEach { response ->
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
                object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String = response
                },
            ))
            assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
        }
    }

    // Synthetic MusicBrainz-shaped response with sparse positions and shared Recordings.
    @Test fun validTracklistWithoutDeclaredCountsIsOrderedByOfficialMediumAndTrackPositions() = runBlocking {
        val sharedRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val recording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val mediumTwoLaterTrack = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val mediumOneTieLaterId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val mediumOneTieEarlierId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val mediumOneEarlierTrack = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Valid reordered edition",
                    "media":[
                        {"position":2,"tracks":[
                            {"id":"$mediumTwoLaterTrack","position":11,"title":"Disc two","recording":{"id":"$recording"}}
                        ]},
                        {"position":1,"tracks":[
                            {"id":"$mediumOneTieLaterId","position":7,"title":"Tie later ID","recording":{"id":"$sharedRecording"}},
                            {"id":"$mediumOneEarlierTrack","position":2,"title":"Earlier position","recording":{"id":"$recording"}},
                            {"id":"$mediumOneTieEarlierId","position":7,"title":"Tie earlier ID","recording":{"id":"$sharedRecording"}}
                        ]}
                    ]}"""
            },
        ))

        val detail = (graph.lookupRelease(worldwide) as
            MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
        assertEquals(
            listOf(mediumOneEarlierTrack, mediumOneTieEarlierId, mediumOneTieLaterId, mediumTwoLaterTrack),
            detail.tracks.map { it.trackMbid },
        )
        assertEquals(listOf(1, 1, 1, 2), detail.tracks.map { it.mediumNumber })
        assertEquals(listOf(2, 7, 7, 11), detail.tracks.map { it.trackNumber })
        assertEquals(sharedRecording, detail.tracks[1].recordingMbid)
        assertEquals(sharedRecording, detail.tracks[2].recordingMbid)
    }

    @Test fun nonZeroTrackOffsetCannotPublishApparentlyCompleteEdition() = runBlocking {
        val firstTrack = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val firstRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val secondTrack = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val secondRecording = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Synthetic offset page",
                    "media":[{"position":1,"track-count":2,"track-offset":1,"tracks":[
                        {"id":"$firstTrack","position":1,"title":"First visible row","recording":{"id":"$firstRecording"}},
                        {"id":"$secondTrack","position":2,"title":"Second visible row","recording":{"id":"$secondRecording"}}
                    ]}]}"""
            },
        ))

        // Synthetic fixture: a non-zero browse offset cannot certify a full
        // edition or be handed to persistence over an existing cached tracklist.
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun nonObjectMediumCannotBeIgnoredBesideValidMediumWithoutDeclaredCounts() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Synthetic malformed media entry",
                    "media":[
                        {"position":1,"tracks":[
                            {"id":"$track","position":2,"title":"Valid medium track","recording":{"id":"$recording"}}
                        ]},
                        null
                    ]}"""
            },
        ))

        // Synthetic malformed response: silently discarding the non-object
        // medium would publish an incomplete detail and risk replacing cache.
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

    @Test fun duplicateTrackMbidWithinMediumWithoutCountCannotCollapseIntoOneTrack() = runBlocking {
        val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val recording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport =
            object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"id":"$worldwide","title":"Synthetic repeated track identity",
                    "media":[{"position":1,"tracks":[
                        {"id":"$track","position":4,"title":"Repeated track","recording":{"id":"$recording"}},
                        {"id":"$track","position":4,"title":"Repeated track","recording":{"id":"$recording"}}
                    ]}]}"""
            },
        ))

        // Synthetic fixture: identical Track rows in one medium must be
        // rejected even though the medium declares no track-count.
        assertTrue(graph.lookupRelease(worldwide) is MusicBrainzCatalogGraphResult.Failure)
    }

}
