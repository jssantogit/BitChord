package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The small lifted plate role shared by selection and rating editors.
 * This borrows the contextual menu's rounded silhouette and high-contrast
 * opaque surface; it does not add a second full-screen overlay when selecting
 * multiple rows, because the user must keep interacting with those rows.
 */
internal fun nativeContextPlateColor(dark: Boolean): Color =
    if (dark) Color(0xFF242427) else Color(0xFFF4F4F6)

@Composable
internal fun NativeContextActionPlate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.shadow(12.dp, shape, clip = false),
        shape = shape,
        color = nativeContextPlateColor(dark),
        contentColor = if (dark) Color.White else Color.Black,
        border = BorderStroke(
            0.5.dp,
            (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.13f else 0.08f),
        ),
    ) { content() }
}
