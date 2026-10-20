package com.music.bitchord.playback

import com.music.bitchord.data.settings.AudioQuality
import org.junit.Assert.*
import org.junit.Test

/** B12: preference is not proof that a provider can or did transcode. */
class CanonicalRemoteQualityIntentTest {
    private val preferences = CanonicalRemoteQualityPreferences(
        wifi = AudioQuality.LOSSLESS,
        mobile = AudioQuality.MEDIUM,
    )

    @Test fun `original default on wifi does not request transcode`() {
        val preview = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.WIFI, metered = false,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.UNKNOWN,
        )
        assertEquals(CanonicalRemoteQualityDecision.ORIGINAL_DIRECT, preview.decision)
        assertEquals("raw", preview.requestFormat)
        assertNull(preview.maxBitrateKbps)
        assertFalse(preview.applied)
        assertNull(preview.negotiatedFormat)
        assertNull(preview.observedFormat)
    }

    @Test fun `mobile quality is only a prospective bounded MP3 request with verified capability`() {
        val preview = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.MOBILE, metered = true,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
        )
        assertEquals(AudioQuality.MEDIUM, preview.chosenPreference)
        assertEquals(CanonicalRemoteQualityDecision.CAPABILITY_VERIFIED_INTENT, preview.decision)
        assertEquals("mp3", preview.requestFormat)
        assertEquals(160, preview.maxBitrateKbps)
        assertFalse(preview.applied)
        assertNull(preview.negotiatedFormat)
        assertNull(preview.observedFormat)
    }

    @Test fun `unverified advertisement never becomes a transcode request`() {
        for (capability in listOf(
            CanonicalRemoteTranscodeCapability.UNKNOWN,
            CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY,
            CanonicalRemoteTranscodeCapability.UNSUPPORTED,
        )) {
            val preview = CanonicalRemoteQualityPlanner.preview(
                preferences, CanonicalRemoteNetwork.MOBILE, metered = true,
                source = CanonicalRemoteSource.OPENSUBSONIC, capability = capability,
            )
            assertEquals("raw", preview.requestFormat)
            assertNull(preview.maxBitrateKbps)
            assertFalse(preview.applied)
            assertNotEquals(CanonicalRemoteQualityDecision.CAPABILITY_VERIFIED_INTENT, preview.decision)
        }
    }

    @Test fun `metering never silently substitutes transport identity`() {
        val meteredWifi = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.WIFI, metered = true,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
        )
        assertEquals(AudioQuality.LOSSLESS, meteredWifi.chosenPreference)
        val unmeteredMobile = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.MOBILE, metered = false,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
        )
        assertEquals(AudioQuality.MEDIUM, unmeteredMobile.chosenPreference)
        assertEquals(160, unmeteredMobile.maxBitrateKbps)
    }

    @Test fun `unknown network and local or other remote source fail closed to original`() {
        for (network in listOf(CanonicalRemoteNetwork.UNKNOWN, CanonicalRemoteNetwork.OTHER)) {
            val preview = CanonicalRemoteQualityPlanner.preview(
                preferences, network, metered = null,
                source = CanonicalRemoteSource.OPENSUBSONIC,
                capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
            )
            assertEquals(CanonicalRemoteQualityDecision.UNKNOWN_NETWORK, preview.decision)
            assertEquals("raw", preview.requestFormat)
        }
        for (source in listOf(CanonicalRemoteSource.LOCAL, CanonicalRemoteSource.OTHER_REMOTE)) {
            val preview = CanonicalRemoteQualityPlanner.preview(
                preferences, CanonicalRemoteNetwork.MOBILE, metered = true,
                source = source,
                capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
            )
            assertEquals(CanonicalRemoteQualityDecision.NOT_OPENSUBSONIC, preview.decision)
            assertNull(preview.maxBitrateKbps)
        }
    }

    @Test fun `network change only changes subsequent preview not current stream`() {
        val original = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.WIFI, metered = false,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
        )
        val next = CanonicalRemoteQualityPlanner.preview(
            preferences, CanonicalRemoteNetwork.MOBILE, metered = true,
            source = CanonicalRemoteSource.OPENSUBSONIC,
            capability = CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
        )
        assertEquals("raw", original.requestFormat)
        assertEquals("mp3", next.requestFormat)
        assertFalse(original.applied)
        assertFalse(next.applied)
    }

    @Test fun `low and high policy cannot exceed bounded offered bitrate`() {
        for ((quality, expected) in listOf(AudioQuality.LOW to 96, AudioQuality.HIGH to 320)) {
            val preview = CanonicalRemoteQualityPlanner.preview(
                CanonicalRemoteQualityPreferences(quality, quality),
                CanonicalRemoteNetwork.WIFI, metered = false,
                CanonicalRemoteSource.OPENSUBSONIC,
                CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
            )
            assertEquals(expected, preview.maxBitrateKbps)
        }
    }
}
