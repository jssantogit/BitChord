package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale

/**
 * Fair, deterministic candidate planning for the PUBLIC iTunes Search API.
 * MusicBrainz seeds only suggest queries; no title/genre/artwork/MBID is used
 * without an independently returned Apple storefront product.
 *
 * Distinct artists receive the first probe before one source artist can
 * monopolize all eight ~3.1s-rate-limited requests. Product-order and source
 * priority remain stable within each artist.
 */
internal object AppleEditorialSeedPlanner {
    private val whitespace = Regex("""\s+""")
    private val editorialSuffix = Regex(
        """\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE,
    )

    fun normalized(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .replace(whitespace, " ").lowercase(Locale.ROOT)

    fun titleKey(value: String): String = normalized(
        value.replace(editorialSuffix, ""),
    )

    fun eligible(seeds: List<AppleHomeEditorialSeed>): List<AppleHomeEditorialSeed> =
        seeds.asSequence()
            .map { it.copy(title = it.title.trim(), artist = it.artist.trim()) }
            .filter { seed ->
                seed.title.length in 2..120 && seed.artist.length in 1..120 &&
                    seed.title.none(Char::isISOControl) &&
                    seed.artist.none(Char::isISOControl) &&
                    canonicalStreamingTitleIsSafe(seed.title)
            }
            .distinctBy { titleKey(it.title) to normalized(it.artist) }
            .toList()

    fun selectEligible(
        eligible: List<AppleHomeEditorialSeed>, maxProbes: Int,
    ): List<AppleHomeEditorialSeed> {
        if (maxProbes <= 0) return emptyList()
        val groups = eligible.groupBy { normalized(it.artist) }.values
            .map { it.iterator() }
        val result = ArrayList<AppleHomeEditorialSeed>(maxProbes)
        while (result.size < maxProbes) {
            var added = false
            for (artist in groups) {
                if (artist.hasNext()) {
                    result += artist.next()
                    added = true
                    if (result.size == maxProbes) return result
                }
            }
            if (!added) break
        }
        return result
    }

    fun select(
        seeds: List<AppleHomeEditorialSeed>, maxProbes: Int,
    ): List<AppleHomeEditorialSeed> =
        selectEligible(eligible(seeds), maxProbes)
}
