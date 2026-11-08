package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small original vectors for the two glyphs absent from the existing tab set.
 * The other three tab glyphs remain the already-vendored BitChordIcons.
 */
internal object AppleMusicNavIcons {
    private val ink = SolidColor(Color.Black) // Icon's tint supplies the actual color.

    val Radio: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_music_future_radio",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = ink) {
                moveTo(14.2f, 12f)
                curveTo(14.2f, 13.22f, 13.22f, 14.2f, 12f, 14.2f)
                curveTo(10.78f, 14.2f, 9.8f, 13.22f, 9.8f, 12f)
                curveTo(9.8f, 10.78f, 10.78f, 9.8f, 12f, 9.8f)
                curveTo(13.22f, 9.8f, 14.2f, 10.78f, 14.2f, 12f)
                close()
            }
            path(stroke = ink, strokeLineWidth = 1.55f, strokeLineCap = StrokeCap.Round) {
                moveTo(8.8f, 8f)
                curveTo(6.55f, 10.1f, 6.55f, 13.9f, 8.8f, 16f)
                moveTo(15.2f, 8f)
                curveTo(17.45f, 10.1f, 17.45f, 13.9f, 15.2f, 16f)
                moveTo(6.35f, 5.5f)
                curveTo(2.4f, 9.1f, 2.4f, 14.9f, 6.35f, 18.5f)
                moveTo(17.65f, 5.5f)
                curveTo(21.6f, 9.1f, 21.6f, 14.9f, 17.65f, 18.5f)
                moveTo(4.25f, 3.25f)
                curveTo(-1.0f, 8.2f, -1.0f, 15.8f, 4.25f, 20.75f)
                moveTo(19.75f, 3.25f)
                curveTo(25.0f, 8.2f, 25.0f, 15.8f, 19.75f, 20.75f)
            }
        }.build()
    }

    /** Stacked media cards with a negative-space music note, like the reference. */
    val Library: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_music_tab_library",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                moveTo(7.25f, 3.2f); lineTo(16.75f, 3.2f)
                moveTo(6.2f, 5.6f); lineTo(17.8f, 5.6f)
            }
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                moveTo(7f, 7.6f)
                lineTo(17f, 7.6f)
                quadTo(20f, 7.6f, 20f, 10.6f)
                lineTo(20f, 19f)
                quadTo(20f, 21.6f, 17.4f, 21.6f)
                lineTo(6.6f, 21.6f)
                quadTo(4f, 21.6f, 4f, 19f)
                lineTo(4f, 10.6f)
                quadTo(4f, 7.6f, 7f, 7.6f)
                close()

                // A connected eighth-note-shaped cutout (even-odd winding).
                moveTo(11.1f, 12.3f)
                lineTo(16.65f, 10.9f)
                lineTo(16.65f, 16.65f)
                quadTo(16.65f, 18.6f, 14.25f, 19f)
                quadTo(12.3f, 19.2f, 12.3f, 17.8f)
                quadTo(12.3f, 16.5f, 14.05f, 16.15f)
                lineTo(15.35f, 15.9f)
                lineTo(15.35f, 13f)
                lineTo(12.4f, 13.8f)
                lineTo(12.4f, 17.3f)
                quadTo(12.4f, 19.2f, 10.05f, 19.45f)
                quadTo(8.05f, 19.6f, 8.05f, 18.2f)
                quadTo(8.05f, 16.9f, 9.75f, 16.5f)
                lineTo(11.1f, 16.15f)
                close()
            }
        }.build()
    }
}
