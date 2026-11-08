package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.apple_queue_favorite
import com.music.bitchord.sharedui.resources.apple_queue_favorited
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import org.jetbrains.compose.resources.painterResource
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
    val isRated = playerRatingHasNativeScore(LocalCanonicalRatingDisplay.current.forSong(song))
    Box(
        modifier = modifier
            .size(40.dp)
            .testTag("now-playing-rating-star")
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (isRated) Res.drawable.apple_queue_favorited
                else Res.drawable.apple_queue_favorite
            ),
            contentDescription = stringResource(Res.string.recording_feedback_edit),
            tint = Color.White,
            modifier = Modifier.size(34.dp),
        )
    }
}
