package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import java.util.Locale

/**
 * Release Groups are user-facing albums/EPs/singles; Releases are editions.
 * Pick a defensible default to show the native tracklist immediately, while
 * preserving the complete set of MusicBrainz IDs for explicit edition browsing.
 *
 * MusicBrainz status and an actual group-title match outrank geography/date.
 * This is selection of a display *edition*, NOT deduplication of audio identities.
 */
internal fun canonicalPreferredEditions(
    editions: List<MusicBrainzReleaseHit>,
    groupTitle: String,
): List<MusicBrainzReleaseHit> {
    val normalizedGroup = groupTitle.trim().lowercase(Locale.ROOT)
    val modifier = Regex(
        """(?i)(?:\b(?:sped\s*up|slowed|nightcore|remix|bootleg|instrumental|deluxe|special\s+edition|repackage|karaoke|live)\b)"""
    )
    return editions.distinctBy { it.musicBrainzId }.sortedWith(
        compareBy<MusicBrainzReleaseHit> {
            when (it.status?.lowercase(Locale.ROOT)) {
                "official" -> 0
                null, "" -> 1
                "promotion" -> 2
                "pseudo-release" -> 3
                else -> 4
            }
        }.thenBy {
            if (it.title.trim().lowercase(Locale.ROOT) == normalizedGroup) 0 else 1
        }.thenBy {
            if (modifier.containsMatchIn(it.title)) 1 else 0
        }.thenBy {
            if (it.releaseDate?.matches(Regex("""\d{4}(-\d{2}){0,2}""")) == true) 0 else 1
        }.thenBy {
            // A precise date is better evidence than a year-only placeholder.
            when (it.releaseDate?.length) {
                10 -> 0
                7 -> 1
                4 -> 2
                else -> 3
            }
        }.thenBy {
            it.releaseDate ?: "9999-12-31"
        }.thenBy {
            when (it.country) {
                "XW" -> 0 // International release, if all stronger evidence ties.
                "KR" -> 1
                else -> 2
            }
        }.thenBy { it.musicBrainzId },
    )
}
