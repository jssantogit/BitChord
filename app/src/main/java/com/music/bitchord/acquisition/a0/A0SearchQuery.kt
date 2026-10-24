package com.music.bitchord.acquisition.a0

/**
 * A0 development-only read-only user-entered Soulseek search term.
 *
 * Matches Nicotine+ search's key interoperability rule: replace punctuation
 * (including isolated '-' and trailing '*') with spaces, collapse whitespace.
 * The result is bounded before any network I/O. Do not put the raw or
 * normalized term in diagnostics, error output, persistence or analytics.
 *
 * Search results are untrusted metadata. This function has NO authority
 * to select a file for transfer or bypass the independent CC0 candidate gate.
 */
internal object A0SearchQuery {
    private const val MAX_SOURCE_CHARS = 120
    private const val MAX_WIRE_UTF8_BYTES = 160
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    fun normalize(userEntered: String): String {
        require(userEntered.length in 1..MAX_SOURCE_CHARS) { "Invalid A0 search input length" }
        require(userEntered.none { it.isISOControl() && !it.isWhitespace() }) {
            "A0 search contains unsupported controls"
        }
        require(userEntered.none { it == '\n' || it == '\r' || it == '\t' }) {
            "A0 search must be on one line"
        }
        val normalized = userEntered.replace(punctuation, " ")
            .trim()
            .replace(whitespace, " ")
        require(normalized.isNotEmpty() &&
            normalized.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_UTF8_BYTES
        ) { "A0 search term is empty or too long" }
        return normalized
    }
}
