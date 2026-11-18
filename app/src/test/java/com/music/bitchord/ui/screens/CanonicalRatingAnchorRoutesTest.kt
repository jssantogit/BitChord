package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** All star launchers now open the same centered, neutral rating modal. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRatingAnchorRoutesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitRatingLauncherUsesCenteredModalRegardlessOfItsBounds() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 4.0,
                    onSave = {},
                    onDismiss = {},
                    anchorBounds = IntRect(20, 60, 64, 104),
                )
            }
        }
        compose.onNodeWithTag("native-rating-modal").assertExists()
        compose.onNodeWithTag("native-rating-modal-content").assertExists()
        compose.onNodeWithTag("native-rating-modal-title").assertExists()
        compose.onNodeWithText("Your rating").assertExists()
        compose.onNodeWithTag("native-rating-anchored-card").assertDoesNotExist()
        compose.onNodeWithTag("native-rating-bottom-sheet").assertDoesNotExist()
    }
    @Test fun nonAnchoredRatingAlsoUsesTheSameCenteredModal() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(rating = 3.5, onSave = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("native-rating-modal").assertExists()
        assertEquals(320, ratingEditorFloatingMaxWidthDp())
    }

    @Test fun starGlyphsKeepTheirOriginalSizeOnNormalScreensAndShrinkForMultiWindow() {
        assertEquals(32, ratingEditorModalGlyphSizeDp(400))
        assertEquals(32, ratingEditorModalGlyphSizeDp(320))
        assertEquals(32, ratingEditorModalGlyphSizeDp(240))
        assertEquals(28, ratingEditorModalGlyphSizeDp(200))
    }

    @Test fun centeredModalRemainsWithinNarrowMultiWindowLayouts() {
        assertEquals(320, ratingEditorModalWidthDp(500))
        assertEquals(288, ratingEditorModalWidthDp(320))
        assertEquals(208, ratingEditorModalWidthDp(240))
        assertEquals(168, ratingEditorModalWidthDp(200))
    }
}
