package com.music.bitchord.data.catalog.identity

import java.util.Locale

/** Canonicalizes only presentation variants of structurally valid ISRC values. */
internal object IsrcNormalizer {
    private val canonicalPattern = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val canonical = value.replace("-", "").uppercase(Locale.ROOT)
        return canonical.takeIf(canonicalPattern::matches)
    }

    fun normalizeDistinct(values: Iterable<String?>): List<String> {
        val normalized = LinkedHashSet<String>()
        for (value in values) {
            normalize(value)?.let(normalized::add)
        }
        return normalized.toList()
    }
}
