package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
    /** Preserve positional API compatibility with existing player calls. */
    standalone: Boolean = false,
    /** Optional fractional score tint: mask the gradient into the existing SVG. */
    colorBrush: Brush? = null,
    /** Outline stays theme-neutral even when a half-star fill is colored. */
    outlineTint: Color = tint,
) {
    val empty = if (standalone) AppleQueueVectors.RatingStarOutline
        else AppleQueueVectors.Favorite
    val filled = if (standalone) AppleQueueVectors.RatingStarFilled
        else AppleQueueVectors.Favorited
    // Apply a gradient only to the colored filled vector. The outline, when
    // present, must keep its distinct neutral stroke; color is masked by the
    // same alpha silhouette used everywhere in the app.
    val fillPaint = if (colorBrush == null) Modifier else Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(brush = colorBrush, blendMode = BlendMode.SrcIn)
        }
    Box(modifier) {
        Icon(
            imageVector = if (fill == AppleRatingStarFill.FULL) filled else empty,
            contentDescription = null,
            tint = when {
                fill == AppleRatingStarFill.HALF -> outlineTint
                colorBrush != null -> Color.White
                else -> tint
            },
            modifier = Modifier.matchParentSize()
                .then(if (fill == AppleRatingStarFill.FULL) fillPaint else Modifier),
        )
        if (fill == AppleRatingStarFill.HALF) {
            Icon(
                imageVector = filled,
                contentDescription = null,
                tint = if (colorBrush != null) Color.White else tint,
                modifier = Modifier.matchParentSize()
                    .then(fillPaint)
                    .drawWithContent {
                        // The mask is applied after the half clipping, so the
                        // right half remains transparent on fractional scores.
                        clipRect(right = size.width / 2f) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )
        }
    }
}
