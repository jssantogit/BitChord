package com.music.bitchord.ui.player

import com.music.bitchord.ui.components.RatingDisplay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rating state is native, not inferred from an account's numeric or starred evidence. */
class PlayerRatingStarTest {
    @Test fun unratedAndRemoteOnlyRemainUnmarked() {
        assertFalse(playerRatingHasNativeScore(null))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(remoteValue = 5)))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(localValue = Double.NaN)))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(localValue = 0.0)))
        assertFalse(playerRatingHasNativeScore(RatingDisplay(localValue = 5.5)))
    }

    @Test fun validNativeScoreSelectsFilledPlayerStarRegardlessOfRemoteScore() {
        assertTrue(playerRatingHasNativeScore(RatingDisplay(localValue = 3.5)))
        assertTrue(playerRatingHasNativeScore(RatingDisplay(localValue = 2.0, remoteValue = 5)))
        assertTrue(playerRatingHasNativeScore(RatingDisplay(localValue = 5.0)))
    }
}
