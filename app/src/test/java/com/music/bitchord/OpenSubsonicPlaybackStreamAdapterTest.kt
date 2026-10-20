package com.music.bitchord

import androidx.media3.datasource.DataSource
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaDataSourceFactory
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicPlaybackStreamAdapter
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapability
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapabilitySnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamEvidenceBasis
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamMechanism
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamPolicyGateway
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamPreparation
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamPreparationFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicPlaybackStreamAdapterTest {
    private val account = OpenSubsonicAccountConfig(
        "home", "https://music.example", OpenSubsonicAuth.ApiKey("provider-private-secret"),
    )
    private fun dataSources(resolveCalls: () -> Unit) = OpenSubsonicMediaDataSourceFactory(
        transport = OpenSubsonicRuntimeTransport {
            resolveCalls()
            OpenSubsonicMediaAccessResult.Failure(OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND)
        },
        upstreamFactory = DataSource.Factory { error("No HTTP before DataSource.open") },
    )
    private fun gateway() = OpenSubsonicStreamPolicyGateway(
        savedConfig = { account },
        revisionNow = { 7L },
        discover = { _, generation ->
            OpenSubsonicStreamCapabilitySnapshot(
                generation,
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
                configurationIsCurrent = OpenSubsonicProvider(account)::matchesSavedConfig,
            )
        },
    )

    @Test fun originalDefaultsToRawWithoutCapabilityDiscoveryOrCredentialResolution() {
        var lookups = 0
        val adapter = OpenSubsonicPlaybackStreamAdapter(dataSources { lookups++ }, gateway())
        val original = adapter.prepareOriginal("recording-1", "asset-1", "opensubsonic:home", 4)
        assertTrue(original is OpenSubsonicStreamPreparation.Ready)
        assertEquals(
            OpenSubsonicStreamVariantMode.ORIGINAL,
            (original as OpenSubsonicStreamPreparation.Ready).requestedMode,
        )
        assertEquals(0, lookups)
        assertFalse(original.toString().contains("recording-1"))
        assertFalse(original.toString().contains("asset-1"))
        assertFalse(original.toString().contains("provider-private-secret"))
        assertEquals(
            OpenSubsonicStreamPreparationFailure.INVALID_IDENTITY,
            (adapter.prepareOriginal("bad/id", "asset-1", "opensubsonic:home", 4)
                as OpenSubsonicStreamPreparation.Rejected).reason,
        )
    }

    @Test fun mp3RequiresCurrentAdvertisedCapabilityAndBoundedDecoderSupportedProfile() = runTest {
        var lookups = 0
        val gateway = gateway()
        val adapter = OpenSubsonicPlaybackStreamAdapter(dataSources { lookups++ }, gateway)
        assertEquals(
            OpenSubsonicStreamPreparationFailure.CAPABILITY_UNAVAILABLE,
            (adapter.prepareCappedMp3("recording-1", "asset-1", "opensubsonic:home", 4, 160_000)
                as OpenSubsonicStreamPreparation.Rejected).reason,
        )
        gateway.refresh(4, "opensubsonic:home")
        val accepted = adapter.prepareCappedMp3(
            "recording-1", "asset-1", "opensubsonic:home", 4, 160_000,
        )
        assertTrue(accepted is OpenSubsonicStreamPreparation.Ready)
        assertEquals(
            OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED,
            (accepted as OpenSubsonicStreamPreparation.Ready).requestedMode,
        )
        // Preparation has not negotiated, opened, received bytes or decoded audio.
        assertEquals(0, lookups)
        assertFalse(accepted.toString().contains("provider-private-secret"))
        assertEquals(
            OpenSubsonicStreamPreparationFailure.INVALID_BITRATE,
            (adapter.prepareCappedMp3("recording-1", "asset-1", "opensubsonic:home", 4, 987_654)
                as OpenSubsonicStreamPreparation.Rejected).reason,
        )
        assertEquals(
            OpenSubsonicStreamPreparationFailure.CAPABILITY_UNAVAILABLE,
            (adapter.prepareCappedMp3("recording-1", "asset-1", "opensubsonic:home", 5, 160_000)
                as OpenSubsonicStreamPreparation.Rejected).reason,
        )
    }
}
