package com.music.bitchord

import com.music.bitchord.ui.CanonicalExploreGenre
import com.music.bitchord.ui.canonicalGenreArtworkPriority
import com.music.bitchord.ui.canonicalWorldwideArtworkPriority
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalPublicArtworkPriorityTest {
    private fun row(number: Int): CanonicalCatalogSearchRow =
        CanonicalCatalogSearchRow(
            musicBrainzId = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}",
            kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Release ${number}",
            subtitle = "Artist ${number}",
            releaseDate = "2026-11-01",
            releaseKind = if (number % 2 == 0) "Album" else "Single",
        )

    @Test fun worldwideArtworkPrioritizesMoreThanTheOldFirstEighteen() {
        val rows = (1..40).map(::row)
        val priority = canonicalWorldwideArtworkPriority(rows, "2026-11-06")
        assertEquals(32, priority.size)
        assertEquals(priority.size, priority.distinctBy { it.musicBrainzId }.size)
        assertTrue(priority.take(12).map { it.musicBrainzId } ==
            rows.take(12).map { it.musicBrainzId })
        assertTrue(priority.any { it.musicBrainzId == rows.last().musicBrainzId }.not())
        assertTrue(priority.drop(12).any { it.musicBrainzId !in
            rows.take(18).map { first -> first.musicBrainzId } })
        assertTrue(canonicalWorldwideArtworkPriority(rows, "2026-11-06", 0).isEmpty())
    }

    @Test fun genreArtworkTargetsVisibleEditorialCardsOnly() {
        val rows = (1..30).map(::row)
        val priority = canonicalGenreArtworkPriority(
            CanonicalExploreGenre("rock", "Rock"), rows, "2026-11-06",
        )
        assertTrue(priority.isNotEmpty())
        assertEquals(priority.size, priority.distinctBy { it.musicBrainzId }.size)
        assertTrue(priority.all { it in rows })
    }
}
