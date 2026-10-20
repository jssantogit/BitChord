package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicDecoderProfile
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicDecisionTransport
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeAudioRequest
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapabilitySnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlan
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlanner
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTranscodeDecisionEngine
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTranscodeDecisionOutcome
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTranscodeDecisionRejection
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSubsonicAuthenticatedTranscodeDecisionTest {
    private val authSecret = "this-secret-must-never-escape"
    private val config = OpenSubsonicAccountConfig(
        "home", "https://music.example.test", OpenSubsonicAuth.ApiKey(authSecret),
    )
    private fun runtime() = OpenSubsonicRuntimeAudioRequest(
        recordingId = "recording-1",
        mediaAssetId = "asset-1",
        providerKey = "opensubsonic:home",
        endpointUrl = "https://music.example.test/rest/stream.view",
        sourceKey = "provider-song-99",
        auth = config.auth,
        saltFactory = { "salt" },
    )
    private fun variant() = (
        OpenSubsonicStreamVariantPlanner.extensionRequested(
            "recording-1", "asset-1", "opensubsonic:home", 7,
            com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence.ADVERTISED,
            OpenSubsonicDecoderProfile.MP3_HTTP, 192_000,
        ) as OpenSubsonicStreamVariantPlan.Accepted
    ).variant
    private suspend fun capability(): OpenSubsonicStreamCapabilitySnapshot =
        OpenSubsonicProvider(config, object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>) =
                """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[{"name":"transcoding","versions":[1]}]}}"""
        }).streamCapabilityEvidence(7)
    private fun reply(codec: String = "mp3", protocol: String = "http", allowed: Boolean = true) =
        """{"subsonic-response":{"status":"ok","transcodeDecision":{"canDirectPlay":false,"canTranscode":$allowed,"transcodeParams":"signed%2Ftoken%2Bopaque","transcodeStream":{"protocol":"$protocol","container":"mp3","codec":"$codec","audioBitrate":192000}}}}"""

    @Test fun decisionIsPostWithRealCapabilityBodyAndAuthenticationIsPrivate() = runBlocking {
        var calls = 0
        val engine = OpenSubsonicTranscodeDecisionEngine(
            transport = object : OpenSubsonicDecisionTransport {
                override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String {
                    calls++
                    assertEquals("https://music.example.test/rest/getTranscodeDecision.view", endpoint)
                    assertEquals("provider-song-99", parameters["mediaId"])
                    assertEquals("song", parameters["mediaType"])
                    assertEquals(authSecret, parameters["apiKey"])
                    assertTrue(body.contains("transcodingProfiles"))
                    assertTrue(body.contains("192000"))
                    assertFalse(body.contains(authSecret))
                    assertFalse(body.contains("provider-song-99"))
                    return reply()
                }
            },
        )
        val result = engine.negotiate(runtime(), variant(), capability(), config) { it == OpenSubsonicDecoderProfile.MP3_HTTP }
        assertEquals(1, calls)
        val ready = result as OpenSubsonicTranscodeDecisionOutcome.Ready
        assertEquals("recording-1", ready.recordingId)
        assertEquals("asset-1", ready.mediaAssetId)
        assertEquals("opensubsonic:home", ready.providerKey)
        assertFalse(result.toString().contains(authSecret))
        assertFalse(result.toString().contains("signed%2Ftoken"))
        assertFalse(result.toString().contains("provider-song-99"))
    }

    @Test fun serverDenialAndUnsupportedHlsFailClosed() = runBlocking {
        for (response in listOf(reply(allowed = false), reply(protocol = "hls"), reply(codec = "aac"))) {
            val engine = OpenSubsonicTranscodeDecisionEngine(
                transport = object : OpenSubsonicDecisionTransport {
                    override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String) = response
                },
            )
            val result = engine.negotiate(runtime(), variant(), capability(), config) { true }
            assertTrue(result is OpenSubsonicTranscodeDecisionOutcome.Rejected)
            assertEquals(
                if (response.contains("false")) OpenSubsonicTranscodeDecisionRejection.SERVER_DENIED
                else OpenSubsonicTranscodeDecisionRejection.UNSUPPORTED_OUTPUT,
                (result as OpenSubsonicTranscodeDecisionOutcome.Rejected).reason,
            )
        }
    }

    @Test fun staleCapabilityAssetMismatchAndDecoderUnavailableNeverCallPost() = runBlocking {
        var calls = 0
        val engine = OpenSubsonicTranscodeDecisionEngine(object : OpenSubsonicDecisionTransport {
            override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String {
                calls++
                return reply()
            }
        })
        val evidence = capability()
        assertEquals(OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY,
            (engine.negotiate(runtime(), variant(), evidence, config.copy(serverUrl="https://changed.example")) { true }
                as OpenSubsonicTranscodeDecisionOutcome.Rejected).reason)
        assertEquals(OpenSubsonicTranscodeDecisionRejection.DECODER_UNAVAILABLE,
            (engine.negotiate(runtime(), variant(), evidence, config) { false }
                as OpenSubsonicTranscodeDecisionOutcome.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test fun malformedServerResponseOrTransportExceptionContainsNoSecrets() = runBlocking {
        for (body in listOf("not-json", """{"subsonic-response":{"status":"ok"}}""")) {
            val engine = OpenSubsonicTranscodeDecisionEngine(object : OpenSubsonicDecisionTransport {
                override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String) = body@body
            })
            val result = engine.negotiate(runtime(), variant(), capability(), config) { true }
            assertTrue(result is OpenSubsonicTranscodeDecisionOutcome.Rejected)
            assertFalse(result.toString().contains(authSecret))
        }
        val engine = OpenSubsonicTranscodeDecisionEngine(object : OpenSubsonicDecisionTransport {
            override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String =
                error("https://music.example.test?apiKey=$authSecret")
        })
        val result = engine.negotiate(runtime(), variant(), capability(), config) { true }
        assertEquals(OpenSubsonicTranscodeDecisionRejection.TRANSPORT_UNAVAILABLE,
            (result as OpenSubsonicTranscodeDecisionOutcome.Rejected).reason)
        assertFalse(result.toString().contains(authSecret))
    }

    @Test fun cancellationIsNeverSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val engine = OpenSubsonicTranscodeDecisionEngine(object : OpenSubsonicDecisionTransport {
                    override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String =
                        throw CancellationException("cancelled")
                })
                engine.negotiate(runtime(), variant(), capability(), config) { true }
            }
        }
    }
}
