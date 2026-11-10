package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import com.music.bitchord.ui.player.AppleQueueVectors

/**
 * Same 28-unit native vectors as the expanded player; not the unrelated
 * Material Star / StarHalf / StarBorder silhouette.
 *
 * HALF composes the normal (unrated) vector and clips a second instance of
 * the EXACT filled player vector to its left half. Clipping is done in vector
 * draw space, with no rasterized SVG, path copy or a different star outline.
 */
enum class AppleRatingStarFill { EMPTY, HALF, FULL }

fun appleRatingStarFill(value: Double, index: Int): AppleRatingStarFill {
    if (!value.isFinite() || value !in 1.0..5.0 || index !in 0..4) {
        return AppleRatingStarFill.EMPTY
    }
    return when {
        value >= index + 1.0 -> AppleRatingStarFill.FULL
        value >= index + 0.5 -> AppleRatingStarFill.HALF
        else -> AppleRatingStarFill.EMPTY
    }
}

@Composable
fun AppleRatingStarGlyph(
    fill: AppleRatingStarFill,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(modifier) {
        Icon(
            imageVector = if (fill == AppleRatingStarFill.FULL)
                AppleQueueVectors.Favorited else AppleQueueVectors.Favorite,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.matchParentSize(),
        )
        if (fill == AppleRatingStarFill.HALF) {
            Icon(
                imageVector = AppleQueueVectors.Favorited,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.matchParentSize().drawWithContent {
                    // clipRect changes the implicit DrawScope receiver.
                    // Keep the enclosing ContentDrawScope to draw the icon.
                    clipRect(right = size.width / 2f) {
                        this@drawWithContent.drawContent()
                    }
                },
            )
        }
    }
}
