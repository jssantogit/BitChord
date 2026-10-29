package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Native scoring must be possible before the first local rating and without
 * contacting Navidrome, once a canonical MediaAsset is already in the catalog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalSongMenuRatingOfflineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previouslyUnratedLocalMediaAssetOffersStarEditorWithoutNetwork() {
        val uri = "content://media/external/audio/432"
        val song = Song(
            videoId = uri, title = "First local score", artist = "Offline",
            thumbnailUrl = null, localUri = uri,
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCanonicalRatingDisplay provides CanonicalRatingDisplaySnapshot(
                        recordingIdBySongKey = mapOf(uri to "canonical-recording-432"),
                    ),
                ) {
                    CanonicalSongMenuRating(song)
                }
            }
        }
        compose.onNodeWithText("Edit rating").assertIsDisplayed()
    }
}
