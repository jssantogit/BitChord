package com.music.bitchord.data.catalog.identity

import java.util.Locale

/** Canonicalizes only presentation variants of structurally valid ISRC values. */
internal object IsrcNormalizer {
    private val canonicalPattern = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
    private val presentationPattern = Regex("^[A-Z]{2}-[A-Z0-9]{3}-[0-9]{2}-[0-9]{5}$")

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            ?: return null
        if (canonicalPattern.matches(value)) return value
        if (!presentationPattern.matches(value)) return null
        return value.replace("-", "")
    }

    fun normalizeDistinct(values: Iterable<String?>): List<String> {
        val normalized = LinkedHashSet<String>()
        for (value in values) {
            normalize(value)?.let(normalized::add)
        }
        return normalized.toList()
    }
}
