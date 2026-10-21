package com.music.bitchord.diagnostics

/**
 * D6: read-only projection over the existing Diagnostics event stream.
 * There is no second logger and no inferred step completion.
 *
 * A flowId and stage come only from StructuredTracer's ephemeral correlation.
 * Session selection is explicit: a reused identifier cannot silently join
 * evidence from another process. Missing stages are UNKNOWN, not SUCCESS.
 */
enum class DiagnosticJourneyOutcome {
    SUCCEEDED, FAILED, CANCELLED, DEGRADED, RECOVERED, UNKNOWN
}

data class DiagnosticJourneyEvidence(
    val sessionId: String,
    val sequence: Long,
    val stage: String?,
    val outcome: DiagnosticJourneyOutcome,
)

data class DiagnosticJourneyTimeline(
    val flowId: String,
    val sessionId: String?,
    val expectedStages: List<String>,
    val evidence: List<DiagnosticJourneyEvidence>,
    val lastConfirmedStage: String?,
    val firstUnknownStage: String?,
    val terminalOutcome: DiagnosticJourneyOutcome,
    val completed: Boolean,
)

object DiagnosticJourneyReconstructor {
    private val safeToken = Regex("^[a-zA-Z0-9_-]{1,96}$")

    /**
     * A bounded snapshot can have holes. Callers must surface capture/ring
     * drops alongside this projection, never imply a complete history.
     */
    fun reconstruct(
        events: List<DiagnosticEvent>,
        flowId: String,
        expectedStages: List<String>,
    ): DiagnosticJourneyTimeline {
        require(flowId.startsWith("ref_") && safeToken.matches(flowId)) {
            "flowId must be an ephemeral safe reference"
        }
        require(expectedStages.size <= 32 && expectedStages.all(safeToken::matches)) {
            "stages must be bounded taxonomy tokens"
        }
        val matching = events.asSequence()
            .filter { it.metadata["flowId"] == flowId && it.name.startsWith("trace_") }
            .sortedWith(compareBy<DiagnosticEvent> { it.epochMillis }
                .thenBy { it.monotonicNanos }.thenBy { it.sequence })
            .take(512)
            .toList()
        val session = matching.firstOrNull()?.sessionId
        val selected = matching.filter { it.sessionId == session }
        val evidence = selected.map { event ->
            DiagnosticJourneyEvidence(
                sessionId = event.sessionId,
                sequence = event.sequence,
                stage = event.metadata["stage"]?.takeIf(safeToken::matches),
                outcome = outcome(event.metadata["outcome"]),
            )
        }
        val observedStages = evidence.mapNotNull { it.stage }.toSet()
        val firstUnknown = expectedStages.firstOrNull { it !in observedStages }
        val lastConfirmed = evidence.lastOrNull {
            it.stage != null && it.outcome == DiagnosticJourneyOutcome.SUCCEEDED
        }?.stage
        val terminalEvent = selected.lastOrNull { it.name == "trace_end" }
        val terminalOutcome = terminalEvent?.metadata?.get("outcome")?.let(::outcome)
            ?: DiagnosticJourneyOutcome.UNKNOWN
        return DiagnosticJourneyTimeline(
            flowId = flowId,
            sessionId = session,
            expectedStages = expectedStages.toList(),
            evidence = evidence,
            lastConfirmedStage = lastConfirmed,
            firstUnknownStage = firstUnknown,
            terminalOutcome = terminalOutcome,
            completed = terminalEvent != null && terminalOutcome != DiagnosticJourneyOutcome.UNKNOWN,
        )
    }

    private fun outcome(raw: String?): DiagnosticJourneyOutcome = when (raw) {
        "succeeded" -> DiagnosticJourneyOutcome.SUCCEEDED
        "failed" -> DiagnosticJourneyOutcome.FAILED
        "cancelled" -> DiagnosticJourneyOutcome.CANCELLED
        "degraded" -> DiagnosticJourneyOutcome.DEGRADED
        "recovered" -> DiagnosticJourneyOutcome.RECOVERED
        else -> DiagnosticJourneyOutcome.UNKNOWN
    }
}
