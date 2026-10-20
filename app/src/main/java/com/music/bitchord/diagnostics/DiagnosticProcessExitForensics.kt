package com.music.bitchord.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build

enum class DiagnosticExitKind {
    RECOVERED_STALL, UNCAUGHT_EXCEPTION_OBSERVED, HISTORICAL_ANR,
    HISTORICAL_PROCESS_EXIT, UNKNOWN_TERMINATION, NO_EXIT_EVIDENCE
}
enum class DiagnosticEvidenceStrength { CONFIRMED, INFERRED, UNKNOWN }

data class DiagnosticExitEvidence(
    val recoveredStallMs: Long? = null,
    val previousProcessEnded: Boolean = false,
    val retainedUncaughtCrash: Boolean = false,
    val platformExitReason: String? = null,
    val platformApiLevel: Int = 0,
)

data class DiagnosticExitFinding(
    val kind: DiagnosticExitKind,
    val strength: DiagnosticEvidenceStrength,
    val source: String,
) {
    val provesAnr: Boolean get() = kind == DiagnosticExitKind.HISTORICAL_ANR
}

/** Observation classification, never attribution of CPU, kernel or feature root cause. */
object DiagnosticProcessExitForensics {
    fun classify(evidence: DiagnosticExitEvidence): DiagnosticExitFinding = when {
        evidence.platformApiLevel >= 30 && evidence.platformExitReason == "anr" ->
            DiagnosticExitFinding(DiagnosticExitKind.HISTORICAL_ANR, DiagnosticEvidenceStrength.CONFIRMED, "android_exit_info")
        evidence.retainedUncaughtCrash ->
            DiagnosticExitFinding(DiagnosticExitKind.UNCAUGHT_EXCEPTION_OBSERVED, DiagnosticEvidenceStrength.CONFIRMED, "retained_handler_snapshot")
        evidence.platformApiLevel >= 30 && evidence.platformExitReason != null ->
            DiagnosticExitFinding(DiagnosticExitKind.HISTORICAL_PROCESS_EXIT, DiagnosticEvidenceStrength.CONFIRMED, "android_exit_info")
        evidence.recoveredStallMs != null ->
            DiagnosticExitFinding(DiagnosticExitKind.RECOVERED_STALL, DiagnosticEvidenceStrength.CONFIRMED, "watchdog_after_recovery")
        evidence.previousProcessEnded ->
            DiagnosticExitFinding(DiagnosticExitKind.UNKNOWN_TERMINATION, DiagnosticEvidenceStrength.UNKNOWN, "last_observation_only")
        else ->
            DiagnosticExitFinding(DiagnosticExitKind.NO_EXIT_EVIDENCE, DiagnosticEvidenceStrength.UNKNOWN, "none")
    }
}

/**
 * Bounded, best-effort, one-shot Android adapter invoked only during an
 * explicitly requested diagnostic export, not at startup or on the main path.
 * Historical reason is platform reported; recency and correlation with a
 * retained crash/flow are *not* established by this probe.
 */
object DiagnosticAndroidExitProbe {
    fun summary(application: Application?): List<String> {
        if (application == null) return listOf("historical_exit_source=unavailable")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOf("historical_exit_source=unsupported_api")
        }
        return try {
            val manager = application.getSystemService(ActivityManager::class.java)
            val item = manager?.getHistoricalProcessExitReasons(application.packageName, 0, 1)
                ?.firstOrNull()
            if (item == null) {
                listOf("historical_exit_source=none")
            } else {
                val reason = when (item.reason) {
                    ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                    else -> "other"
                }
                listOf(
                    "historical_exit_source=android_exit_info",
                    "historical_exit_reason=$reason",
                    "historical_exit_correlation=unknown",
                )
            }
        } catch (_: Throwable) {
            listOf("historical_exit_source=unavailable")
        }
    }
}
