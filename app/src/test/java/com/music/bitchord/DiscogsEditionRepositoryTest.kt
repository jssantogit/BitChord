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
