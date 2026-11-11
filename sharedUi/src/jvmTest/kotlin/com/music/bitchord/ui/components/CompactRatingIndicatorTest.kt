package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import com.music.bitchord.ui.player.AppleQueueVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactRatingIndicatorTest {
    private val nineScores = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0)

    @Test fun nineValuesHaveExactV3VectorGlyphsAndAccessiblePortugueseDescriptions() {
        nineScores.forEach { score ->
            assertEquals(score, normalizedCompactRating(score))
            assertTrue(compactRatingDigitPath(score).startsWith("M"))
            assertTrue(PathParser().parsePathString(compactRatingDigitPath(score)).toPath().isEmpty.not())
            val accessible = compactRatingAccessibility(score)
            assertTrue(accessible.contains(" de 5 estrelas"))
            if (score % 1.0 == 0.5) {
                assertTrue(accessible.contains(",5"))
                // Decimal V3 is two separated subpaths: identical in both geometries.
                assertTrue(compactRatingDigitPath(score).contains("ZM"))
            } else {
                assertTrue(accessible.contains(score.toInt().toString()))
            }
        }
    }

    @Test fun missingAndInvalidValuesNeverCreateScoresOrDigitPaths() {
        val invalid = listOf<Double?>(null, 0.0, 0.5, -1.0, 5.5,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.25)
        invalid.forEach { value ->
            assertEquals(null, normalizedCompactRating(value))
            assertEquals("Sem avaliação", compactRatingAccessibility(value))
        }
        assertEquals("", compactRatingDigitPath(Double.NaN))
    }

    @Test fun v2PaletteAndFortyToSixtyPercentDiagonalBandAreExact() {
        val colors = listOf(
            Color(0xFFD96979), Color(0xFFF09852), Color(0xFFE9C64B),
            Color(0xFF40CBAA), Color(0xFF0091FF),
        )
        (1..5).forEach { index ->
            val stops = compactRatingColorStops(index.toDouble())
            assertEquals(listOf(0f, 1f), stops.map { it.first })
            assertEquals(colors[index - 1], stops.first().second)
            assertEquals(colors[index - 1], stops.last().second)
        }
        (1..4).forEach { index ->
            val stops = compactRatingColorStops(index + 0.5)
            assertEquals(listOf(0f, 0.4f, 0.6f, 1f), stops.map { it.first })
            assertEquals(listOf(colors[index - 1], colors[index - 1],
                colors[index], colors[index]), stops.map { it.second })
        }
        assertTrue(compactRatingColorStops(Double.NaN).isEmpty())
    }

    @Test fun preferencesAndGeometriesRemainOrthogonalToSourceAndRating() {
        assertEquals(2, CompactRatingStyle.entries.size)
        assertEquals(2, CompactRatingGeometry.entries.size)
        assertNotEquals(CompactRatingGeometry.CIRCULAR, CompactRatingGeometry.STANDALONE)
        assertEquals(28f, AppleQueueVectors.RatingStarFilled.viewportWidth, 0.001f)
        assertEquals(28f, AppleQueueVectors.Favorited.viewportWidth, 0.001f)
        assertTrue(AppleQueueVectors.RatingStarContour.isEmpty.not())
    }
}
