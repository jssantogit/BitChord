package com.music.bitchord.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/**
 * Place a frosted menu beside its actual source control, not the window's
 * upper-right edge. All values are pixels; anchor is in window coordinates,
 * overlayOrigin is the overlay's own window position.
 *
 * Prefer below; flip above for a low anchor. Clamp after measuring to avoid
 * clipping at narrow edges, status bars and the keyboard boundary.
 */
internal fun anchoredFrostedMenuPosition(
    anchor: IntRect,
    popup: IntSize,
    overlay: IntSize,
    overlayOrigin: IntOffset,
    gap: Int,
    margin: Int,
): IntOffset {
    val safeGap = gap.coerceAtLeast(0)
    val safeMargin = margin.coerceAtLeast(0)
    val left = (anchor.right - overlayOrigin.x - popup.width).coerceIn(
        safeMargin,
        (overlay.width - popup.width - safeMargin).coerceAtLeast(safeMargin),
    )
    val below = anchor.bottom - overlayOrigin.y + safeGap
    val above = anchor.top - overlayOrigin.y - popup.height - safeGap
    val top = (if (below + popup.height <= overlay.height - safeMargin) below else above)
        .coerceIn(
            safeMargin,
            (overlay.height - popup.height - safeMargin).coerceAtLeast(safeMargin),
        )
    return IntOffset(left, top)
}
