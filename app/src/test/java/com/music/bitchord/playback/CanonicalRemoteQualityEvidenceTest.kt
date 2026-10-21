package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicNegotiationEvidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamTruthSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransportStage
import com.music.bitchord.data.settings.AudioQuality
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B14-B18 contract tests: a real Media3 open is not decoder or audible proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanonicalRemoteQualityEvidenceTest {
    private val request = CanonicalRemotePlaybackRequest("recording-1", "asset-1", "opensubsonic:test")
    private fun spec() = DataSpec(Uri.parse(CanonicalRemotePlaybackUri.encode(request)))

    private class Stream : DataSource {
        var opens = 0
        var closes = 0
        override fun open(dataSpec: DataSpec): Long { opens++; return 32 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun close() { closes++ }
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }

    private class Catalog : CanonicalRemoteQualityCatalog {
        val stream = Stream()
        var generation = 4L
        var refreshes = 0
        var originals = 0
        var mp3 = 0
        var ceiling: Int? = null
        var truth: ((OpenSubsonicStreamTruthSnapshot) -> Unit)? = null

        override fun accountGeneration() = generation
        override suspend fun refreshCapability(generation: Long, providerKey: String): CanonicalRemoteTranscodeCapability {
            refreshes++
            return CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY
        }
        override fun prepareOriginal(
            request: CanonicalRemotePlaybackRequest, generation: Long,
            onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
        ): DataSource { originals++; truth = onTruth; return stream }
        override fun prepareMp3(
            request: CanonicalRemotePlaybackRequest, generation: Long, ceilingBps: Int,
            onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
        ): DataSource { mp3++; ceiling = ceilingBps; truth = onTruth; return stream }
    }

    private fun open(
        catalog: Catalog,
        wifi: AudioQuality = AudioQuality.HIGH,
        mobile: AudioQuality = AudioQuality.MEDIUM,
        network: CanonicalRemoteNetwork = CanonicalRemoteNetwork.WIFI,
        sink: (CanonicalRemoteQualityEvidence) -> Unit = {},
    ) = CanonicalRemoteQualityDataSource(
        request, CanonicalRemoteQualityRuntime(catalog),
        preferences = { CanonicalRemoteQualityPreferences(wifi, mobile) },
        network = { network },
        evidence = sink,
    )

    @Test fun actualDataSourceOpenIsSeparatedFromPreparationAndDecoderProof() {
        val catalog = Catalog()
        val events = mutableListOf<CanonicalRemoteQualityEvidence>()
        val source = open(catalog, sink = events::add)
        assertTrue(events.isEmpty())
        assertEquals(0, catalog.stream.opens)
        assertEquals(32L, source.open(spec()))
        assertEquals(1, catalog.stream.opens)
        assertEquals(1, catalog.mp3)
        assertEquals(320_000, catalog.ceiling)
        assertEquals(listOf(
            CanonicalRemoteQualityEvidenceStage.PREPARED,
            CanonicalRemoteQualityEvidenceStage.TRANSPORT_OPENED,
        ), events.map { it.stage })
        assertEquals(events[0].attemptId, events[1].attemptId)
        assertEquals(320_000, events[1].requestedBitrateBps)
        assertFalse(events[1].toDiagnosticMetadata().containsKey("actualBitrateBps"))
        assertFalse(events[1].toDiagnosticMetadata().containsKey("audible"))
        source.close()
    }

    @Test fun transportHeadersAndBytesAreNotDecoderOrEffectiveBitrateEvidence() {
        val catalog = Catalog()
        val events = mutableListOf<CanonicalRemoteQualityEvidence>()
        val source = open(catalog, sink = events::add)
        source.open(spec())
        val snapshot = OpenSubsonicStreamTruthSnapshot(
            OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED,
            320_000, OpenSubsonicNegotiationEvidence.SERVER_DECISION,
            OpenSubsonicTransportStage.BYTES_RECEIVED, 200, "audio/mpeg", 4096, 120,
        )
        catalog.truth!!.invoke(snapshot)
        catalog.truth!!.invoke(snapshot) // repeated byte callbacks are bounded by stage
        val observed = events.filter { it.stage == CanonicalRemoteQualityEvidenceStage.CATALOG_TRUTH }
        assertEquals(1, observed.size)
        assertEquals(OpenSubsonicTransportStage.BYTES_RECEIVED, observed.single().transport)
        assertEquals(OpenSubsonicNegotiationEvidence.SERVER_DECISION, observed.single().negotiation)
        val metadata = observed.single().toDiagnosticMetadata().toString()
        assertFalse(metadata.contains("recording-1"))
        assertFalse(metadata.contains("opensubsonic:test"))
        assertFalse(metadata.contains("audio/mpeg"))
        assertFalse(metadata.contains("actualCodec"))
        assertFalse(metadata.contains("actualBitrate"))
        assertFalse(metadata.contains("audible"))
        source.close()
        catalog.truth!!.invoke(snapshot)
        assertEquals(1, events.count { it.stage == CanonicalRemoteQualityEvidenceStage.CATALOG_TRUTH })
    }

    @Test fun physicalWifiAndCellularUseTheirOwnNextOpenCeilingsAndNeverUpscaleLow() {
        val wifi = Catalog()
        val fromWifi = open(wifi, network = CanonicalRemoteNetwork.WIFI)
        fromWifi.open(spec())
        assertEquals(320_000, wifi.ceiling)
        fromWifi.close()
        val mobile = Catalog()
        val fromCellular = open(mobile, network = CanonicalRemoteNetwork.MOBILE)
        fromCellular.open(spec())
        assertEquals(160_000, mobile.ceiling)
        fromCellular.close()
        val low = Catalog()
        val failures = mutableListOf<CanonicalRemoteQualityEvidence>()
        val fromLow = open(low, wifi = AudioQuality.LOW, sink = failures::add)
        try { fromLow.open(spec()); fail("LOW 64 must be rejected") } catch (_: IOException) { }
        assertEquals(0, low.mp3)
        assertEquals(0, low.originals)
        assertEquals(CanonicalRemoteStreamRejection.UNSUPPORTED_CEILING,
            failures.first { it.stage == CanonicalRemoteQualityEvidenceStage.REJECTED }.rejection)
    }

    @Test fun mismatchedUriFailsClosedWithoutPoisoningOpeningFlagOrLeakingIdentity() {
        val catalog = Catalog()
        val events = mutableListOf<CanonicalRemoteQualityEvidence>()
        val source = open(catalog, wifi = AudioQuality.LOSSLESS, sink = events::add)
        try {
            source.open(DataSpec(Uri.parse("bitchord://canonical?broken=%ZZ")))
            fail("Invalid identity should fail")
        } catch (_: IOException) { }
        assertEquals(0, catalog.stream.opens)
        assertEquals(32L, source.open(spec()))
        assertEquals(1, catalog.stream.opens)
        assertTrue(events.none { it.toDiagnosticMetadata().toString().contains("recording-1") })
        source.close()
    }

    @Test fun decoderInputEvidenceCannotBeMisreportedAsDecodedOutputOrAudible() {
        assertEquals("mp3", CanonicalRemoteDecoderInputEvidence.fromMime("audio/mpeg").name.lowercase())
        assertEquals("unknown", CanonicalRemoteDecoderInputEvidence.fromMime("bad/private?token=secret").name.lowercase())
    }
}
