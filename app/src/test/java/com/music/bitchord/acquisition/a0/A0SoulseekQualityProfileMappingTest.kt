package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.SoulseekQualityPreset
import org.junit.Assert.*
import org.junit.Test

class A0SoulseekQualityProfileMappingTest {
    @Test fun preferencesAreDedicatedAndLegacyNeutral() {
        val old=A0SoulseekQualityProfiles.snapshot(SoulseekQualityPreset.ANY)
        assertEquals(A0CanonicalAcquisitionIntent.QualityPreset.LEGACY_UNSPECIFIED,old.preset)
        assertFalse(old.strict)
        assertEquals(7,SoulseekQualityPreset.entries.size)
    }

    @Test fun losslessAndHiResAreStrictButDoNotPretendToVerifyAudio() {
        for(value in listOf(SoulseekQualityPreset.HI_RES,SoulseekQualityPreset.LOSSLESS)) {
            val profile=A0SoulseekQualityProfiles.snapshot(value)
            assertTrue(profile.strict)
            assertTrue(profile.allowedCodecs.isEmpty())
        }
    }

    @Test fun customPresetsHaveCodecCorrectTypedConstraints() {
        val mp3=A0SoulseekQualityProfiles.snapshot(SoulseekQualityPreset.MP3_256)
        assertEquals(setOf(A0CanonicalAcquisitionIntent.Codec.MP3),mp3.allowedCodecs)
        assertEquals(256,mp3.minBitrateKbps)
        assertNull(mp3.minBitDepth)
        val flac=A0SoulseekQualityProfiles.snapshot(SoulseekQualityPreset.FLAC_24_96)
        assertEquals(setOf(A0CanonicalAcquisitionIntent.Codec.FLAC),flac.allowedCodecs)
        assertEquals(24,flac.minBitDepth)
        assertEquals(96000,flac.minSampleRateHz)
        assertNull(flac.minBitrateKbps)
    }

    @Test fun invalidOldPreferenceFallsBackWithoutChangingOtherDownloadQuality() {
        assertEquals(SoulseekQualityPreset.ANY,
            SoulseekQualityPreset.parse("invalid-or-renamed"))
        assertEquals(SoulseekQualityPreset.HIGH_QUALITY,
            SoulseekQualityPreset.parse("HIGH_QUALITY"))
    }
}
