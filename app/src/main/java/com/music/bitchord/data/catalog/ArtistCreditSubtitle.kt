package com.music.bitchord.data.catalog

import java.util.Locale

/**
 * Extract the credited artist from a historical display subtitle without
 * depending on a network provider parser or changing serialized song data.
 */
internal fun artistCreditFromSubtitle(subtitle: String): String {
    val types = setOf("song", "video", "album", "single", "ep", "artist",
        "playlist", "podcast", "episode")
    val duration = Regex("""\d+:\d{2}""")
    val tally = Regex(
        """[\d.,]+\s*[KMB]?\s+(plays|views|likes|songs|tracks|subscribers|""" +
            """hours?|minutes?|seconds?)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    return subtitle.split(" • ").map(String::trim).filter(String::isNotBlank)
        .firstOrNull { value ->
            value.lowercase(Locale.ROOT) !in types &&
                !duration.matches(value) &&
                !tally.matches(value)
        } ?: subtitle
}
