package com.music.bitchord.diagnostics

import com.music.bitchord.data.TrackLog
import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyCancellationD1Test {
    @Test fun trackLogClipboardBoundaryHidesAuthenticatedUrisAndLegacyIds() {
        val uuid = "dd51b6f2-2a2a-4f7b-aa8f-a8aa0136a70e"
        val text = TrackLog.sanitizeForExport(
            "resolver https://private.invalid/media?id=$uuid&token=verysecret " +
                "radio:AbCD12_-xYz links:XYz_9876-Ab Bearer abc.def; status=503",
        )
        listOf(uuid, "private.invalid", "verysecret", "AbCD12_-xYz", "XYz_9876-Ab", "abc.def")
            .forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("status=503"))
        assertTrue(text.contains("radio:<id>"))
    }

    @Test fun spanSuspendPreservesCancellationAsCancellation() = runBlocking {
        val points = mutableListOf<StructuredTracePoint>()
        val trace = StructuredTracer(
            sink = StructuredTraceSink { points += it },
            traceIdFactory = { "session-trace" },
        ).startTrace(TraceFeature.PROVIDER, "resolve")
        val error = runCatching {
            trace.spanSuspend("network_request") { throw CancellationException("user stopped") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(
            TraceOutcome.CANCELLED,
            points.single { it.kind == TracePointKind.SPAN }.outcome,
        )
        assertEquals(TraceOutcome.STARTED, points.first().outcome)
    }
}
