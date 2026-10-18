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
 * Best-effort redaction and payload bounding before an event reaches memory,
 * disk or logcat. This deliberately errs on the side of hiding values whose
 * key sounds secret.
 */
object DiagnosticSanitizer {
    const val REDACTED = "<redacted>"
    const val MAX_TEXT_CHARS = 8_192
    const val MAX_METADATA_VALUE_CHARS = 4_096
    const val MAX_METADATA_ENTRIES = 32

    private val authorizationHeader = Regex(
        pattern = "(?i)(\\bauthorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+",
    )
    private val cookieHeader = Regex(
        pattern = "(?i)(\\bcookie\\s*[:=]\\s*)[^\\s]+",
    )
    private val standaloneBearer = Regex(
        pattern = "(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]+",
    )
    private val knownCookieValue = Regex(
        pattern = "(?i)(\\b(?:SID|HSID|SSID|APISID|SAPISID|__Secure-[A-Za-z0-9_-]+)\\s*=\\s*)[^\\s;,&]+",
    )
    private val sensitiveQueryValue = Regex(
        pattern = "(?i)([?&](?:access_?token|refresh_?token|id_?token|token|secret|password|passwd|api_?key|session(?:id|key)?)=)[^&#\\s]+",
    )
    private val keySeparators = Regex("[_\\-.]")

    fun metadata(values: Map<String, String>): Map<String, String> =
        values.entries
            .take(MAX_METADATA_ENTRIES)
            .associate { (key, value) ->
                key.take(128) to if (isSensitiveKey(key)) {
                    REDACTED
                } else {
                    text(value, MAX_METADATA_VALUE_CHARS)
                }
            }

    fun text(value: String, maxChars: Int = MAX_TEXT_CHARS): String {
        require(maxChars > 0) { "maxChars must be greater than zero" }
        var sanitized = value
        sanitized = authorizationHeader.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = cookieHeader.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = standaloneBearer.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = knownCookieValue.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        sanitized = sensitiveQueryValue.replace(sanitized) { "${it.groupValues[1]}$REDACTED" }
        return sanitized.take(maxChars)
    }

    private fun isSensitiveKey(key: String): Boolean {
        val compact = key.trim().lowercase(Locale.ROOT).replace(keySeparators, "")
        return compact == "authorization" ||
            compact == "cookie" ||
            compact == "session" ||
            compact.endsWith("token") ||
            compact.endsWith("secret") ||
            compact.endsWith("password") ||
            compact.endsWith("passwd") ||
            compact.endsWith("apikey") ||
            compact.endsWith("sessionid") ||
            compact.endsWith("sessionkey")
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
            append(DiagnosticSanitizer.text(event.name, 256))
            append(duration)
            append(" session=")
            append(event.sessionId.take(64))
            append(" thread=")
            append(DiagnosticSanitizer.text(event.threadName, 128))
            append(message)
            append(metadata)
            append(error)
        }
    }
}
