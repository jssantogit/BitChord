package com.music.bitchord.diagnostics

/**
 * D15: deterministic, fail-closed privacy policy for untyped legacy DebugLog.
 * Typed structured traces remain the preferred high-fidelity diagnostic path.
 */
internal object DiagnosticLegacyPrivacy {
    private val recognizedTags = setOf(
        "App", "MainActivity", "Player", "Playback", "PlaybackService",
        "Media3", "Queue", "Search", "VideoSwitch", "Network", "Catalog",
        "OpenSubsonic", "Subsonic", "Room", "Ratings", "Listening", "Artwork",
        "Diagnostics", "NowPlaying", "Downloads", "Library",
    )
    private val sensitiveTag = Regex("(?i)(search|query|video.?switch|now.?playing.?title)")
    private val sensitiveFreeText = Regex("(?i)(\\bsearch\\s+query\\b|\\bvideo\\s+switch\\b)")
    private val keyedMediaText = Regex(
        """(?i)\b(title|artist|song|album|query|search|track|videoId)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^;,\r\n]+)""",
    )

    /** A dynamic tag is an untrusted media identifier until proven otherwise. */
    fun tag(raw: String): String = raw.takeIf { it in recognizedTags } ?: "other"

    fun message(rawTag: String, raw: String): String {
        if (sensitiveTag.containsMatchIn(rawTag) || sensitiveFreeText.containsMatchIn(raw)) {
            return "legacy_payload_suppressed"
        }
        val withoutMedia = keyedMediaText.replace(raw.take(DiagnosticSanitizer.MAX_TEXT_CHARS)) {
            "${it.groupValues[1]}=<redacted>"
        }
        return DiagnosticSanitizer.freeText(withoutMedia)
    }


    private val unsafeStackSymbol = Regex("[^A-Za-z0-9_.$]")

    /**
     * Legacy Throwable.message and cause text are untyped media payloads.
     * Keep only bounded static exception type + stack symbols, never raw messages,
     * filenames or serialized causes. Structured trace errors are unchanged.
     */
    fun error(rawTag: String, throwable: Throwable): String {
        if (sensitiveTag.containsMatchIn(rawTag)) return "legacy_payload_suppressed"
        fun symbol(value: String, max: Int): String =
            unsafeStackSymbol.replace(value.take(max), "_")
        val exceptionType = symbol(throwable.javaClass.name, 128)
        val frames = throwable.stackTrace.take(16).joinToString("\n") { frame ->
            "at ${symbol(frame.className, 160)}.${symbol(frame.methodName, 96)}:" +
                frame.lineNumber.coerceAtLeast(0)
        }
        return DiagnosticSanitizer.freeText("exception=$exceptionType\n$frames")
    }
}
