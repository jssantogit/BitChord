package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicDecoderProfile
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlan
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlanner
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicStreamVariantContractTest {
    private val recording = "recording-111"
    private val asset = "asset-222"
    private val provider = "opensubsonic:home"

    @Test fun originalPreservesCanonicalIdentityAndDefaultsToRaw() {
        val result = OpenSubsonicStreamVariantPlanner.original(recording, asset, provider, 9)
        val accepted = result as OpenSubsonicStreamVariantPlan.Accepted
        assertEquals(OpenSubsonicStreamVariantMode.ORIGINAL, accepted.variant.mode)
        assertEquals(recording, accepted.variant.recordingId)
        assertEquals(asset, accepted.variant.mediaAssetId)
        assertEquals(provider, accepted.variant.providerKey)
        assertEquals(9L, accepted.variant.accountGeneration)
        assertEquals("raw", accepted.variant.legacyFormat)
        assertEquals(null, accepted.variant.legacyMaxBitRateKbps)
    }

    @Test fun advertisedV1AllowsAnExplicitRequestButNeverClaimsItWasNegotiated() {
        val result = OpenSubsonicStreamVariantPlanner.extensionRequested(
            recording, asset, provider, 9, OpenSubsonicStreamConfidence.ADVERTISED,
            OpenSubsonicDecoderProfile.MP3_HTTP, maxBitrateBps = 192_000,
        ) as OpenSubsonicStreamVariantPlan.Accepted
        assertEquals(OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED, result.variant.mode)
        assertEquals(OpenSubsonicDecoderProfile.MP3_HTTP, result.variant.decoderProfile)
        assertEquals(192_000, result.variant.maxBitrateBps)
        assertFalse(result.variant.toString().contains("recording-111"))
        assertFalse(result.variant.toString().contains("opensubsonic:home"))
    }

    @Test fun unknownAndUnsupportedTranscodingFailClosed() {
        listOf(OpenSubsonicStreamConfidence.UNKNOWN, OpenSubsonicStreamConfidence.UNSUPPORTED).forEach {
            val result = OpenSubsonicStreamVariantPlanner.extensionRequested(
                recording, asset, provider, 9, it,
                OpenSubsonicDecoderProfile.MP3_HTTP, 192_000,
            )
            assertEquals(
                OpenSubsonicStreamVariantRejection.CAPABILITY_NOT_ADVERTISED,
                (result as OpenSubsonicStreamVariantPlan.Rejected).reason,
            )
        }
    }

    @Test fun unsupportedBitrateOrIdentityCannotEnterAuthenticatedRequest() {
        for (value in listOf(-1, 0, 512_001)) {
            assertTrue(
                OpenSubsonicStreamVariantPlanner.extensionRequested(
                    recording, asset, provider, 9, OpenSubsonicStreamConfidence.ADVERTISED,
                    OpenSubsonicDecoderProfile.MP3_HTTP, value,
                ) is OpenSubsonicStreamVariantPlan.Rejected,
            )
        }
        assertEquals(
            OpenSubsonicStreamVariantRejection.INVALID_IDENTITY,
            (OpenSubsonicStreamVariantPlanner.original(" ", asset, provider, 0) as OpenSubsonicStreamVariantPlan.Rejected).reason,
        )
        assertEquals(
            OpenSubsonicStreamVariantRejection.INVALID_IDENTITY,
            (OpenSubsonicStreamVariantPlanner.original(recording, asset, "opensubsonic:other?apiKey=secret", 0) as OpenSubsonicStreamVariantPlan.Rejected).reason,
        )
    }

    @Test fun legacyBitrateIsBestEffortNotDecisionV1() {
        val result = OpenSubsonicStreamVariantPlanner.legacyBestEffort(
            recording, asset, provider, 3, maxBitRateKbps = 128,
        ) as OpenSubsonicStreamVariantPlan.Accepted
        assertEquals(OpenSubsonicStreamVariantMode.LEGACY_STREAM_BEST_EFFORT, result.variant.mode)
        assertEquals("mp3", result.variant.legacyFormat)
        assertEquals(128, result.variant.legacyMaxBitRateKbps)
        assertEquals(null, result.variant.decoderProfile)
        assertFalse(result.variant.toString().contains("asset-222"))
    }

    @Test fun deniedOrMalformedLegacyInputsNeverBecomeRawOrNegotiated() {
        assertTrue(
            OpenSubsonicStreamVariantPlanner.legacyBestEffort(
                recording, asset, provider, 0, maxBitRateKbps = -1,
            ) is OpenSubsonicStreamVariantPlan.Rejected,
        )
        assertTrue(
            OpenSubsonicStreamVariantPlanner.original(recording, asset, provider, -1)
                is OpenSubsonicStreamVariantPlan.Rejected,
        )
    }
}
