package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics

/**
 * Privacy-safe, low-cardinality telemetry for later physical catalog smoke.
 * These are candidate/probe counts, NEVER estimated catalog totals or
 * Apple Music chart rankings. Neither URL, title, query nor ID is recorded.
 */
internal fun appleEditorialProbeEventMetadata(
    surface: String,
    report: AppleEditorialProbeReport,
): Map<String, String> {
    fun count(value: Int): String = value.coerceIn(0, 10_000).toString()
    return mapOf(
        "surface" to when (surface) {
            "home", "genre" -> surface
            else -> "other"
        },
        "candidateSeeds" to count(report.eligibleSeeds),
        "appleRequests" to count(report.probesMade),
        "confirmedProducts" to count(report.matchingProducts),
        "withoutMatch" to count(report.withoutMatchingProducts),
        "failed" to count(report.failedRequests),
        "partial" to count(report.incompleteResponses),
        "budgetLimited" to report.budgetLimited.toString(),
    )
}

internal fun recordAppleEditorialProbeReport(
    surface: String, report: AppleEditorialProbeReport?,
) {
    if (report == null) return
    runCatching {
        Diagnostics.event(
            category = DiagnosticCategory.APP,
            name = "apple_editorial_probe",
            metadata = appleEditorialProbeEventMetadata(surface, report),
        )
    }
}
