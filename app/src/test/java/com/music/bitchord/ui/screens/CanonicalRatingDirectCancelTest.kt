package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A changed rating is only a draft until Save. Cancel must never stack an alert. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRatingDirectCancelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelEditedExistingRatingClosesImmediatelyWithoutSavingOrConfirmation() {
        var dismissed = 0
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 3.0,
                    onSave = { saves++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithTag("rating-editor-star-4-empty", useUnmergedTree = true)
            .assertExists()
        // A change is pending, but Cancel discards it without a second alert.
        compose.onNodeWithTag("rating-editor-star-4-empty", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.onNodeWithTag("rating-editor-cancel-action").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, saves)
        }
    }
}
