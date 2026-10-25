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
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val worldwide = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val original = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val sped = "3e619ba2-34f0-4649-83d8-05483e2fabe5"

    @Test fun artistToGroupToReleaseToMediumTrackGraphKeepsIdentityBoundaries() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String {
                requests += path to parameters
                return when (path) {
                    "release-group" -> """{"release-group-count":1,"release-group-offset":0,
                        "release-groups":[{"id":"$group","title":"SUPER REAL ME","primary-type":"EP",
                        "artist-credit":[{"name":"ILLIT"}]}]}"""
                    "release" -> """{"release-count":1,"release-offset":0,
                        "releases":[{"id":"$worldwide","title":"SUPER REAL ME","date":"2024-03-25","country":"XW"}]}"""
                    "release/$worldwide" -> """{"id":"$worldwide","title":"SUPER REAL ME","date":"2024-03-25",
                        "country":"XW","release-group":{"id":"$group","title":"SUPER REAL ME"},
                        "artist-credit":[{"name":"ILLIT"}],"media":[{"position":1,"tracks":[
                            {"id":"14f273b6-813b-4239-b0f8-c4b839b902c6",
                             "position":2,"title":"Magnetic","length":160000,
                             "recording":{"id":"$original","title":"Magnetic","length":160000}},
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
        assertEquals(worldwide, editions.value.items.single().musicBrainzId)
        assertEquals(group, detail.value.groupMbid)
        assertEquals(2, detail.value.tracks.size)
        assertEquals(listOf(2, 8), detail.value.tracks.map { it.trackNumber })
        assertEquals(listOf(original, sped), detail.value.tracks.map { it.recordingMbid })
        assertEquals(2, detail.value.tracks.map { it.trackMbid }.distinct().size)
        assertEquals("recordings+artist-credits+release-groups", requests.last().second["inc"])
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
}
