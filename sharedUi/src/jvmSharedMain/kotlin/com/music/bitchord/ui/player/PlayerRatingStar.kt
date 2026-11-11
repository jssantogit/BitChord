package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.CompactRatingGeometry
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import org.jetbrains.compose.resources.stringResource

/** Only a saved, first-party score marks the star; a remote rating alone never does. */
internal fun playerRatingHasNativeScore(rating: RatingDisplay?): Boolean =
    rating?.localValue?.let { it.isFinite() && it in 1.0..5.0 } == true

/**
 * One rating entry for the entire phone/tablet player, including an unrated
 * track. The click always delegates to the same verified-Recording editor as
 * the artist/catalog screens; it neither saves nor toggles a Favorite itself.
 */
@Composable
internal fun PlayerRatingStar(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rating = LocalCanonicalRatingDisplay.current.forSong(song)
    val localScore = rating?.localValue?.takeIf {
        playerRatingHasNativeScore(rating) && it * 2.0 == (it * 2.0).toInt().toDouble()
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .testTag("now-playing-rating-star")
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompactRatingIndicator(
            value = localScore,
            geometry = CompactRatingGeometry.CIRCULAR,
            size = 34.dp,
            showUnrated = true,
            neutralColor = Color.White,
        )
    }
}
