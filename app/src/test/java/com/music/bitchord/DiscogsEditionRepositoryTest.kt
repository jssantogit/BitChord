package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsEditionRepositoryTest {
    private val mbid = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val canonical = CanonicalEditionEvidence(
        musicBrainzReleaseId = mbid, title = "앨범", creditedArtist = "아티스트",
        status = "Official", barcodes = emptyList(), trackTitles = listOf("첫 곡"),
    )

    @Test fun disabledRepositoryDoesNotEvenAskMusicBrainzForExternalLinks() = runBlocking {
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = error("Disabled provider must do no I/O")
        }))
        val result = DiscogsEditionRepository(links, enabled = false).observe(canonical)
        assertEquals(DiscogsEditionEnrichment.Disabled, result)
    }

    @Test fun verifiedLinkedEditionHasSourceAttributionAndShortLivedEvidence() = runBlocking {
        var mbCalls = 0
        var discogsCalls = 0
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String {
                mbCalls++
                return """{"id":"$mbid","relations":[{
                    "type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                    "url":{"resource":"https://www.discogs.com/release/12-Album"}
                 }]}"""
            }
        }))
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                discogsCalls++
                return DiscogsHttpResponse(200,
                    """{"id":12,"title":"앨범","artists":[{"name":"아티스트"}],
                      "tracklist":[{"position":"1","title":"첫 곡"}]}""")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        var now = 1_000L
        val repository = DiscogsEditionRepository(links, client,
            enabled = true, nowMillis = { now })
        val first = repository.observe(canonical)
        assertTrue(first is DiscogsEditionEnrichment.Verified)
        val verified = first as DiscogsEditionEnrichment.Verified
        assertEquals("Data provided by Discogs.", verified.attributionText)
        assertEquals("https://www.discogs.com/release/12", verified.attributionUrl)
        assertFalse(verified.observation.canSupplyArtwork())
        assertFalse(verified.observation.canSupplyAudio())
        repository.observe(canonical)
        assertEquals(2, mbCalls) // relation checks remain source-authoritative
        assertEquals(1, discogsCalls) // edition data uses fresh cache
        now += DiscogsObservation.TTL_MILLIS
        repository.observe(canonical)
        assertEquals(2, discogsCalls) // expired source metadata must be re-fetched
    }

    @Test fun releaseGroupMasterProvidesVerifiedUnlinkedCandidateWhenEditionReallyMatches() = runBlocking {
        val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
        val paths = mutableListOf<String>()
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String {
                paths += path
                return when (path) {
                    "release/$mbid" -> """{"id":"$mbid","relations":[]}"""
                    "release-group/$group" -> """{"id":"$group","relations":[{
                        "type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
                        "url":{"resource":"https://www.discogs.com/master/987"}}]}"""
                    else -> error("Unexpected MB path: $path")
                }
            }
        })
        val discogsPaths = mutableListOf<String>()
        val discogs = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                discogsPaths += path
                return when (path) {
                    "masters/987" -> DiscogsHttpResponse(200,
                        """{"id":987,"title":"ILLIT - SUPER REAL ME",
                        "artists":[{"name":"ILLIT"}],"main_release":101}""")
                    "releases/101" -> DiscogsHttpResponse(200,
                        """{"id":101,"master_id":987,"title":"SUPER REAL ME",
                         "artists":[{"name":"ILLIT"}],"country":"South Korea","year":2024,
                         "labels":[{"name":"Belift Lab","catno":"CMDC12000"}],
                         "identifiers":[{"type":"Barcode","value":"8809704427821"}],
                         "formats":[{"name":"CD","descriptions":["EP"]}],
                         "tracklist":[{"position":"1","title":"My World"},
                                      {"position":"2","title":"Magnetic"}]}""")
                    else -> error("Unexpected Discogs path: $path")
                }
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = mbid,
            musicBrainzReleaseGroupId = group,
            title = "SUPER REAL ME",
            creditedArtist = "ILLIT",
            creditedArtistNames = listOf("ILLIT"),
            status = "Official",
            barcodes = listOf("8809704427821"),
            trackTitles = listOf("My World", "Magnetic"),
            country = "KR",
            releaseYear = 2024,
        )
        val repo = DiscogsEditionRepository(
            links = DiscogsMusicBrainzLinks(mb), discogs = discogs,
            enabled = true, nowMillis = { 1_000L },
        )
        val evidence = repo.observe(canonical)
        assertTrue(evidence is DiscogsEditionEnrichment.Verified)
        assertEquals("https://www.discogs.com/release/101",
            (evidence as DiscogsEditionEnrichment.Verified).attributionUrl)
        assertEquals(listOf("release/$mbid", "release-group/$group"), paths)
        assertEquals(listOf("masters/987", "releases/101"), discogsPaths)
    }

    @Test fun masterGroupLinkAloneNeverVerifiesARelease() = runBlocking {
        val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String =
                if (path.startsWith("release-group/")) """{"id":"$group","relations":[{
                    "type":"discogs","type-id":"99e550f3-5ab4-3110-b5b9-fe01d970b126",
                    "url":{"resource":"https://discogs.com/master/987"}}]}"""
                else """{"id":"$mbid","relations":[]}"""
        })
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                if (path.startsWith("masters/"))
                    DiscogsHttpResponse(200, """{"id":987,"title":"Album",
                    "artists":[{"name":"Artist"}],"main_release":102}""")
                else DiscogsHttpResponse(200, """{"id":102,"title":"Different Edition",
                    "artists":[{"name":"Different"}],"country":"US","year":2002,
                    "tracklist":[{"position":"1","title":"Unrelated"}]}""")
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = mbid, musicBrainzReleaseGroupId = group,
            title = "Album", creditedArtist = "Artist",
            creditedArtistNames = listOf("Artist"), status = "Official",
            barcodes = listOf("8809704427821"), trackTitles = listOf("Track"),
            country = "KR", releaseYear = 2024,
        )
        assertFalse(DiscogsEditionRepository(
            DiscogsMusicBrainzLinks(mb), client, enabled = true,
        ).observe(canonical) is DiscogsEditionEnrichment.Verified)
    }

    @Test fun mismatchedEditionDoesNotShowDiscogsAsVerified() = runBlocking {
        val links = DiscogsMusicBrainzLinks(MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String =
                """{"id":"$mbid","relations":[{
                    "type":"discogs","type-id":"4a78823c-1c53-4176-a5f3-58026c76f2bc",
                    "url":{"resource":"https://www.discogs.com/release/12"}}]}"""
        }))
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                DiscogsHttpResponse(200, """{"id":12,"title":"Different remix",
                    "artists":[{"name":"Another artist"}]}""")
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        assertEquals(DiscogsEditionEnrichment.Conflict,
            DiscogsEditionRepository(links, client, enabled = true).observe(canonical))
    }
}
