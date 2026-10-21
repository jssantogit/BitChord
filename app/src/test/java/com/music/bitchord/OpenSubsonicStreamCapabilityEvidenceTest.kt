package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSubsonicStreamCapabilityEvidenceTest {
    private val config = OpenSubsonicAccountConfig(
        accountId = "home",
        serverUrl = "https://music.example.test",
        auth = OpenSubsonicAuth.ApiKey("do-not-export-secret"),
    )

    private fun provider(body: suspend () -> String) = OpenSubsonicProvider(
        config = config,
        transport = object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>): String {
                assertEquals("getOpenSubsonicExtensions", endpoint)
                assertFalse(parameters.containsKey("apiKey"))
                assertFalse(parameters.containsKey("u"))
                assertFalse(parameters.containsKey("t"))
                return body()
            }
        },
    )

    private fun envelope(extensions: String?): String {
        val field = if (extensions == null) "" else ",\"openSubsonicExtensions\":$extensions"
        return "{\"subsonic-response\":{\"status\":\"ok\",\"openSubsonic\":true" + field + "}}"
    }

    @Test fun v1AdvertisementIsNotSuccessfulPlaybackProof() = runBlocking {
        val evidence = provider { envelope("""[{"name":"transcoding","versions":[1]}]""") }
            .streamCapabilityEvidence(generation = 11)
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, evidence.transcoding.confidence)
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, evidence.traditional.confidence)
        assertFalse(evidence.transcoding.isVerifiedFor("asset-1", "profile-1"))
        assertTrue(evidence.matches(generation = 11, savedConfig = config))
        assertFalse(evidence.matches(generation = 12, savedConfig = config))
        assertFalse(evidence.toString().contains("do-not-export-secret"))
        assertFalse(evidence.toString().contains("music.example"))
    }

    @Test fun v2OnlyIsUnsupportedAndOmittedVersionIsUnknown() = runBlocking {
        val v2 = provider { envelope("""[{"name":"transcoding","versions":[2]}]""") }
            .streamCapabilityEvidence(generation = 0)
        assertEquals(OpenSubsonicStreamConfidence.UNSUPPORTED, v2.transcoding.confidence)
        val noVersions = provider { envelope("""[{"name":"transcoding"}]""") }
            .streamCapabilityEvidence(generation = 0)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, noVersions.transcoding.confidence)
    }

    @Test fun explicitEmptyManifestDiffersFromMissingManifest() = runBlocking {
        val absent = provider { envelope("[]") }.streamCapabilityEvidence(0)
        val omitted = provider { envelope(null) }.streamCapabilityEvidence(0)
        assertEquals(OpenSubsonicStreamConfidence.UNSUPPORTED, absent.transcoding.confidence)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, omitted.transcoding.confidence)
    }

    @Test fun malformedEnvelopeAndFailuresNeverBecomeUnsupportedOrVerified() = runBlocking {
        val malformed = provider { "not-json-secret-apiKey" }.streamCapabilityEvidence(0)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, malformed.transcoding.confidence)
        assertFalse(malformed.toString().contains("not-json-secret"))
        val network = provider { error("https://server/?apiKey=do-not-export-secret") }
            .streamCapabilityEvidence(0)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, network.transcoding.confidence)
        assertFalse(network.toString().contains("do-not-export-secret"))
    }

    @Test fun changedEndpointAndCredentialInvalidateSameAccountIdSnapshot() = runBlocking {
        val evidence = provider { envelope("[]") }.streamCapabilityEvidence(4)
        assertFalse(evidence.matches(4, config.copy(serverUrl = "https://other.example")))
        assertFalse(evidence.matches(4, config.copy(auth = OpenSubsonicAuth.ApiKey("new-secret"))))
        assertFalse(evidence.matches(4, null))
    }

    @Test fun cancellationIsPropagatedNotTurnedIntoUnknown() {
        val cancellation = assertThrows(CancellationException::class.java) {
            runBlocking {
                provider { throw CancellationException("aborted") }.streamCapabilityEvidence(0)
            }
        }
        assertEquals("aborted", cancellation.message)
    }
}
