package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamTruthSnapshot
import com.music.bitchord.data.settings.AudioQuality
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** B13 runtime RED: a Catalog decision is required for each next stream open. */
class CanonicalRemoteQualityRuntimeTest {
    private val track = CanonicalRemotePlaybackRequest("recording-1", "asset-1", "opensubsonic:home")

    private class SilentStream : DataSource {
        var opens = 0
        var closes = 0
        override fun open(dataSpec: DataSpec): Long { opens++; return 1L }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun close() { closes++ }
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) = Unit
    }

    private class FakeCatalog : CanonicalRemoteQualityCatalog {
        var revision = 7L
        var capability = CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY
        var refreshes = 0
        var originals = 0
        var mp3 = 0
        var bitrate: Int? = null
        var failRefresh = false
        var rotateDuringRefresh = false
        var stream = SilentStream()
        override fun accountGeneration(): Long = revision
        override suspend fun refreshCapability(
            generation: Long, providerKey: String,
        ): CanonicalRemoteTranscodeCapability {
            refreshes++
            if (failRefresh) throw CancellationException("canceled")
            if (rotateDuringRefresh) revision++
            return capability
        }
        override fun prepareOriginal(
            request: CanonicalRemotePlaybackRequest,
            generation: Long,
            onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
        ): DataSource? { originals++; return stream }
        override fun prepareMp3(
            request: CanonicalRemotePlaybackRequest,
            generation: Long,
            ceilingBps: Int,
            onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
        ): DataSource? { mp3++; bitrate=ceilingBps; return stream }
    }

    private fun prefs(quality: AudioQuality) =
        CanonicalRemoteQualityPreferences(quality, quality)

    @Test fun originalLosslessIsRawWithoutManifestRequestOrAutoStart() = runTest {
        val catalog = FakeCatalog()
        val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
            track, prefs(AudioQuality.LOSSLESS), CanonicalRemoteNetwork.WIFI,
        )
        assertEquals(CanonicalRemoteStreamMode.ORIGINAL, prepared.mode)
        assertEquals(0, catalog.refreshes)
        assertEquals(1, catalog.originals)
        assertEquals(0, catalog.mp3)
        assertEquals(0, catalog.stream.opens)
        assertFalse(prepared.applied)
    }

    @Test fun advertisedOnlyMayPrepareButNeverReportsPerAssetVerifiedOrApplied() = runTest {
        val catalog = FakeCatalog()
        val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
            track, prefs(AudioQuality.MEDIUM), CanonicalRemoteNetwork.MOBILE,
        )
        assertEquals(CanonicalRemoteStreamMode.MP3_REQUESTED_UNVERIFIED, prepared.mode)
        assertEquals(160_000, prepared.requestedBitrateBps)
        assertEquals(160_000, catalog.bitrate)
        assertEquals(1, catalog.refreshes)
        assertEquals(0, catalog.stream.opens)
        assertFalse(prepared.applied)
        assertNull(prepared.negotiatedFormat)
        assertNull(prepared.observedFormat)
    }

    @Test fun low64CannotBeRaisedToUnsupported96() = runTest {
        val catalog = FakeCatalog()
        val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
            track, prefs(AudioQuality.LOW), CanonicalRemoteNetwork.MOBILE,
        )
        assertEquals(CanonicalRemoteStreamMode.REJECTED, prepared.mode)
        assertEquals(CanonicalRemoteStreamRejection.UNSUPPORTED_CEILING, prepared.rejection)
        assertEquals(0, catalog.refreshes)
        assertEquals(0, catalog.mp3)
        assertEquals(0, catalog.originals)
    }

    @Test fun unsupportedAndUnknownCapabilityNeverFallbackSilently() = runTest {
        for (kind in listOf(CanonicalRemoteTranscodeCapability.UNSUPPORTED,
            CanonicalRemoteTranscodeCapability.UNKNOWN)) {
            val catalog = FakeCatalog().also { it.capability=kind }
            val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
                track, prefs(AudioQuality.HIGH), CanonicalRemoteNetwork.WIFI,
            )
            assertEquals(CanonicalRemoteStreamMode.REJECTED, prepared.mode)
            assertEquals(CanonicalRemoteStreamRejection.CAPABILITY_UNAVAILABLE, prepared.rejection)
            assertEquals(0, catalog.originals)
            assertEquals(0, catalog.mp3)
        }
    }

    @Test fun accountRotationDuringRefreshRejectsStaleRequestBeforePreparation() = runTest {
        val catalog = FakeCatalog().also { it.rotateDuringRefresh=true }
        val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
            track, prefs(AudioQuality.MEDIUM), CanonicalRemoteNetwork.WIFI,
        )
        assertEquals(CanonicalRemoteStreamMode.REJECTED, prepared.mode)
        assertEquals(CanonicalRemoteStreamRejection.STALE_ACCOUNT, prepared.rejection)
        assertEquals(0, catalog.originals)
        assertEquals(0, catalog.mp3)
    }

    @Test fun cancellationOfCapabilityRefreshPropagatesWithoutFallbackOrAutoplay() = runTest {
        val catalog = FakeCatalog().also { it.failRefresh=true }
        try {
            CanonicalRemoteQualityRuntime(catalog).prepare(
                track, prefs(AudioQuality.MEDIUM), CanonicalRemoteNetwork.MOBILE,
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, catalog.originals)
            assertEquals(0, catalog.mp3)
        }
    }

    @Test fun unknownTransportUsesOriginalRegardlessOfMetering() = runTest {
        val catalog = FakeCatalog()
        val prepared = CanonicalRemoteQualityRuntime(catalog).prepare(
            track, prefs(AudioQuality.HIGH), CanonicalRemoteNetwork.UNKNOWN,
        )
        assertEquals(CanonicalRemoteStreamMode.ORIGINAL, prepared.mode)
        assertEquals(0, catalog.refreshes)
        assertEquals(1, catalog.originals)
    }

    @Test fun freshNetworkPolicyAffectsNewOpenOnly() = runTest {
        val catalog = FakeCatalog()
        val runtime = CanonicalRemoteQualityRuntime(catalog)
        val original = runtime.prepare(track,
            CanonicalRemoteQualityPreferences(AudioQuality.LOSSLESS, AudioQuality.MEDIUM),
            CanonicalRemoteNetwork.WIFI)
        val mobile = runtime.prepare(track,
            CanonicalRemoteQualityPreferences(AudioQuality.LOSSLESS, AudioQuality.MEDIUM),
            CanonicalRemoteNetwork.MOBILE)
        assertEquals(CanonicalRemoteStreamMode.ORIGINAL, original.mode)
        assertEquals(CanonicalRemoteStreamMode.MP3_REQUESTED_UNVERIFIED, mobile.mode)
        assertEquals(0, catalog.stream.opens)
    }
}
