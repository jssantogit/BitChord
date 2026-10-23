package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTracer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticNavigationTruthD13Test {
    @Test fun compositionDoesNotProveDrawOrVisibility() {
        val points = mutableListOf<StructuredTracePoint>()
        val witness = DiagnosticNavigationWitness(
            DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) })),
            NavigationSurface.MY_RATINGS,
        )
        witness.request()
        witness.target(NavigationSurface.MY_RATINGS)
        witness.composed()
        assertTrue(witness.snapshot().composed)
        assertFalse(witness.snapshot().firstDrawn)
        assertFalse(witness.snapshot().visibleProven)
        assertFalse(points.any { it.stage == "ui_visible" })
    }

    @Test fun drawingRequiresTargetCompositionAndNonZeroBounds() {
        val witness = DiagnosticNavigationWitness(
            DiagnosticEvidenceJourneyRecorder(StructuredTracer()),
            NavigationSurface.SMART_PLAYLIST_BUILDER,
        )
        witness.firstDraw(1080, 1920)
        witness.target(NavigationSurface.MY_RATINGS)
        witness.composed()
        witness.firstDraw(1080, 1920)
        assertFalse(witness.snapshot().firstDrawn)
        witness.target(NavigationSurface.SMART_PLAYLIST_BUILDER)
        witness.firstDraw(0, 1920)
        assertFalse(witness.snapshot().firstDrawn)
        witness.firstDraw(1080, 1920)
        assertTrue(witness.snapshot().firstDrawn)
        assertFalse(witness.snapshot().visibleProven)
    }

    @Test fun smartPlaylistBuilderKeepsItsOwnJourneyIdentity() {
        val points = mutableListOf<StructuredTracePoint>()
        val witness = DiagnosticNavigationWitness(
            DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) })),
            NavigationSurface.SMART_PLAYLIST_BUILDER,
        )
        witness.request()
        witness.target(NavigationSurface.SMART_PLAYLIST_BUILDER)
        witness.composed()
        witness.firstDraw(400, 800)
        witness.dispose()
        val starts = points.filter { it.stage == null && it.operation.startsWith("evidence_") }
        assertEquals(2, starts.size)
        assertEquals("evidence_smart_playlist_builder", starts.first().operation)
        assertEquals(starts.first().operation, starts.last().operation)
        assertFalse(points.any { it.operation == "evidence_my_ratings" })
        assertFalse(points.any { it.stage == "ui_visible" })
    }

    @Test fun disposeDoesNotForgeVisibleFrameOrSuccessfulResult() {
        val points = mutableListOf<StructuredTracePoint>()
        val witness = DiagnosticNavigationWitness(
            DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) })),
            NavigationSurface.MY_RATINGS,
        )
        witness.request()
        witness.dispose()
        witness.composed()
        assertTrue(witness.snapshot().disposed)
        assertFalse(witness.snapshot().composed)
        assertEquals("ui_disposed", points.last { it.stage != null }.stage)
    }
}
