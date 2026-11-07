package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopQualityTest {

    @Test
    fun everyNonRetiredProviderRemainsEligibleForQualityRungs() {
        val active = DesktopSourceKind.entries.filterNot { it == DesktopSourceKind.YOUTUBE }
        DesktopAudioQuality.entries.forEach { rung ->
            active.forEach { kind ->
                assertTrue(rung.permits(kind), "$rung / $kind")
            }
        }
    }

    @Test
    fun retiredYouTubeIsNeverEligibleEvenForHistoricalQualitySettings() {
        DesktopAudioQuality.entries.forEach { rung ->
            assertFalse(rung.permits(DesktopSourceKind.YOUTUBE), rung.name)
        }
    }

    @Test
    fun onlyTheLowestRungIsActuallyABitrateCap() {
        assertEquals(64, DesktopAudioQuality.LOW.maxKbps)
        listOf(DesktopAudioQuality.MEDIUM, DesktopAudioQuality.HIGH, DesktopAudioQuality.LOSSLESS)
            .forEach { assertEquals(Int.MAX_VALUE, it.maxKbps, it.name) }
    }

    @Test
    fun anOlderBuildsCeilingIsMigratedRatherThanReinterpreted() {
        // Three-rung builds had no source gating, so their "HIGH" meant "best available from
        // anything" — this model's LOSSLESS.
        assertEquals(DesktopAudioQuality.LOSSLESS, DesktopAudioQuality.stored("HIGH", hasFourRungs = false))
        assertEquals(DesktopAudioQuality.MEDIUM, DesktopAudioQuality.stored("MEDIUM", hasFourRungs = false))
        assertEquals(DesktopAudioQuality.LOW, DesktopAudioQuality.stored("LOW", hasFourRungs = false))
        // Once the four rungs have been seen, a stored HIGH is a real choice.
        assertEquals(DesktopAudioQuality.HIGH, DesktopAudioQuality.stored("HIGH", hasFourRungs = true))
    }

    @Test
    fun anUnsetOrUnreadableCeilingDefaultsToLossless() {
        assertEquals(DesktopAudioQuality.LOSSLESS, DesktopAudioQuality.stored(null, hasFourRungs = true))
        assertEquals(DesktopAudioQuality.LOSSLESS, DesktopAudioQuality.stored("", hasFourRungs = true))
        assertEquals(DesktopAudioQuality.LOSSLESS, DesktopAudioQuality.stored("nonsense", hasFourRungs = true))
        // The download rungs' spelling, in case one was ever written here.
        assertEquals(DesktopAudioQuality.MEDIUM, DesktopAudioQuality.stored("STANDARD", hasFourRungs = true))
    }
}
