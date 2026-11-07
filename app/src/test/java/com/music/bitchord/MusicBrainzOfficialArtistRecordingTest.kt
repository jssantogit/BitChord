package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MetadataLookupResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzOfficialArtistRecordingTest {
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val release = "11111111-1111-4111-8111-111111111111"
    private val another = "22222222-2222-4222-8222-222222222222"

    private suspend fun lookup(video: Boolean, releases: String) : Pair<String?, String?> {
        var inc: String? = null
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                inc = parameters["inc"]
                return """{"id":"$recording","title":"Official song","video":$video,
                  "artist-credit":[{"name":"Artist","artist":{"id":"33333333-3333-4333-8333-333333333333","name":"Artist"}}],
                  "releases":[$releases]}"""
            }
        }
        val provider = MusicBrainzMetadataEnrichmentProvider(
            MusicBrainzClient(transport = transport,
                rateLimiter = MusicBrainzRateLimiter(
                    nowMillis = { 1_000L }, delayMillis = {},
                ),
            ),
        )
        val result = provider.lookupOfficialAudioRecording(recording)
        assertTrue(result is MetadataLookupResult.Success)
        return (result as MetadataLookupResult.Success).value.officialAudioReleaseMbid to inc
    }

    @Test fun retainsOnlyOfficialAlbumSingleOrEpEditionForArtistChart() = runBlocking {
        val unofficial = """{"id":"$another","status":"Bootleg",
          "release-group":{"primary-type":"Album"}}"""
        val official = """{"id":"$release","status":"Official",
          "release-group":{"primary-type":"Single"}}"""
        val (selected, inc) = lookup(false, "$unofficial,$official")
        assertEquals(release, selected)
        assertEquals("artist-credits+releases+release-groups", inc)
    }

    @Test fun rejectsVideoRemixCompilationAndMissingReleaseProof() = runBlocking {
        val live = """{"id":"$release","status":"Official",
          "release-group":{"primary-type":"Album","secondary-types":["Live"]}}"""
        val compilation = """{"id":"$another","status":"Official",
          "release-group":{"primary-type":"Album","secondary-types":["Compilation"]}}"""
        assertNull(lookup(false, "$live,$compilation").first)
        assertNull(lookup(true, """{"id":"$release","status":"Official",
          "release-group":{"primary-type":"Album"}}""").first)
        assertNull(lookup(false, "").first)
    }
}
