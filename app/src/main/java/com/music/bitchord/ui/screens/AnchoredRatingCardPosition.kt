package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** Position the rating card next to the actual star, flip above near the foot,
 * and clamp inside the visible window. All positions and gaps are pixels.
 */
internal fun anchoredRatingCardPosition(
    anchor: IntRect,
    popup: IntSize,
    window: IntSize,
    layoutDirection: LayoutDirection,
    gap: Int,
    margin: Int,
): IntOffset {
    val inset = margin.coerceAtLeast(0)
    val spacing = gap.coerceAtLeast(0)
    val left = if (layoutDirection == LayoutDirection.Rtl) anchor.left - spacing
        else anchor.right - popup.width
    val x = left.coerceIn(inset, (window.width - popup.width - inset).coerceAtLeast(inset))
    val below = anchor.bottom + spacing
    val above = anchor.top - popup.height - spacing
    val y = (if (below + popup.height <= window.height - inset) below else above)
        .coerceIn(inset, (window.height - popup.height - inset).coerceAtLeast(inset))
    return IntOffset(x, y)
}
