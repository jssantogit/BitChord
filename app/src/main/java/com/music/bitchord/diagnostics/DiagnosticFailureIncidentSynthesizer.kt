package com.music.bitchord.diagnostics

enum class DiagnosticFunctionalTerminal { UNKNOWN, FAILED, RECOVERED, CANCELLED }

data class DiagnosticFailureIncident(
    val sessionId: String,
    val category: DiagnosticCategory,
    val reasonCode: String,
    val flowId: String?,
    val attemptRef: String?,
    val occurrences: Int,
    val firstObservedUtcMillis: Long,
    val lastObservedUtcMillis: Long,
    val lastObservedAction: String,
    val terminalOutcome: DiagnosticFunctionalTerminal,
) {
    /** Unscoped warnings are clusters, not proof of a single retry/attempt. */
    val attemptBoundaryKnown: Boolean get() = attemptRef != null
}

/**
 * D9: incident projection from the D3 typed failure/recovery breadcrumbs.
 * No retries are performed; no failure can be hidden by a subsequent success.
 */
object DiagnosticFailureIncidentSynthesizer {
    private const val MAX_EVENTS = 1_024
    private const val MAX_INCIDENTS = 64
    private val allowedReasons = DiagnosticFailureReason.entries.map { it.wireName }.toSet()
    private val allowedActions = DiagnosticRecoveryOutcome.entries.map { it.wireName }.toSet()
    private val safeReference = Regex("^ref_[A-Za-z0-9_-]{1,80}$")

    fun summarize(events: List<DiagnosticEvent>): List<DiagnosticFailureIncident> {
        data class Key(
            val session: String, val category: DiagnosticCategory, val reason: String,
            val flow: String?, val attempt: String?,
        )
        data class MutableIncident(
            var count: Int, var first: Long, var last: Long, var lastAction: String,
            var terminal: DiagnosticFunctionalTerminal,
        )
        val grouped = linkedMapOf<Key, MutableIncident>()
        events.takeLast(MAX_EVENTS).forEach { event ->
            if (event.name != "failure_recovery") return@forEach
            val reason = event.metadata["reasonCode"]?.takeIf { it in allowedReasons } ?: return@forEach
            val action = event.metadata["recoveryOutcome"]?.takeIf { it in allowedActions } ?: return@forEach
            val flow = event.metadata["flowId"]?.takeIf(safeReference::matches)
            val attempt = event.metadata["attemptRef"]?.takeIf(safeReference::matches)
            val key = Key(event.sessionId, event.category, reason, flow, attempt)
            val terminal = when (action) {
                "recovered" -> DiagnosticFunctionalTerminal.RECOVERED
                "failed" -> DiagnosticFunctionalTerminal.FAILED
                else -> DiagnosticFunctionalTerminal.UNKNOWN
            }
            val current = grouped[key]
            if (current == null) {
                if (grouped.size >= MAX_INCIDENTS) return@forEach
                grouped[key] = MutableIncident(1, event.epochMillis, event.epochMillis, action, terminal)
            } else {
                current.count++
                current.first = minOf(current.first, event.epochMillis)
                if (event.epochMillis >= current.last) {
                    current.last = event.epochMillis
                    current.lastAction = action
                    if (terminal != DiagnosticFunctionalTerminal.UNKNOWN) current.terminal = terminal
                }
            }
        }
        return grouped.map { (key, value) ->
            DiagnosticFailureIncident(
                sessionId = key.session, category = key.category,
                reasonCode = key.reason, flowId = key.flow, attemptRef = key.attempt,
                occurrences = value.count,
                firstObservedUtcMillis = value.first,
                lastObservedUtcMillis = value.last,
                lastObservedAction = value.lastAction,
                terminalOutcome = value.terminal,
            )
        }.sortedWith(compareBy<DiagnosticFailureIncident> { it.firstObservedUtcMillis }
            .thenBy { it.reasonCode }).take(MAX_INCIDENTS)
    }
}
