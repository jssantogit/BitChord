package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, entirely local pre-download ranking of untrusted filenames.
 * Scores are only sorting hints, not proof of audio identity or integrity.
 * No remote paths, peer names, query text or scores are logged.
 */
internal object A0CandidateRank {
    enum class Match { ALL_TERMS, SOME_TERMS, NO_TERMS }

    data class Ranking(val score: Int, val match: Match)

    private fun tokens(value: String): List<String> {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    }

    fun rank(searchPhrase: String?, name: String, declaredBitrateKbps: Int?): Ranking {
        val title = name.substringBeforeLast('.', name)
        val requested = tokens(searchPhrase.orEmpty()).distinct()
        val actual = tokens(title).toSet()
        val matches = requested.count { it in actual }
        val match = when {
            requested.isEmpty() || matches == 0 -> Match.NO_TERMS
            matches == requested.size -> Match.ALL_TERMS
            else -> Match.SOME_TERMS
        }
        val textScore = when(match) {
            Match.ALL_TERMS -> 80
            Match.SOME_TERMS -> (matches * 45 / requested.size).coerceAtLeast(10)
            Match.NO_TERMS -> 0
        }
        val lower = " " + tokens(title).joinToString(" ") + " "
        val suspicious = listOf(" live ", " remix ", " karaoke ", " instrumental ",
            " nightcore ", " slowed ", " cover ")
            .any { it in lower }
        val bitrateBonus = when (declaredBitrateKbps) {
            in 256..1536 -> 8
            in 192..255 -> 4
            else -> 0
        }
        return Ranking((textScore + bitrateBonus - if (suspicious) 24 else 0)
            .coerceIn(0, 100), match)
    }
}
