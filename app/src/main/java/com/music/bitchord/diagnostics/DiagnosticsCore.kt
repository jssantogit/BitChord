package com.music.bitchord.diagnostics

import java.time.Instant
import java.time.format.DateTimeFormatter
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
    val sequence: Long = 0L,
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
    private var nextSequence = 0L
    private var evictions = 0L

    /** Known ring losses since last clear; unknown losses before this process are excluded. */
    val evictedCount: Long
        get() = synchronized(lock) { evictions }

    val size: Int
        get() = synchronized(lock) { events.size }

    fun append(event: DiagnosticEvent): DiagnosticEvent = synchronized(lock) {
        val numbered = if (event.sequence > 0L) event else event.copy(sequence = ++nextSequence)
        if (numbered.sequence > nextSequence) nextSequence = numbered.sequence
        if (events.size == capacity) {
            events.removeFirst()
            evictions++
        }
        events.addLast(numbered)
        numbered
    }

    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    fun clear() {
        synchronized(lock) { events.clear(); evictions = 0L }
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
    // Only for untyped legacy free text. Typed metadata (including traceId) keeps its
    // structured identity and correlation; arbitrary messages never publish raw URIs/UUIDs.
    private val freeTextUri = Regex("""(?i)\b(?:https?://|bitchord://|content://|file://)[^\s\[\]<>"']+""")
    private val freeTextUuid = Regex("""(?i)\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b""")
    // Legacy provider messages include opaque third-party IDs outside URI contexts.
    // Mask only known provider patterns; preserve ordinary text and typed trace metadata.
    private val freeTextProviderMediaId = Regex("""(?i)\b(radio|links):[A-Za-z0-9_-]{11}(?=\s|[,;.!?]|$)""")
    private val freeTextAlbumCatalogId = Regex(
        """(?i)(\bmotion artwork on album\s+'[^'\r\n]{1,150}'\s*\()\d{8,14}(\))""",
    )

    fun metadata(values: Map<String, String>): Map<String, String> =
        values.entries
            .take(MAX_METADATA_ENTRIES)
            .associate { (key, value) ->
                key.take(128) to if (isSensitiveKey(key)) {
                    REDACTED
                } else {
                    (if (key == "traceId") {
                        // Correlation token produced by StructuredTracer; keep the legacy
                        // typed trace correlation contract, never free-text UUIDs.
                        text(value, MAX_METADATA_VALUE_CHARS)
                    } else {
                        freeText(value, MAX_METADATA_VALUE_CHARS)
                    }).replace("\r", "\\r").replace("\n", "\\n")
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

    fun freeText(value: String, maxChars: Int = MAX_TEXT_CHARS): String {
        val sanitized = text(value, maxChars)
        val noCanonicalIds = freeTextUuid.replace(
            freeTextUri.replace(sanitized, "<uri>"),
            "<id>",
        )
        val noExternalVideoIds = freeTextProviderMediaId.replace(noCanonicalIds) {
            "${it.groupValues[1]}:<id>"
        }
        return freeTextAlbumCatalogId.replace(noExternalVideoIds) {
            "${it.groupValues[1]}<id>${it.groupValues[2]}"
        }
    }

    private val sensitiveIdentityKeys = setOf(
        "title", "tracktitle", "artist", "artistname", "album", "albumname",
        "trackid", "videoid", "mediaid", "mediaassetid", "recordingid",
        "providerkey", "sourceurl", "url", "uri",
    )

    private fun isSensitiveKey(key: String): Boolean {
        val compact = key.trim().lowercase(Locale.ROOT).replace(keySeparators, "")
        return compact in sensitiveIdentityKeys ||
            compact == "authorization" ||
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
            ?.let { " message=${DiagnosticSanitizer.freeText(it)}" }
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
            ?.let { " error=${DiagnosticSanitizer.freeText(it)}" }
            .orEmpty()

        return buildString {
            append("schema=v2 utc=")
            append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.epochMillis)))
            append(" seq=")
            append(event.sequence)
            append(" mono_ns=")
            append(event.monotonicNanos)
            append(' ')
            append(event.level.name)
            append(' ')
            append(event.category.name)
            append(' ')
            append(DiagnosticSanitizer.freeText(event.name, 256))
            append(duration)
            append(" session=")
            append(event.sessionId.take(64))
            append(" thread=")
            append(DiagnosticSanitizer.freeText(event.threadName, 128))
            append(message)
            append(metadata)
            append(error)
        }.replace("\r", "\\r").replace("\n", "\\n")
    }
}

object DiagnosticCaptureIntegrity {
    fun flushStatus(flushed: Boolean): String = if (flushed) "complete" else "partial_flush_timeout"
}
