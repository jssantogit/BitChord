package com.music.bitchord.diagnostics

/**
 * D15: deterministic, fail-closed privacy policy for untyped legacy DebugLog.
 * Typed structured traces remain the preferred high-fidelity diagnostic path.
 */
internal object DiagnosticLegacyPrivacy {
    private val staticTag = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,47}$")
    private val sensitiveTag = Regex("(?i)(search|query|video.?switch|now.?playing.?title)")
    private val sensitiveFreeText = Regex("(?i)(\\bsearch\\s+query\\b|\\bvideo\\s+switch\\b)")
    private val keyedMediaText = Regex(
        """(?i)\b(title|artist|song|album|query|search|track|videoId)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^;,\r\n]+)""",
    )

    /** A dynamic tag is an untrusted media identifier until proven otherwise. */
    fun tag(raw: String): String = raw.takeIf(staticTag::matches) ?: "other"

    fun message(rawTag: String, raw: String): String {
        if (sensitiveTag.containsMatchIn(rawTag) || sensitiveFreeText.containsMatchIn(raw)) {
            return "legacy_payload_suppressed"
        }
        val withoutMedia = keyedMediaText.replace(raw.take(DiagnosticSanitizer.MAX_TEXT_CHARS)) {
            "${it.groupValues[1]}=<redacted>"
        }
        return DiagnosticSanitizer.freeText(withoutMedia)
    }
}
