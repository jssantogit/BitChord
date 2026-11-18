package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
        // Use the same accessible star/button actions as the existing Compose
        // regression, rather than tapping a decorative child by coordinates.
        compose.onNodeWithContentDescription("4 stars")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Cancel")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, saves)
        }
    }
}
