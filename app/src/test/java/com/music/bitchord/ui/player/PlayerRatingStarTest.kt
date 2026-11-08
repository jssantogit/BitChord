package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.RatingDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Regression: rating is accessible without entering a three-dot menu or first rating. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlayerRatingStarTest {
    @get:Rule val compose = createComposeRule()
    private val song = Song(videoId = "content://media/track", title = "Track", artist = "Artist", thumbnailUrl = null)

    @Test fun startsUnmarkedForNoRatingAndForRemoteOnlyScore() {
        assertFalse(playerRatingHasNativeScore(null))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(remoteValue = 5)))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(localValue = Double.NaN)))
        assertTrue(playerRatingHasNativeScore(RatingDisplay(localValue = 3.5)))
        assertTrue(playerRatingHasNativeScore(RatingDisplay(localValue = 2.0, remoteValue = 5)))
    }

    @Test fun starOpensEditorForFirstTimeAndRemoteOnlyRatings() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanonicalRatingDisplay provides CanonicalRatingDisplaySnapshot(
                        bySongKey = mapOf(song.videoId to RatingDisplay(remoteValue = 4))
                    )
                ) {
                    PlayerRatingStar(song = song, onClick = { opens++ })
                }
            }
        }
        compose.onNodeWithTag("now-playing-rating-star").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun starOpensExistingNativeRatingEditor() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanonicalRatingDisplay provides CanonicalRatingDisplaySnapshot(
                        bySongKey = mapOf(song.videoId to RatingDisplay(localValue = 4.5))
                    )
                ) {
                    PlayerRatingStar(song = song, onClick = { opens++ })
                }
            }
        }
        compose.onNodeWithTag("now-playing-rating-star").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
