package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
