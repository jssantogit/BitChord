package com.music.bitchord.diagnostics

/**
 * Offline, bounded incident projection from the existing diagnostic events.
 * An incident groups evidence, but does not assert a root cause.
 */
data class DiagnosticTriageIncident(
    val incidentId: String,
    val category: DiagnosticCategory,
    val reasonCode: String,
    val outcome: DiagnosticFunctionalTerminal,
    val occurrences: Int,
    val firstObservedUtcMillis: Long,
    val lastObservedUtcMillis: Long,
    val flowId: String?,
    val lastConfirmedStage: String?,
    val severity: DiagnosticLevel,
    val causeStrength: String = "unknown",
)

data class DiagnosticIncidentFilter(
    val category: DiagnosticCategory? = null,
    val outcome: DiagnosticFunctionalTerminal? = null,
    val flowId: String? = null,
    val sinceUtcMillis: Long? = null,
    val untilUtcMillis: Long? = null,
    val minimumLevel: DiagnosticLevel? = null,
)

class DiagnosticIncidentTriage private constructor(
    val incidents: List<DiagnosticTriageIncident>,
    val rejectedWrites: Long,
    val flushComplete: Boolean,
    val eventWindow: Int,
) {
    fun filter(query: DiagnosticIncidentFilter): List<DiagnosticTriageIncident> =
        incidents.filter {
            (query.category == null || it.category == query.category) &&
                (query.outcome == null || it.outcome == query.outcome) &&
                (query.flowId == null || it.flowId == query.flowId) &&
                (query.sinceUtcMillis == null || it.lastObservedUtcMillis >= query.sinceUtcMillis) &&
                (query.untilUtcMillis == null || it.firstObservedUtcMillis <= query.untilUtcMillis) &&
                (query.minimumLevel == null || it.severity.ordinal >= query.minimumLevel.ordinal)
        }

    fun toHumanLines(): List<String> = buildList {
        add("schema=v3")
        add("source=in_memory_snapshot_only")
        add("integrity=" + if (flushComplete && rejectedWrites == 0L) "memory_window_only" else "partial_known_losses")
        add("writer_rejected=" + rejectedWrites)
        add("observed_event_window=" + eventWindow)
        add("incident_count=" + incidents.size)
        add("caution=incident_count_not_warn_error_count")
        add("unknown=unobserved_stages_prior_capture_drops_root_cause")
        incidents.forEach { item ->
            add("incident=" + item.incidentId + " category=" + item.category.name.lowercase() +
                " reason=" + item.reasonCode + " occurrences=" + item.occurrences +
                " outcome=" + item.outcome.name.lowercase() +
                " last_confirmed_stage=" + (item.lastConfirmedStage ?: "unknown") +
                " cause_strength=" + item.causeStrength)
        }
    }

    /** Fixed typed tokens only; excludes raw logs, credentials and identities. */
    fun toJsonl(): String = incidents.joinToString(
        separator = "\n",
        postfix = if (incidents.isEmpty()) "" else "\n",
    ) {
        buildString {
            append("{\"schema\":3,\"type\":\"incident\",\"id\":\"").append(it.incidentId)
            append("\",\"category\":\"").append(it.category.name.lowercase())
            append("\",\"reason\":\"").append(it.reasonCode)
            append("\",\"outcome\":\"").append(it.outcome.name.lowercase())
            it.flowId?.let { safeFlow ->
                append("\",\"flowId\":\"").append(safeFlow)
            }
            append("\",\"occurrences\":").append(it.occurrences)
            append(",\"firstUtcMillis\":").append(it.firstObservedUtcMillis)
            append(",\"lastUtcMillis\":").append(it.lastObservedUtcMillis)
            append(",\"causeStrength\":\"unknown\"")
            append(",\"lastConfirmedStage\":\"").append(it.lastConfirmedStage ?: "unknown")
            append("\"}")
        }
    }

    companion object {
        private const val MAX_EVENTS = 1_024
        private const val MAX_INCIDENTS = 64
        private val safeStage = Regex("^[a-zA-Z0-9_-]{1,96}$")
        private val safeFlow = Regex("^ref_[A-Za-z0-9_-]{1,80}$")

        fun build(
            events: List<DiagnosticEvent>,
            rejectedWrites: Long = 0L,
            flushComplete: Boolean = true,
        ): DiagnosticIncidentTriage {
            val window = events.takeLast(MAX_EVENTS)
            val rows = mutableListOf<DiagnosticTriageIncident>()
            DiagnosticFailureIncidentSynthesizer.summarize(window).forEach { item ->
                val stage = item.flowId?.let { flow ->
                    runCatching { DiagnosticJourneyReconstructor.reconstruct(window, flow, emptyList()) }
                        .getOrNull()?.lastConfirmedStage
                }?.takeIf(safeStage::matches)
                rows.add(DiagnosticTriageIncident(
                    incidentId = "i_" + (rows.size + 1),
                    category = item.category,
                    reasonCode = item.reasonCode,
                    outcome = item.terminalOutcome,
                    occurrences = item.occurrences,
                    firstObservedUtcMillis = item.firstObservedUtcMillis,
                    lastObservedUtcMillis = item.lastObservedUtcMillis,
                    flowId = item.flowId,
                    lastConfirmedStage = stage,
                    severity = if (item.terminalOutcome == DiagnosticFunctionalTerminal.FAILED)
                        DiagnosticLevel.ERROR else DiagnosticLevel.WARN,
                ))
            }
            window.forEach { event ->
                if (rows.size >= MAX_INCIDENTS) return@forEach
                if (event.name != "main_thread_stall" && event.name != "uncaught_exception") return@forEach
                rows.add(DiagnosticTriageIncident(
                    incidentId = "i_" + (rows.size + 1),
                    category = event.category,
                    reasonCode = if (event.name == "main_thread_stall")
                        "recovered_stall" else "uncaught_exception_observed",
                    outcome = if (event.name == "uncaught_exception") DiagnosticFunctionalTerminal.FAILED
                        else DiagnosticFunctionalTerminal.UNKNOWN,
                    occurrences = 1,
                    firstObservedUtcMillis = event.epochMillis,
                    lastObservedUtcMillis = event.epochMillis,
                    flowId = event.metadata["flowId"]?.takeIf(safeFlow::matches),
                    lastConfirmedStage = null,
                    severity = event.level,
                ))
            }
            return DiagnosticIncidentTriage(
                incidents = rows.sortedWith(compareBy<DiagnosticTriageIncident> { it.firstObservedUtcMillis }
                    .thenBy { it.incidentId }).take(MAX_INCIDENTS),
                rejectedWrites = rejectedWrites.coerceAtLeast(0L),
                flushComplete = flushComplete,
                eventWindow = window.size,
            )
        }
    }
}
