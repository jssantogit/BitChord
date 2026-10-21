package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTrace
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceFlowContext
import com.music.bitchord.observability.TraceOutcome

/**
 * D11: small stable vocabulary owned by Diagnostics. Domain owners may call this
 * seam at their actual boundaries without exposing songs, accounts or source IDs.
 * A stage not emitted is UNKNOWN, never evidence of success.
 */
enum class EvidenceJourney(val wireName: String, val feature: TraceFeature) {
    REMOTE_RATINGS("remote_ratings", TraceFeature.PROVIDER),
    CANONICAL_RATINGS("canonical_ratings", TraceFeature.CATALOG),
    ROOM_RATINGS("room_ratings", TraceFeature.DATABASE),
    MY_RATINGS("my_ratings", TraceFeature.UI),
    NOW_PLAYING("now_playing", TraceFeature.UI),
}

enum class EvidenceStep(val wireName: String) {
    PROVIDER_ENUMERATION("provider_enumeration"),
    REMOTE_RATING_FIELD("remote_rating_field"),
    REMOTE_STARRED_FIELD("remote_starred_field"),
    CANONICAL_MATCH("canonical_match"),
    CATALOG_APPLY("catalog_apply"),
    ROOM_SUBSCRIPTION("room_subscription"),
    ROOM_QUERY("room_query"),
    ROOM_EMISSION("room_emission"),
    ROUTE_REQUESTED("route_requested"),
    ROUTE_TARGET("route_target"),
    UI_COMPOSED("ui_composed"),
    UI_FIRST_DRAW("ui_first_draw"),
    UI_VISIBLE("ui_visible"),
    UI_EMPTY_STATE("ui_empty_state"),
    UI_DISPOSED("ui_disposed"),
    UI_ERROR("ui_error"),
    FRAME_WINDOW("frame_window"),
    MAIN_STALL_RECOVERED("main_stall_recovered"),
    UI_MEASURE("ui_measure"),
    UI_LAYOUT("ui_layout"),
    UI_DRAW("ui_draw"),
}

enum class EvidenceTruth(val wireName: String, val outcome: TraceOutcome) {
    OBSERVED("observed", TraceOutcome.SUCCEEDED),
    ABSENT("absent", TraceOutcome.SKIPPED),
    UNSUPPORTED("unsupported", TraceOutcome.SKIPPED),
    UNKNOWN("unknown", TraceOutcome.SKIPPED),
    PARTIAL("partial", TraceOutcome.DEGRADED),
    FAILED("failed", TraceOutcome.FAILED),
}

/**
 * No new logger, persistent ID, authentication metadata or telemetry upload.
 * StructuredTracer owns the point budget and the per-capture salted flowId.
 */
class DiagnosticEvidenceJourneyRecorder(
    private val tracer: StructuredTracer,
    private val active: () -> Boolean = { true },
) {
    fun begin(
        journey: EvidenceJourney,
        parent: TraceFlowContext? = null,
    ): DiagnosticEvidenceJourney? = try {
        if (!active()) null else DiagnosticEvidenceJourney(
            tracer.startTrace(
                feature = journey.feature,
                operation = "evidence_" + journey.wireName,
                attributes = mapOf("journey" to journey.wireName, "taxonomy" to "d11_v1"),
                parent = parent,
            ),
        )
    } catch (_: Throwable) {
        // Diagnostics is best-effort; it cannot affect the app or provider.
        null
    }
}

/** Handle can be passed explicitly across coroutine, navigation and DB boundaries. */
class DiagnosticEvidenceJourney internal constructor(private val trace: StructuredTrace) {
    fun context(): TraceFlowContext = trace.context()

    fun observe(step: EvidenceStep, truth: EvidenceTruth, count: Int? = null) {
        try {
            val fields = linkedMapOf("truth" to truth.wireName)
            // Unknown count is not zero. Counts are capped to avoid high cardinality.
            if (truth == EvidenceTruth.OBSERVED && count != null && count >= 0) {
                fields["count"] = if (count > 1_000) "1000_plus" else count.toString()
            }
            trace.event(step.wireName, truth.outcome, fields)
        } catch (_: Throwable) {
            // Logger failure must never throw back into a feature boundary.
        }
    }

    fun finish(outcome: TraceOutcome) {
        try {
            if (outcome != TraceOutcome.STARTED) trace.complete(outcome)
        } catch (_: Throwable) {
            // Trace completion is best-effort and idempotent.
        }
    }
}
