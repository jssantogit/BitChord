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
    // Recent-listen covers use the existing square shelf composition, not the
    // editorials' tall overlay. Keep the skeleton and real cards identical.
    val recentCoverWidth = shelfWidth
    const val recentCoverRatio = 1f
    val squareCoverRadius = 12.dp
    val squareCoverShape = RoundedCornerShape(squareCoverRadius)

    const val discoveryAspect = 1.777f
    val discoveryCornerRadius = 7.dp
    val discoveryShape = RoundedCornerShape(discoveryCornerRadius)
    val discoveryTitleInset = 10.dp

    // Existing Bitchord effect roles, not original Apple pixel measurements.
    // Hero contrast belongs only behind titles over cover art.
    val heroShape = RoundedCornerShape(18.dp)
    const val heroScrimAlpha = 0.78f

    // Discovery cards use a lighter overlay and text shadow over their duotone.
    const val discoveryScrimAlpha = 0.34f
    const val discoveryTitleShadowAlpha = 0.30f

    // The featured-release glass panel is a separate card family, not a cover.
    val featuredShape = RoundedCornerShape(28.dp)
    val featuredArtworkSize = 88.dp
    val featuredArtworkGap = 14.dp
    const val featuredFillAlpha = 0.07f
    const val featuredEdgeAlpha = 0.215f
}
