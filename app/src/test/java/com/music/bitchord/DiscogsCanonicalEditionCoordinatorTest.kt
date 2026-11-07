package com.music.bitchord

import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsCanonicalEditionCoordinatorTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val track = "14f273b6-813b-4239-b0f8-c4b839b902c6"

    private fun musicBrainzJson(valid: Boolean = true) = """{
        "id":"$mbid","title":"SUPER REAL ME","barcode":"036000291452",
        "status":"Official","artist-credit":[{"name":"ILLIT"}],
        "media-count":1,"media":[{"position":1,"track-count":2,"tracks":[
            {"id":"$track","title":"My World","recording":{"id":"$recording"}},
            {"id":"${if (valid) "7d950448-969b-4d73-a00f-fec131238b5d" else "BAD"}",
              "title":"Magnetic","recording":{"id":"3e619ba2-34f0-4649-83d8-05483e2fabe5"}}
        ]}]
    }"""

    @Test fun disabledCoordinatorNeverCallsEitherExternalService() = runBlocking {
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = error("No MusicBrainz fetch when gate is closed")
        })
        assertEquals(DiscogsEditionEnrichment.Disabled,
            DiscogsCanonicalEditionCoordinator(graph = MusicBrainzCatalogGraph(mb),
                enabled = false).observeRelease(mbid))
    }

    @Test fun onlyReleaseMbidsAndExactLocallyLinkedEditionAreAcceptedForUi() {
        assertEquals(mbid, discogsOnlineReleaseMbid("catalog:RELEASE:$mbid"))
        assertNull(discogsOnlineReleaseMbid("catalog:RELEASE_GROUP:$group"))
        assertNull(discogsOnlineReleaseMbid("catalog:ARTIST:$mbid"))
        assertNull(discogsOnlineReleaseMbid("catalog:RELEASE:$mbid:extra"))
        assertNull(discogsOnlineReleaseMbid("local:$mbid"))
        assertNull(discogsOnlineReleaseMbid("catalog:RELEASE:wrong"))
        val local = MusicBrainzReleaseEnrichmentEntity(
            releaseId = "local-edition-01", mbid = mbid, title = "SUPER REAL ME",
            artistDisplay = "ILLIT", releaseDate = "2024-03-25", country = "KR",
            status = "Official", fetchedAtEpochMs = 1000L,
        )
        assertEquals(mbid, discogsLibraryReleaseMbid("local-edition-01", local))
        assertNull(discogsLibraryReleaseMbid("another-release", local))
        assertNull(discogsLibraryReleaseMbid("local-edition-01", local.copy(mbid = group,
            status = "Bootleg")))
        assertNull(discogsLibraryReleaseMbid("local-edition-01", null))
    }

    @Test fun incompleteMusicBrainzReleaseStopsBeforeDiscogsRequest() = runBlocking {
        var discogsCalls = 0
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = musicBrainzJson(valid = false)
        })
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                discogsCalls++
                return DiscogsHttpResponse(200, "")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0))
        val repository = DiscogsEditionRepository(discogs = client, enabled = true)
        val coordinator = DiscogsCanonicalEditionCoordinator(
            graph = MusicBrainzCatalogGraph(mb), editions = repository, enabled = true,
        )
        assertEquals(DiscogsEditionEnrichment.Candidate, coordinator.observeRelease(mbid))
        assertEquals(0, discogsCalls)
    }

    @Test fun concurrentSameEditionReadersShareOneBoundedEvidenceFetch() = runBlocking {
        var graphCalls = 0
        var relationCalls = 0
        var discogsCalls = 0
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = when (parameters["inc"]) {
                "recordings+artist-credits+release-groups" -> {
                    graphCalls++
                    musicBrainzJson()
                }
                "url-rels" -> {
                    relationCalls++
                    """{"id":"$mbid","relations":[{"type":"discogs",
                       "type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                       "url":{"resource":"https://discogs.com/release/12345"}}]}"""
                }
                else -> error("Unexpected MusicBrainz query")
            }
        })
        val discogsClient = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                discogsCalls++
                return DiscogsHttpResponse(200, """{"id":12345,
                  "title":"ILLIT - SUPER REAL ME","artists":[{"name":"ILLIT"}],
                  "tracklist":[{"position":"1","title":"My World"},
                               {"position":"2","title":"Magnetic"}]}""")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0))
        var now = 1000L
        val coordinator = DiscogsCanonicalEditionCoordinator(
            graph = MusicBrainzCatalogGraph(mb),
            editions = DiscogsEditionRepository(
                links = DiscogsMusicBrainzLinks(mb),
                discogs = discogsClient, enabled = true, nowMillis = { now },
            ),
            enabled = true, nowMillis = { now },
        )
        coroutineScope {
            val first = async { coordinator.observeRelease(mbid) }
            val second = async { coordinator.observeRelease(mbid) }
            assertTrue(first.await() is DiscogsEditionEnrichment.Verified)
            assertTrue(second.await() is DiscogsEditionEnrichment.Verified)
        }
        assertEquals(1, graphCalls)
        assertEquals(1, relationCalls)
        assertEquals(1, discogsCalls)
        now += 20L * 60_000L
        // Re-check canonical evidence later, while source-specific Discogs
        // record may remain fresh in its own shorter cache.
        assertTrue(coordinator.observeRelease(mbid) is DiscogsEditionEnrichment.Verified)
        assertEquals(2, graphCalls)
        assertEquals(2, relationCalls)
        assertEquals(1, discogsCalls)
        coordinator.clear()
        assertTrue(coordinator.observeRelease(mbid) is DiscogsEditionEnrichment.Verified)
        assertEquals(3, graphCalls)
        assertEquals(2, discogsCalls) // rollback clears ONLY Discogs' in-memory evidence
    }

    @Test fun completeEditionCorroboratesDiscogsOnlyUsingRealMusicBrainzTracklist() = runBlocking {
        var calls = 0
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String {
                calls++
                return when (parameters["inc"]) {
                    "recordings+artist-credits+release-groups" -> musicBrainzJson()
                    "url-rels" -> """{"id":"$mbid","relations":[{
                        "type":"discogs",
                        "type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                        "url":{"resource":"https://www.discogs.com/release/12345"}
                    }]}"""
                    else -> error("Unexpected query to MusicBrainz: $parameters")
                }
            }
        })
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                assertEquals("releases/12345", path)
                return DiscogsHttpResponse(200, """{
                    "id":12345,"title":"ILLIT - SUPER REAL ME",
                    "artists":[{"name":"ILLIT"}],
                    "identifiers":[{"type":"Barcode","value":"036000291452"}],
                    "labels":[{"name":"BELIFT LAB","catno":"BEL-001"}],
                    "tracklist":[{"position":"1","title":"My World"},
                                 {"position":"2","title":"Magnetic"}]
                }""")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0))
        val repository = DiscogsEditionRepository(
            links = DiscogsMusicBrainzLinks(mb), discogs = client, enabled = true,
        )
        val result = DiscogsCanonicalEditionCoordinator(
            graph = MusicBrainzCatalogGraph(mb), editions = repository, enabled = true,
        ).observeRelease(mbid)
        assertTrue(result is DiscogsEditionEnrichment.Verified)
        assertEquals("https://www.discogs.com/release/12345",
            (result as DiscogsEditionEnrichment.Verified).attributionUrl)
        assertEquals(2, calls)
    }
}
