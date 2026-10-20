package com.music.bitchord.diagnostics

import java.time.Instant

/** Immutable point-in-time capture. Unknown counters remain unknown rather than zero. */
data class DiagnosticReportV2Input(
    val recentEvents: List<DiagnosticEvent>,
    val flushComplete: Boolean,
    val rejectedWrites: Long,
    val evictedMemoryEvents: Long,
    val ioFailures: Long? = null,
    val captureRotations: Long? = null,
)

data class DiagnosticReportEvent(
    val sessionId: String,
    val sequence: Long,
    val epochMillis: Long,
    val monotonicNanos: Long,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val name: String,
    val source: String,
) {
    /** Fixed-field JSON only: no raw message, URL, track ID, exception or metadata. */
    fun toJsonLine(): String = buildString {
        append("{\"schema\":2")
        append(",\"session\":\"").append(jsonToken(sessionId)).append('"')
        append(",\"sequence\":").append(sequence)
        append(",\"utc\":\"").append(Instant.ofEpochMilli(epochMillis)).append('"')
        append(",\"monotonicNanos\":").append(monotonicNanos)
        append(",\"level\":\"").append(level.name.lowercase()).append('"')
        append(",\"category\":\"").append(category.name.lowercase()).append('"')
        append(",\"name\":\"").append(jsonToken(name)).append('"')
        append(",\"origin\":\"").append(source).append("\"}")
    }
}

data class DiagnosticReportV2(
    val events: List<DiagnosticReportEvent>,
    val overlapCount: Int,
    val legacyCaptureLines: Int,
    val integrityStatus: String,
    val rejectedWrites: Long,
    val evictedMemoryEvents: Long,
    val ioFailures: Long?,
    val captureRotations: Long?,
) {
    val incidentCount: Int get() = events.count { it.level == DiagnosticLevel.WARN || it.level == DiagnosticLevel.ERROR }

    fun toHumanText(): String = buildString {
        appendLine("schema=v2")
        appendLine("integrity=" + integrityStatus)
        appendLine("unique_events=" + events.size)
        appendLine("incident_count=" + incidentCount)
        appendLine("snapshot_capture_overlap=" + overlapCount)
        appendLine("unparsed_legacy_capture_lines=" + legacyCaptureLines)
        appendLine("writer_rejected=" + rejectedWrites)
        appendLine("ring_evictions=" + evictedMemoryEvents)
        appendLine("io_failures=" + (ioFailures?.toString() ?: "unknown"))
        appendLine("capture_rotations=" + (captureRotations?.toString() ?: "unknown"))
        appendLine("note=historical capture loss before process start is unknown")
    }

    fun toJsonl(): String = events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n") {
        it.toJsonLine()
    }

    companion object {
        /** Safe only for bounded, already captured snapshots; does not read the filesystem. */
        fun build(input: DiagnosticReportV2Input, captureLines: List<String>): DiagnosticReportV2 {
            val dedup = linkedMapOf<Pair<String, Long>, DiagnosticReportEvent>()
            var overlap = 0
            var legacy = 0
            var unknownSequence = -1L
            captureLines.forEach { line ->
                val parsed = parseCapture(line)
                if (parsed == null) {
                    legacy++
                } else {
                    val key = parsed.sessionId to parsed.sequence
                    if (dedup.containsKey(key)) overlap++ else dedup[key] = parsed
                }
            }
            input.recentEvents.forEach { event ->
                val key = event.sessionId to if (event.sequence > 0L) event.sequence else unknownSequence--
                if (dedup.containsKey(key)) {
                    overlap++
                } else {
                    dedup[key] = DiagnosticReportEvent(
                        sessionId = event.sessionId,
                        sequence = event.sequence,
                        epochMillis = event.epochMillis,
                        monotonicNanos = event.monotonicNanos,
                        level = event.level,
                        category = event.category,
                        name = event.name,
                        source = "memory",
                    )
                }
            }
            return DiagnosticReportV2(
                events = dedup.values.sortedWith(
                    compareBy<DiagnosticReportEvent> { it.sessionId }
                        .thenBy { it.sequence }
                        .thenBy { it.monotonicNanos },
                ),
                overlapCount = overlap,
                legacyCaptureLines = legacy,
                integrityStatus = when {
                    !input.flushComplete -> "partial_flush_timeout"
                    input.rejectedWrites > 0L || input.evictedMemoryEvents > 0L ||
                        (input.ioFailures ?: 0L) > 0L -> "partial_known_losses"
                    else -> "complete"
                },
                rejectedWrites = input.rejectedWrites,
                evictedMemoryEvents = input.evictedMemoryEvents,
                ioFailures = input.ioFailures,
                captureRotations = input.captureRotations,
            )
        }

        private val captureHeader = Regex(
            """^schema=v2 utc=(\S+) seq=(\d+) mono_ns=(\d+) (DEBUG|INFO|WARN|ERROR) (APP|STARTUP|PLAYER|UI|CATALOG|NETWORK|DATABASE|OTHER) (\S+)""",
        )
        private val captureSession = Regex("""(?:^|\s)session=(\S+)""")

        private fun parseCapture(line: String): DiagnosticReportEvent? {
            val header = captureHeader.find(line) ?: return null
            val session = captureSession.find(line)?.groupValues?.get(1) ?: return null
            val time = runCatching { Instant.parse(header.groupValues[1]).toEpochMilli() }.getOrNull() ?: return null
            val seq = header.groupValues[2].toLongOrNull() ?: return null
            val mono = header.groupValues[3].toLongOrNull() ?: return null
            if (seq <= 0L || mono < 0L) return null
            return DiagnosticReportEvent(
                sessionId = session,
                sequence = seq,
                epochMillis = time,
                monotonicNanos = mono,
                level = DiagnosticLevel.valueOf(header.groupValues[4]),
                category = DiagnosticCategory.valueOf(header.groupValues[5]),
                name = header.groupValues[6],
                source = "capture",
            )
        }
    }
}

/** Keep JSONL token fields finite and printable; never emit arbitrary log payloads. */
private fun jsonToken(value: String): String = DiagnosticSanitizer.freeText(value.take(256))
    .filter { it.code in 32..126 }
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
