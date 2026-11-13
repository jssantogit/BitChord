package com.music.bitchord.ui.components

import kotlin.math.roundToInt

/** Keep floating app chrome outside the readable held-preview/menu region. */
internal fun heldMenuVisibleBottom(
    viewportHeight: Int,
    systemBottomInset: Int,
    chromeReserve: Int,
): Int = (viewportHeight - systemBottomInset - chromeReserve).coerceIn(0, viewportHeight)

/** Reserve enough space for a selected row while making the action list scrollable if needed. */
internal fun heldMenuMaxHeight(
    availableHeight: Int,
    minPreview: Int,
    gap: Int,
): Int = minOf(
    (availableHeight - minPreview - gap).coerceAtLeast(0),
    (availableHeight.coerceAtLeast(0) * 0.80f).roundToInt(),
)

/** Shrink large grid tiles rather than pushing the action list out of view. */
internal fun heldMenuPreviewScale(
    plateWidth: Int,
    plateHeight: Int,
    viewportWidth: Int,
    margin: Int,
    availableHeight: Int,
    menuHeight: Int,
    gap: Int,
): Float {
    val widthScale = (viewportWidth - 2 * margin).coerceAtLeast(1).toFloat() /
        plateWidth.coerceAtLeast(1)
    val heightScale = (availableHeight - menuHeight - gap).coerceAtLeast(1).toFloat() /
        plateHeight.coerceAtLeast(1)
    return minOf(1f, widthScale, heightScale).coerceAtLeast(0.02f)
}

/** A real captured page is blurred; dim-only is reserved for the accessibility setting. */
internal fun shouldBlurHeldContextBackdrop(
    heldVisible: Boolean,
    reduceDynamicBlur: Boolean,
): Boolean = heldVisible && !reduceDynamicBlur

/** A parent that already blurs the actual composed page must not Haze-sample
 * it again; that second pass can capture only the black page plate. */
internal fun heldMenuNeedsOwnHazeSource(
    hostProvidesBlur: Boolean,
    reduceDynamicBlur: Boolean,
): Boolean = !hostProvidesBlur && !reduceDynamicBlur
