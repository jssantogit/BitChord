package com.music.bitchord.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared media card *roles*, not a single shape/blur for every kind of content.
 *
 * Standard square shelves retain Bitchord's established 150dp/12dp/10dp
 * composition. The Explore genre tile is a deliberate adaptation of the
 * am-descomp search_landing_tile: declared 1.777 ratio, 7dp corner and 10dp
 * bottom-title inset. No runtime measure for Apple editorial/album cards is
 * claimed, and no unverified global blur is added.
 */
object NativeMediaCardMetrics {
    val shelfWidth = 150.dp
    val shelfCaptionGap = 10.dp
    val squareCoverRadius = 12.dp
    val squareCoverShape = RoundedCornerShape(squareCoverRadius)

    const val discoveryAspect = 1.777f
    val discoveryCornerRadius = 7.dp
    val discoveryShape = RoundedCornerShape(discoveryCornerRadius)
    val discoveryTitleInset = 10.dp
}
