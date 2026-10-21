package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicDecoderProfile
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicNegotiationEvidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeAudioRequest
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamTruthTracker
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlan
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamVariantPlanner
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTranscodeDecisionOutcome
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTranscodeStreamTicket
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransportStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicStreamTruthTest {
    private fun original() = (
        OpenSubsonicStreamVariantPlanner.original("recording-1", "asset-1", "opensubsonic:home", 5)
            as OpenSubsonicStreamVariantPlan.Accepted
    ).variant
    private fun legacy() = (
        OpenSubsonicStreamVariantPlanner.legacyBestEffort("recording-1", "asset-1", "opensubsonic:home", 5, 128)
            as OpenSubsonicStreamVariantPlan.Accepted
    ).variant
    private fun extension() = (
        OpenSubsonicStreamVariantPlanner.extensionRequested(
            "recording-1", "asset-1", "opensubsonic:home", 5,
            OpenSubsonicStreamConfidence.ADVERTISED, OpenSubsonicDecoderProfile.MP3_HTTP, 192_000,
        ) as OpenSubsonicStreamVariantPlan.Accepted
    ).variant

    private fun negotiatedReady(): OpenSubsonicTranscodeDecisionOutcome.Ready {
        val runtime = OpenSubsonicRuntimeAudioRequest(
            recordingId = "recording-1", mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
            endpointUrl = "https://music.example/rest/stream.view",
            sourceKey = "secret-source-1",
            auth = OpenSubsonicAuth.ApiKey("very-private-api-key"),
            saltFactory = { "salt" },
        )
        return OpenSubsonicTranscodeDecisionOutcome.Ready(
            OpenSubsonicTranscodeStreamTicket(
                recordingId = "recording-1", mediaAssetId = "asset-1",
                providerKey = "opensubsonic:home",
                runtime = runtime, serverEscapedParams = "do-not-log-opaque-token",
            ),
        )
    }

    @Test fun legacyBitrateRequestedIsNeverReportedAsNegotiatedOrActual() {
        val tracker = OpenSubsonicStreamTruthTracker()
        val attempt = tracker.begin(legacy())
        val initial = tracker.snapshot
        assertEquals(128_000, initial.requestedMaxBitrateBps)
        assertEquals(OpenSubsonicNegotiationEvidence.UNKNOWN, initial.negotiation)
        assertNull(initial.actualBitrateBps)
        tracker.onHeaders(attempt, 200, mapOf("content-type" to listOf("audio/mpeg")), 10000L)
        val result = tracker.snapshot
        assertEquals(OpenSubsonicNegotiationEvidence.UNKNOWN, result.negotiation)
        assertNull(result.actualBitrateBps)
        assertNull(result.actualCodec)
        assertNull(result.audible)
        assertNull(result.bitPerfect)
        assertEquals(OpenSubsonicTransportStage.HEADERS_RECEIVED, result.transportStage)
    }

    @Test fun positiveDecisionIsNegotiatedButNotObservedAudioOrAudibility() {
        val tracker = OpenSubsonicStreamTruthTracker()
        val attempt = tracker.begin(extension())
        tracker.onDecision(attempt, negotiatedReady())
        val beforeOpen = tracker.snapshot
        assertEquals(OpenSubsonicNegotiationEvidence.SERVER_DECISION, beforeOpen.negotiation)
        assertEquals(OpenSubsonicTransportStage.NOT_OPENED, beforeOpen.transportStage)
        assertNull(beforeOpen.actualCodec)
        assertNull(beforeOpen.actualBitrateBps)
        assertNull(beforeOpen.audible)
        tracker.onHeaders(attempt, 206, mapOf("content-type" to listOf("audio/mpeg")), 32_000L)
        tracker.onBytes(attempt, 2048)
        val after = tracker.snapshot
        assertEquals(206, after.httpStatusCode)
        assertEquals(2048L, after.receivedByteCount)
        assertEquals("audio/mpeg", after.reportedMimeType)
        assertEquals(OpenSubsonicTransportStage.BYTES_RECEIVED, after.transportStage)
        assertNull(after.actualCodec)
        assertNull(after.actualBitrateBps)
        assertNull(after.bitPerfect)
        assertNull(after.audible)
    }

    @Test fun untrustedHeadersAreBoundedAndNeverDisplayTokensOrUrl() {
        val tracker = OpenSubsonicStreamTruthTracker()
        val attempt = tracker.begin(original())
        tracker.onHeaders(attempt, 200, mapOf(
            "content-type" to listOf("audio/mpeg; charset=binary"),
            "location" to listOf("https://music.example/stream?apiKey=private-key"),
            "set-cookie" to listOf("transcodeParams=very-secret"),
            "x-long" to listOf("X".repeat(150_000)),
        ), -1)
        val result = tracker.snapshot
        assertEquals("audio/mpeg", result.reportedMimeType)
        assertNull(result.reportedLength)
        val shown = result.toString()
        for (forbidden in listOf("music.example", "private-key", "very-secret", "transcodeParams", "recording-1", "asset-1")) {
            assertFalse(shown.contains(forbidden))
        }
        assertTrue(shown.length < 600)
    }

    @Test fun failureAfterPartialBytesDoesNotProveCompletedOrAudiblePlayback() {
        val tracker = OpenSubsonicStreamTruthTracker()
        val attempt = tracker.begin(original())
        tracker.onHeaders(attempt, 200, emptyMap(), null)
        tracker.onBytes(attempt, 128)
        tracker.onFailure(attempt)
        val result = tracker.snapshot
        assertEquals(OpenSubsonicTransportStage.FAILED, result.transportStage)
        assertEquals(128L, result.receivedByteCount)
        assertNull(result.audible)
        assertNull(result.actualCodec)
        assertNull(result.bitPerfect)
    }

    @Test fun staleCallbacksAndInvalidByteCountsNeverCorruptNewAttempt() {
        val tracker = OpenSubsonicStreamTruthTracker()
        val stale = tracker.begin(extension())
        val fresh = tracker.begin(original())
        tracker.onDecision(stale, negotiatedReady())
        tracker.onHeaders(stale, 206, mapOf("content-type" to listOf("audio/aac")), 10L)
        tracker.onBytes(stale, 50)
        tracker.onBytes(fresh, -10)
        val result = tracker.snapshot
        assertEquals(OpenSubsonicNegotiationEvidence.UNKNOWN, result.negotiation)
        assertEquals(OpenSubsonicTransportStage.NOT_OPENED, result.transportStage)
        assertEquals(0L, result.receivedByteCount)
        assertNull(result.reportedMimeType)
    }
}
