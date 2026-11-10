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
 * HALF composes the unrated contour and clips the EXACT matching filled
 * contour at its midpoint. When standalone=true the same player star
 * geometry is used without its circular button platter. Clipping remains
 * vector based; no bitmap, unrelated Material star or altered silhouette.
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
    /** Five-star editors need standalone silhouettes, not five player platters. */
    standalone: Boolean = false,
) {
    val empty = if (standalone) AppleQueueVectors.RatingStarOutline
        else AppleQueueVectors.Favorite
    val filled = if (standalone) AppleQueueVectors.RatingStarFilled
        else AppleQueueVectors.Favorited
    Box(modifier) {
        Icon(
            imageVector = if (fill == AppleRatingStarFill.FULL) filled else empty,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.matchParentSize(),
        )
        if (fill == AppleRatingStarFill.HALF) {
            Icon(
                imageVector = filled,
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
