package com.music.bitchord.diagnostics

import java.util.Locale

/** A severity independent of Android's Log API so diagnostics stay testable. */
enum class DiagnosticLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Coarse categories used to filter reports without coupling diagnostics to features. */
enum class DiagnosticCategory {
    APP,
    STARTUP,
    PLAYER,
    UI,
    CATALOG,
    NETWORK,
    DATABASE,
    OTHER,
}

data class DiagnosticEvent(
    val epochMillis: Long,
    val monotonicNanos: Long,
    val sessionId: String,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val name: String,
    val message: String?,
    val threadName: String,
    val durationNanos: Long?,
    val metadata: Map<String, String>,
    val error: String?,
)

/**
 * Small synchronized ring buffer. Diagnostics must never grow with app uptime.
 * Oldest events are discarded first when [capacity] is reached.
 */
class DiagnosticBuffer(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>(capacity)

    val size: Int
        get() = synchronized(lock) { events.size }

    fun append(event: DiagnosticEvent) {
        synchronized(lock) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    fun clear() {
        synchronized(lock) { events.clear() }
    }
}

data class CaptureWindow(
    val expiresAtEpochMillis: Long,
) {
    fun isActive(nowEpochMillis: Long): Boolean = nowEpochMillis < expiresAtEpochMillis
}

/**
 * Best-effort redaction before an event reaches memory, disk or logcat.
 * This deliberately errs on the side of hiding values whose key sounds secret.
 */
object DiagnosticSanitizer {
    const val REDACTED = "<redacted>"

    private val sensitiveKey = Regex(
        pattern = "(?:^|[_\\-.])(authorization|cookie|token|secret|password|passwd|api[_-]?key|session)(?:$|[_\\-.])",
        option = RegexOption.IGNORE_CASE,
    )
    private val compactSensitiveKey = Regex(
        pattern = "^(authorization|cookie|access_?token|refresh_?token|id_?token|password|passwd|secret|api_?key|session(?:id)?)$",
        option = RegexOption.IGNORE_CASE,
    )
    private val authorizationHeader = Regex(
        pattern = "(?i)(\\bauthorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+",
    )
    private val cookieHeader = Regex(
        pattern = "(?i)(\\bcookie\\s*[:=]\\s*)[^\\s]+",
    )
    private val sensitiveQueryValue = Regex(
        pattern = "(?i)([?&](?:access_?token|refresh_?token|id_?token|token|secret|password|passwd|api_?key|session(?:id)?)=)[^&#\\s]+",
    )

    fun metadata(values: Map<String, String>): Map<String, String> =
        values.mapValues { (key, value) ->
            if (isSensitiveKey(key)) REDACTED else text(value)
        }

    fun text(value: String): String {
        var sanitized = value
        sanitized = authorizationHeader.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = cookieHeader.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = sensitiveQueryValue.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        return sanitized
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.trim().lowercase(Locale.ROOT)
        return compactSensitiveKey.matches(normalized) || sensitiveKey.containsMatchIn(normalized)
    }
}

object DiagnosticFormatter {
    fun format(event: DiagnosticEvent): String {
        val duration = event.durationNanos?.let { nanos ->
            " duration=${String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0)}"
        }.orEmpty()
        val message = event.message
            ?.takeIf { it.isNotBlank() }
            ?.let { " message=${DiagnosticSanitizer.text(it)}" }
            .orEmpty()
        val metadata = DiagnosticSanitizer.metadata(event.metadata)
            .toSortedMap()
            .entries
            .joinToString(separator = " ") { (key, value) -> "$key=$value" }
            .takeIf { it.isNotBlank() }
            ?.let { " $it" }
            .orEmpty()
        val error = event.error
            ?.takeIf { it.isNotBlank() }
            ?.let { " error=${DiagnosticSanitizer.text(it)}" }
            .orEmpty()

        return buildString {
            append(event.level.name)
            append(' ')
            append(event.category.name)
            append(' ')
            append(event.name)
            append(duration)
            append(" session=")
            append(event.sessionId)
            append(" thread=")
            append(event.threadName)
            append(message)
            append(metadata)
            append(error)
        }
    }
}
