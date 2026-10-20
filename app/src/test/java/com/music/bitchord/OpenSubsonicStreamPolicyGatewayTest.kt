package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapability
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapabilitySnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamEvidenceBasis
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamLeaseStatus
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamMechanism
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamPolicyGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicStreamPolicyGatewayTest {
    private val account = OpenSubsonicAccountConfig(
        "home", "https://music.example", OpenSubsonicAuth.ApiKey("very-private-secret"),
    )
    private fun advertised(generation: Long): OpenSubsonicStreamCapabilitySnapshot {
        val provider = OpenSubsonicProvider(account)
        return OpenSubsonicStreamCapabilitySnapshot(
            generation = generation,
            traditional = OpenSubsonicStreamCapability(
                OpenSubsonicStreamMechanism.TRADITIONAL_STREAM_BEST_EFFORT,
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamEvidenceBasis.PROTOCOL_SPEC,
            ),
            transcoding = OpenSubsonicStreamCapability(
                OpenSubsonicStreamMechanism.EXTENSION_TRANSCODING_V1,
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamEvidenceBasis.MANIFEST_V1,
            ),
            configurationIsCurrent = provider::matchesSavedConfig,
        )
    }

    @Test fun cachedPreviewNeverRefreshesNetworkAndIsUnknownUntilExplicitRefresh() = runTest {
        var discoveries = 0
        var revision = 1L
        val gateway = OpenSubsonicStreamPolicyGateway(
            savedConfig = { account },
            revisionNow = { revision },
            discover = { _, generation ->
                discoveries++
                advertised(generation)
            },
        )
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, gateway.preview(4, "opensubsonic:home").lifecycle)
        assertEquals(0, discoveries)
        val current = gateway.refresh(4, "opensubsonic:home")
        assertEquals(OpenSubsonicStreamLeaseStatus.CURRENT, current.lifecycle)
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, current.transcoding)
        assertEquals(1, current.contractVersion)
        assertFalse(current.toString().contains("very-private-secret"))
        assertFalse(current.toString().contains("music.example"))
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, gateway.preview(4, "opensubsonic:home").transcoding)
        assertEquals(1, discoveries)
        revision++
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, gateway.preview(4, "opensubsonic:home").lifecycle)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, gateway.preview(4, "opensubsonic:home").transcoding)
        assertEquals(1, discoveries)
    }

    @Test fun missingAccountProviderMismatchAndFailedDiscoveryFailClosed() = runTest {
        var config: OpenSubsonicAccountConfig? = null
        var attempts = 0
        val gateway = OpenSubsonicStreamPolicyGateway(
            savedConfig = { config },
            revisionNow = { 7L },
            discover = { _, _ ->
                attempts++
                error("https://music.example/rest?apiKey=private")
            },
        )
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, gateway.refresh(1, "opensubsonic:home").lifecycle)
        assertEquals(0, attempts)
        config = account
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, gateway.refresh(1, "opensubsonic:other").lifecycle)
        assertEquals(0, attempts)
        val failed = gateway.refresh(1, "opensubsonic:home")
        assertEquals(OpenSubsonicStreamLeaseStatus.CURRENT, failed.lifecycle)
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, failed.transcoding)
        assertFalse(failed.toString().contains("apiKey"))
        assertEquals(1, attempts)
    }

    @Test fun outdatedRefreshCannotOverwriteNewerGenerationOrRestoredAccount() = runTest {
        var revision = 2L
        val oldRequest = CompletableDeferred<OpenSubsonicStreamCapabilitySnapshot>()
        val gateway = OpenSubsonicStreamPolicyGateway(
            savedConfig = { account },
            revisionNow = { revision },
            discover = { _, generation ->
                if (generation == 8L) oldRequest.await() else advertised(generation)
            },
        )
        val older = async { gateway.refresh(8, "opensubsonic:home") }
        runCurrent()
        val newest = gateway.refresh(9, "opensubsonic:home")
        assertEquals(OpenSubsonicStreamLeaseStatus.CURRENT, newest.lifecycle)
        oldRequest.complete(advertised(8))
        older.await()
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, gateway.preview(9, "opensubsonic:home").transcoding)
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, gateway.preview(8, "opensubsonic:home").lifecycle)
        revision++
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, gateway.preview(9, "opensubsonic:home").transcoding)
    }
}
