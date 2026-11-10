package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One vocabulary for shared Android action controls, not a blanket rule
 * for transport buttons, text filters or SearchLanding cards.
 *
 * 40dp comes from the traced Library More Options modifier in
 * am-descomp/docs/components/library-toolbar.md. Toolbar 48dp and detail
 * 50dp keep their existing Bitchord roles; neither is asserted to be the
 * measured size of every Apple Music button.
 */
internal object AppleFloatingControlMetrics {
    val libraryDiameter = 40.dp
    val toolbarDiameter = 48.dp
    val toolbarGlyphSize = 24.dp
    val preferredGap = 10.dp
    val detailDiameter = 50.dp
    const val secondaryFillAlpha = 0.07f
    const val secondaryEdgeAlpha = 0.10f
    const val disabledAlpha = 0.38f
}

/**
 * Use every pixel of remaining space for separation before dropping below
 * the preferred gap. Never shrink the 48dp interaction targets to cram them
 * into an unknown device width. An overfull layout still needs route-level
 * decisions about which actions should appear in overflow.
 *
 * Widths are pixels from Compose measurement; Int.MAX_VALUE is an unbounded
 * width and must not cause overflow.
 */
internal fun fittingToolbarActionGapPx(
    itemWidths: List<Int>,
    availableWidthPx: Int,
    preferredGapPx: Int,
): Int {
    if (itemWidths.size <= 1) return 0
    val desired = preferredGapPx.coerceAtLeast(0)
    if (availableWidthPx == Int.MAX_VALUE) return desired
    val occupied = itemWidths.sumOf { it.coerceAtLeast(0).toLong() }
    val spare = (availableWidthPx.toLong() - occupied).coerceAtLeast(0)
    return minOf(desired, (spare / (itemWidths.size - 1)).toInt())
}

/**
 * Secondary (unfilled) action material shared by the icon-only and labeled
 * controls of a detail page. Preserve its existing Bitchord palette, the
 * conditional Liquid Glass rim, and independent accessibility semantics.
 * The global floating header surface is deliberately a separate haze style.
 */
@Composable
internal fun Modifier.appleSecondaryActionSurface(
    shape: CornerBasedShape = CircleShape,
): Modifier = this
    .background(Color.White.copy(alpha = AppleFloatingControlMetrics.secondaryFillAlpha), shape)
    .liquidGlassRim(shape)
    .border(
        1.dp,
        Color.White.copy(alpha = AppleFloatingControlMetrics.secondaryEdgeAlpha),
        shape,
    )
