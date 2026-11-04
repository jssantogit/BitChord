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
    // Unicode combining marks form part of words in Indic, Thai and many
    // other scripts. They are not punctuation, even when NFC cannot compose
    // them into a precomposed character.
    private val punctuation = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val hasLetterOrDigit = Regex("[\\p{L}\\p{N}]")
    private val whitespace = Regex("\\s+")

    /**
     * The manual variation button offers up to two alternatives.
     * A separately bounded, same-session title-only fallback may be sent
     * after the initial exact query finds no compatible candidates.
     */
    /** A search-text-only alternative for explicitly equivalent edition spellings.
     * It grants NO authority to accept the file or change its canonical identity.
     */
    fun editionSpellingVariant(userEntered:String):String?=runCatching {
        val first=normalize(userEntered)
        val expanded=first.replace(
            Regex("\\b(demo|live|acoustic|instrumental|studio|radio) ver\\b",
                RegexOption.IGNORE_CASE),"$1 Version")
            .replace(Regex("\\ba cappella\\b",RegexOption.IGNORE_CASE),"Acapella")
        normalize(expanded).takeIf { it!=first }
    }.getOrNull()

    fun variants(userEntered: String): List<String> {
        val first = normalize(userEntered)
        val variants = linkedSetOf(first)
        // NFKD on an entire Korean title decomposes Hangul syllables into
        // Jamo, yielding an unhelpful search variation that displaces the
        // real title-only fallback. Remove accents from LATIN characters only.
        // Preserve Korean, Japanese and other scripts byte-for-byte.
        val accentMarks=Regex("\\p{M}+")
        val ascii=buildString {
            for (character in first) {
                if(Character.UnicodeScript.of(character.code)==
                    Character.UnicodeScript.LATIN) {
                    append(java.text.Normalizer.normalize(character.toString(),
                        java.text.Normalizer.Form.NFKD).replace(accentMarks,""))
                } else append(character)
            }
        }
        if (ascii != first) variants.add(normalize(ascii))
        editionSpellingVariant(userEntered)?.let(variants::add)
        val parts = userEntered.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            variants.add(normalize(parts[1]))
        }
        return variants.take(3)
    }

    /**
     * The one safe automatic fallback when a structured artist-title search
     * yields no quality-compatible candidates. Do not create a broad query
     * from unstructured or short titles. Identity checks still use the
     * ORIGINAL artist-title request, never this fallback text.
     */
    /**
     * Automatic acquisition requires a real canonical artist AND title.
     * Normalizing an invalid title such as "Artist - ---" would otherwise
     * send an overbroad artist-only query and waste a server search slot.
     * Ordinary user-driven manual search remains allowed to be unstructured.
     */
    fun canonicalArtistTitle(userEntered:String):String?=runCatching {
        normalize(userEntered) // source length, controls and UTF-8 budget
        val parts=userEntered.split(Regex("\\s+[-–—]\\s+"),limit=2)
        if(parts.size!=2)return@runCatching null
        val artist=normalize(parts[0])
        val title=normalize(parts[1])
        normalize("$artist $title")
    }.getOrNull()

    fun titleOnlyFallback(userEntered: String): String? {
        val parts = userEntered.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null
        return runCatching { normalize(parts[1]) }.getOrNull()
            ?.takeIf { it.length >= 3 && it != normalize(userEntered) }
    }

    fun normalize(userEntered: String): String {
        require(userEntered.length in 1..MAX_SOURCE_CHARS) { "Invalid A0 search input length" }
        require(userEntered.none { it.isISOControl() && !it.isWhitespace() }) {
            "A0 search contains unsupported controls"
        }
        require(userEntered.none { it == '\n' || it == '\r' || it == '\t' }) {
            "A0 search must be on one line"
        }
        // Library tags may contain canonically decomposed Unicode
        // (e.g. "o" + combining diaeresis). Compose BEFORE punctuation
        // cleanup so accents never turn into accidental word boundaries.
        // NFC preserves Hangul syllables and all canonical identifiers.
        val composed=A0CjkOrthography.widthOnlyNfc(userEntered)
        val normalized = composed.replace(punctuation, " ")
            .trim()
            .replace(whitespace, " ")
        require(hasLetterOrDigit.containsMatchIn(normalized) &&
            normalized.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_UTF8_BYTES
        ) { "A0 search term is empty or too long" }
        return normalized
    }
}
