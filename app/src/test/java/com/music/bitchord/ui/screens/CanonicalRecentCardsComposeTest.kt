package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.music.bitchord.playback.CanonicalCollectionIntent
import org.junit.Assert.assertEquals
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The canonical recent card really follows the same fixed-width shelf geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRecentCardsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recentLocalCardLongPressOpensItsRealCanonicalActions() {
        var result: Pair<String, CanonicalCollectionIntent>? = null
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsShelf(
                    recent = listOf(V2DisplayRow(id = "recording-id", title = "Recent", artist = "Artist")),
                    onPlayTrack = {},
                    onTrackAction = { id, action -> result = id to action },
                )
            }
        }
        compose.onNodeWithTag("canonical-recent-card", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("Tocar a seguir").performClick()
        compose.runOnIdle {
            assertEquals("recording-id" to CanonicalCollectionIntent.ADD_NEXT, result)
        }
    }

    @Test fun recentRecordingCardSharesShelfWidthInsteadOfLegacy142Dp() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsShelf(
                    recent = listOf(V2DisplayRow(id = "one", title = "Test Track", artist = "Test Artist")),
                    onPlayTrack = {},
                )
            }
        }
        compose.onNodeWithTag("canonical-recent-card", useUnmergedTree = true)
            .assertWidthIsEqualTo(SHELF_CARD_WIDTH)
    }
}
