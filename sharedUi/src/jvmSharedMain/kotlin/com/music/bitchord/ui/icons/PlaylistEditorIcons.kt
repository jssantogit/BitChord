package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.AppleQueueVectors

/**
 * Original BitChord outline glyphs for both playlist editors.
 *
 * Reuse the shared native icon family first; only the missing semantic
 * controls are drawn here. The new-surface recipes are composition guidance,
 * not proof that Apple Music uses these exact glyphs.
 */
object PlaylistEditorIcons {
    val Music = BitChordIcons.MusicNote
    val Library = BitChordIcons.Library
    val Add = BitChordIcons.Plus
    val Check = BitChordIcons.Check
    val History = BitChordIcons.Clock
    val Ratings = AppleQueueVectors.Favorite
    /** Menus use the plain standalone star, never the circular player variant. */
    val RatingPlain = AppleQueueVectors.RatingStarOutline

    private fun outlined(
        name: String,
        autoMirror: Boolean = false,
        draw: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f, autoMirror = autoMirror,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()

    val Back by lazy { outlined("playlist_back", autoMirror = true) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 5.5f); lineTo(4.5f, 12f); lineTo(11f, 18.5f)
    } }
    val Close by lazy { outlined("playlist_close") {
        moveTo(5.5f, 5.5f); lineTo(18.5f, 18.5f)
        moveTo(18.5f, 5.5f); lineTo(5.5f, 18.5f)
    } }
    val Filters by lazy { outlined("playlist_filters") {
        moveTo(4f, 6f); lineTo(10f, 6f)
        moveTo(14f, 6f); lineTo(20f, 6f)
        moveTo(12f, 4f); lineTo(12f, 8f)
        moveTo(4f, 12f); lineTo(6f, 12f)
        moveTo(10f, 12f); lineTo(20f, 12f)
        moveTo(8f, 10f); lineTo(8f, 14f)
        moveTo(4f, 18f); lineTo(14f, 18f)
        moveTo(18f, 18f); lineTo(20f, 18f)
        moveTo(16f, 16f); lineTo(16f, 20f)
    } }
    val Edit by lazy { outlined("playlist_edit") {
        moveTo(4f, 20f); lineTo(9f, 19f); lineTo(19.5f, 8.5f)
        curveTo(21f, 7f, 17f, 3f, 15.5f, 4.5f)
        lineTo(5f, 15f); close()
        moveTo(13.5f, 6.5f); lineTo(17.5f, 10.5f)
    } }
    val Delete by lazy { outlined("playlist_delete") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(9f, 4.5f); lineTo(15f, 4.5f)
        moveTo(7f, 7f); lineTo(8f, 20f); lineTo(16f, 20f); lineTo(17f, 7f)
        moveTo(10f, 10f); lineTo(10.5f, 17f)
        moveTo(14f, 10f); lineTo(13.5f, 17f)
    } }
    val ChevronUp by lazy { outlined("playlist_chevron_up") {
        moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f)
    } }
    val ChevronDown by lazy { outlined("playlist_chevron_down") {
        moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f)
    } }
    val Calendar by lazy { outlined("playlist_calendar") {
        moveTo(5f, 5.5f); lineTo(19f, 5.5f); lineTo(19f, 20f)
        lineTo(5f, 20f); close()
        moveTo(5f, 10f); lineTo(19f, 10f)
        moveTo(8.5f, 3.5f); lineTo(8.5f, 7.5f)
        moveTo(15.5f, 3.5f); lineTo(15.5f, 7.5f)
    } }
    val Audio by lazy { outlined("playlist_audio") {
        moveTo(4f, 10f); lineTo(4f, 14f)
        moveTo(8f, 6f); lineTo(8f, 18f)
        moveTo(12f, 3.5f); lineTo(12f, 20.5f)
        moveTo(16f, 7f); lineTo(16f, 17f)
        moveTo(20f, 10f); lineTo(20f, 14f)
    } }
    val Artwork by lazy { outlined("playlist_artwork") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f)
        lineTo(4f, 19f); close()
        moveTo(6f, 16.5f); lineTo(10f, 12f); lineTo(13f, 15f)
        lineTo(15.5f, 12f); lineTo(18f, 15f)
        moveTo(15.5f, 8.5f); lineTo(16f, 8.5f)
    } }
    val Folder by lazy { outlined("playlist_folder") {
        moveTo(3.5f, 7f); lineTo(3.5f, 19f); lineTo(20.5f, 19f)
        lineTo(20.5f, 9f); lineTo(12f, 9f); lineTo(9.5f, 6f)
        lineTo(3.5f, 6f); close()
    } }
    val Lock by lazy { outlined("playlist_lock") {
        moveTo(6.5f, 10f); lineTo(17.5f, 10f); lineTo(17.5f, 20f)
        lineTo(6.5f, 20f); close()
        moveTo(8.5f, 10f); lineTo(8.5f, 7.5f)
        curveTo(8.5f, 2.5f, 15.5f, 2.5f, 15.5f, 7.5f)
        lineTo(15.5f, 10f)
    } }
    val Link by lazy { outlined("playlist_link") {
        moveTo(10f, 8f); lineTo(13f, 5f)
        curveTo(17.3f, 0.7f, 23f, 6.3f, 18.7f, 10.6f)
        lineTo(15.6f, 13.5f)
        moveTo(14f, 16f); lineTo(11f, 19f)
        curveTo(6.7f, 23.3f, 1f, 17.7f, 5.3f, 13.4f)
        lineTo(8.4f, 10.5f)
        moveTo(8.7f, 15.3f); lineTo(15.3f, 8.7f)
    } }
    val Globe by lazy { outlined("playlist_globe") {
        moveTo(21f, 12f)
        arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
        arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(6f, 7f, 6f, 17f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(18f, 7f, 18f, 17f, 12f, 21f)
    } }
    val Unselected by lazy { outlined("playlist_selection_off") {
        moveTo(21f, 12f)
        arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
        arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
    } }
    val Selected by lazy { outlined("playlist_selection_on") {
        moveTo(21f, 12f)
        arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
        arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
        moveTo(7.5f, 12f); lineTo(10.5f, 15f); lineTo(16.5f, 9f)
    } }
    // Toolbar draws one circle of its own; the glyph cannot include another.
    val More = AppleQueueVectors.MorePlain
}
