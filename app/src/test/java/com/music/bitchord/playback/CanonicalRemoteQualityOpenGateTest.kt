package com.music.bitchord.playback

import com.music.bitchord.data.settings.AudioQuality
import org.junit.Assert.*
import org.junit.Test

/**
 * B13 RED: an open-time plan must not confuse a user's quality preference,
 * a public extension announcement or a planned request with actual playback.
 */
class CanonicalRemoteQualityOpenGateTest {
    private fun preview(
        quality: AudioQuality,
        capability: CanonicalRemoteTranscodeCapability,
        source: CanonicalRemoteSource = CanonicalRemoteSource.OPENSUBSONIC,
        network: CanonicalRemoteNetwork = CanonicalRemoteNetwork.MOBILE,
    ): CanonicalRemoteQualityPreview =
        CanonicalRemoteQualityPlanner.preview(
            preferences = CanonicalRemoteQualityPreferences(quality, quality),
            network = network,
            metered = true,
            source = source,
            capability = capability,
        )

    @Test fun `default original never needs a fabricated account generation`() {
        val result = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.LOSSLESS, CanonicalRemoteTranscodeCapability.UNKNOWN),
            accountGeneration = null,
            supportedMp3CeilingsBps = emptySet(),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.ORIGINAL, result.mode)
        assertNull(result.maxBitrateBps)
        assertFalse(result.applied)
    }

    @Test fun `public advertisement cannot be promoted to verified per-media capability`() {
        val result = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY),
            accountGeneration = 4L,
            supportedMp3CeilingsBps = setOf(160_000),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.ORIGINAL, result.mode)
        assertNull(result.maxBitrateBps)
        assertFalse(result.applied)
    }

    @Test fun `verified bounded medium may request but never claim applied or observed quality`() {
        val result = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.VERIFIED_MP3),
            accountGeneration = 8L,
            supportedMp3CeilingsBps = setOf(96_000, 160_000, 320_000),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.REQUEST_CAPPED_MP3, result.mode)
        assertEquals(160_000, result.maxBitrateBps)
        assertEquals(8L, result.accountGeneration)
        assertFalse(result.applied)
    }

    @Test fun `LOW 64 kbps must not be silently increased to catalog 96 kbps`() {
        val result = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.LOW, CanonicalRemoteTranscodeCapability.VERIFIED_MP3),
            accountGeneration = 9L,
            supportedMp3CeilingsBps = setOf(96_000, 160_000, 320_000),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, result.mode)
        assertEquals(CanonicalRemoteQualityOpenReason.CEILING_NOT_SUPPORTED, result.reason)
        assertNull(result.maxBitrateBps)
        assertFalse(result.applied)
    }

    @Test fun `unknown supported ceilings never become invented provider support`() {
        val result = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.HIGH, CanonicalRemoteTranscodeCapability.VERIFIED_MP3),
            accountGeneration = 0L,
            supportedMp3CeilingsBps = emptySet(),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, result.mode)
        assertNull(result.maxBitrateBps)
    }

    @Test fun `missing or stale account generation blocks variant preparation`() {
        for (generation in listOf<Long?>(null, -1L)) {
            val result = CanonicalRemoteQualityOpenGate.plan(
                preview(AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.VERIFIED_MP3),
                accountGeneration = generation,
                supportedMp3CeilingsBps = setOf(160_000),
            )
            assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, result.mode)
            assertEquals(CanonicalRemoteQualityOpenReason.GENERATION_UNAVAILABLE, result.reason)
            assertNull(result.maxBitrateBps)
        }
    }

    @Test fun `local unknown transport and other provider remain direct original`() {
        for (source in listOf(CanonicalRemoteSource.LOCAL, CanonicalRemoteSource.OTHER_REMOTE)) {
            val result = CanonicalRemoteQualityOpenGate.plan(
                preview(AudioQuality.HIGH, CanonicalRemoteTranscodeCapability.VERIFIED_MP3, source),
                accountGeneration = null,
                supportedMp3CeilingsBps = setOf(320_000),
            )
            assertEquals(CanonicalRemoteQualityOpenMode.ORIGINAL, result.mode)
        }
        val networkUnknown = CanonicalRemoteQualityOpenGate.plan(
            preview(AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.VERIFIED_MP3,
                network = CanonicalRemoteNetwork.UNKNOWN),
            accountGeneration = 12L,
            supportedMp3CeilingsBps = setOf(160_000),
        )
        assertEquals(CanonicalRemoteQualityOpenMode.ORIGINAL, networkUnknown.mode)
    }

    @Test fun `advertised manifest can only prepare an unverified next-stream request`() {
        val plan = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY, 4L,
        )
        assertEquals(CanonicalRemoteQualityOpenMode.REQUEST_CAPPED_MP3, plan.mode)
        assertEquals(CanonicalRemoteQualityOpenReason.ADVERTISED_REQUEST_PENDING_MEDIA_DECISION, plan.reason)
        assertEquals(160_000, plan.maxBitrateBps)
        assertEquals(4L, plan.accountGeneration)
        assertFalse(plan.applied)
    }

    @Test fun `account-wide verified hint still needs this MediaAsset decision on open`() {
        val plan = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.HIGH, CanonicalRemoteTranscodeCapability.VERIFIED_MP3, 5L,
        )
        assertEquals(CanonicalRemoteQualityOpenReason.ADVERTISED_REQUEST_PENDING_MEDIA_DECISION, plan.reason)
        assertEquals(320_000, plan.maxBitrateBps)
        assertFalse(plan.applied)
    }

    @Test fun `next-open gate rejects unsupported capability and LOW64 without fallback`() {
        val unsupported = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.HIGH, CanonicalRemoteTranscodeCapability.UNKNOWN, 6L,
        )
        assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, unsupported.mode)
        assertEquals(CanonicalRemoteQualityOpenReason.CAPABILITY_NOT_AVAILABLE, unsupported.reason)

        val low = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.LOW, CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY, 6L,
        )
        assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, low.mode)
        assertEquals(CanonicalRemoteQualityOpenReason.CEILING_NOT_SUPPORTED, low.reason)
        assertNull(low.maxBitrateBps)
    }

    @Test fun `next-open gate requires a Catalog generation for MP3 and preserves original`() {
        val stale = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.MEDIUM, CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY, null,
        )
        assertEquals(CanonicalRemoteQualityOpenMode.BLOCK_UNAPPLIED, stale.mode)
        assertEquals(CanonicalRemoteQualityOpenReason.GENERATION_UNAVAILABLE, stale.reason)
        val direct = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            AudioQuality.LOSSLESS, CanonicalRemoteTranscodeCapability.UNKNOWN, null,
        )
        assertEquals(CanonicalRemoteQualityOpenMode.ORIGINAL, direct.mode)
        assertFalse(direct.applied)
    }

}
