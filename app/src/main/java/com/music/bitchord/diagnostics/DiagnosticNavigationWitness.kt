package com.music.bitchord.diagnostics

import com.music.bitchord.observability.TraceOutcome

enum class NavigationSurface {
    MY_RATINGS,
    SMART_PLAYLIST_BUILDER,
}

data class NavigationEvidenceSnapshot(
    val requested: Boolean,
    val targeted: Boolean,
    val composed: Boolean,
    val firstDrawn: Boolean,
    /** No programmatic Compose callback alone proves physical visibility. */
    val visibleProven: Boolean = false,
    val disposed: Boolean,
)

/**
 * D13: observable navigation transitions, deliberately separate from visible
 * pixels. Call this from the owner-approved route/Composable boundaries.
 * Without a callback there is no evidence: no fabricated first-draw event.
 */
class DiagnosticNavigationWitness(
    recorder: DiagnosticEvidenceJourneyRecorder,
    private val surface: NavigationSurface,
) {
    private val trace = recorder.begin(
        when (surface) {
            NavigationSurface.MY_RATINGS -> EvidenceJourney.MY_RATINGS
            NavigationSurface.SMART_PLAYLIST_BUILDER -> EvidenceJourney.SMART_PLAYLIST_BUILDER
        },
    )
    private var requested = false
    private var targeted = false
    private var composed = false
    private var firstDrawn = false
    private var disposed = false

    @Synchronized fun request() {
        if (disposed || requested) return
        requested = true
        trace?.observe(EvidenceStep.ROUTE_REQUESTED, EvidenceTruth.OBSERVED)
    }

    @Synchronized fun target(current: NavigationSurface) {
        if (disposed || targeted && current == surface) return
        targeted = current == surface
        trace?.observe(
            EvidenceStep.ROUTE_TARGET,
            if (targeted) EvidenceTruth.OBSERVED else EvidenceTruth.ABSENT,
        )
    }

    @Synchronized fun composed() {
        if (disposed || composed) return
        composed = true
        trace?.observe(EvidenceStep.UI_COMPOSED, EvidenceTruth.OBSERVED)
    }

    /** Positive bounds are layout/draw evidence, NOT proof pixels were visible. */
    @Synchronized fun firstDraw(width: Int, height: Int) {
        if (disposed || !targeted || !composed || firstDrawn || width <= 0 || height <= 0) return
        firstDrawn = true
        trace?.observe(EvidenceStep.UI_FIRST_DRAW, EvidenceTruth.OBSERVED)
    }

    @Synchronized fun error() {
        if (disposed) return
        trace?.observe(EvidenceStep.UI_ERROR, EvidenceTruth.FAILED)
    }

    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true
        trace?.observe(EvidenceStep.UI_DISPOSED, EvidenceTruth.OBSERVED)
        // Even first draw does not prove visible pixels. Termination remains unknown.
        trace?.finish(TraceOutcome.SKIPPED)
    }

    @Synchronized fun snapshot() = NavigationEvidenceSnapshot(
        requested, targeted, composed, firstDrawn, disposed = disposed,
    )
}
