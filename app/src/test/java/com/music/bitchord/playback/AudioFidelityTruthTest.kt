package com.music.bitchord.playback

import android.media.AudioFormat
import com.music.bitchord.playback.audio.DirectAudioProbe
import com.music.bitchord.playback.audio.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFidelityTruthTest {
    @Test
    fun `unconfigured neutral playback does not advertise a DSP chain`() {
        val initial = AudioOutputStatus.Snapshot()
        assertFalse("No audio effects are connected in Android playback", initial.dspAvailable)
        assertEquals("Bypassed", initial.dspFormat)
    }

    @Test
    fun `app PCM exact alone cannot prove end to end bit perfect on speaker bluetooth or usb`() {
        for (kind in listOf(AudioRouting.Kind.PHONE, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.USB)) {
            assertEquals(
                "Unobserved route $kind cannot claim bit perfect",
                FidelityTruth.PCM_EXACT_ROUTE_NOT_PROVEN,
                AudioFidelityTruth.classify(pcmExact = true, anyDsp = false),
            )
        }
    }

    @Test
    fun `DSP and PCM conversion remain altered but direct route is not end to end proof`() {
        assertEquals(FidelityTruth.ALTERED, AudioFidelityTruth.classify(false, false))
        assertEquals(FidelityTruth.ALTERED, AudioFidelityTruth.classify(true, true))
        // A direct AudioTrack observation is insufficient to prove the
        // intervening Android mixer/HAL or external output path.
        assertEquals(FidelityTruth.PCM_EXACT_ROUTE_NOT_PROVEN, AudioFidelityTruth.classify(true, false))
    }

    @Test
    fun `Android direct support negotiation is not proof of a direct AudioTrack`() {
        val support = DirectAudioProbe.DirectSupport(
            isDirectSupported = true,
            isOffloadSupported = false,
            supportsFloat = false,
            supportsPcm24 = false,
            supportsPcm16 = true,
            description = "API reports direct support, no output path was measured",
        )
        val asked = AudioOutputStatus.Snapshot(
            routeKind = AudioRouting.Kind.USB,
            requestedTransportType = TransportType.AUDIO_TRACK_DIRECT,
            directSupport = support,
            directPlaybackSelected = true,
            actualEncoding = AudioFormat.ENCODING_PCM_16BIT,
            actualSampleRateHz = 44_100,
        )
        val result = AudioOutputStatus.evaluateActualPath(asked)
        assertTrue(result.directPlaybackRequested)
        assertTrue(result.directPlaybackSelected)
        assertFalse("Support does not observe actual AudioFlinger route", result.directPlaybackActual)
    }

    @Test
    fun `USB userspace capability without opened endpoint is not active USB transport`() {
        val asked = AudioOutputStatus.Snapshot(
            routeKind = AudioRouting.Kind.USB,
            requestedTransportType = TransportType.DIRECT_USB,
        )
        val result = AudioOutputStatus.evaluateActualPath(asked)
        assertFalse(result.directPlaybackActual)
        assertFalse(result.transportType == TransportType.DIRECT_USB)
    }

    @Test
    fun `route change invalidates previously observed AudioTrack and PCM exactness`() {
        val before = AudioOutputStatus.Snapshot(
            activeDeviceId = 12,
            routeKind = AudioRouting.Kind.USB,
            actualEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            actualSampleRateHz = 96_000,
            outputExact = true,
            outputExactDetail = "24-bit PCM to Float32",
        )
        val changed = AudioOutputStatus.clearMeasurementsForRoute(before, AudioRouting.Kind.BLUETOOTH, 21)
        assertNull(changed.actualEncoding)
        assertNull(changed.actualSampleRateHz)
        assertFalse(changed.outputExact)
        assertNull(changed.outputExactDetail)
        val same = AudioOutputStatus.clearMeasurementsForRoute(before, AudioRouting.Kind.USB, 12)
        assertEquals(before, same)
    }
}
