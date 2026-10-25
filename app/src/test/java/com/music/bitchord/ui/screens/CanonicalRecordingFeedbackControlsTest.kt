package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.music.bitchord.ui.components.ActionRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRecordingFeedbackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingAndSelectingStarsNeverWritesUntilSave() {
        val values = mutableListOf<Double>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = null,
                    onSave = { values += it },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithText("User rating").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 stars").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Double>(), values)
            assertEquals(0, dismissals)
        }
        compose.onNodeWithText("Save rating").performClick()
        compose.runOnIdle { assertEquals(listOf(4.0), values) }
    }

    @Test fun cancelCannotWriteAndDialogNeverExposesClear() {
        val values = mutableListOf<Double>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 4.5,
                    onSave = { values += it },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithText("Clear rating").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(emptyList<Double>(), values)
        }
    }

    @Test fun halfStarIsSelectableAndExistingLocalValueIsPreselected() {
        val values = mutableListOf<Double>()
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 2.5,
                    onSave = { values += it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("2.5 stars selected").assertIsDisplayed()
        compose.onNodeWithText("Save rating").performClick()
        compose.runOnIdle { assertEquals(listOf(2.5), values) }
    }

    @Test fun tappingLeftHalfOfThirdStarAndSavingWritesTwoPointFive() {
        val values = mutableListOf<Double>()
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = null,
                    onSave = { values += it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("2.5 stars").performClick()
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
        compose.onNodeWithContentDescription("2.5 stars selected").assertIsDisplayed()
        compose.onNodeWithText("Save rating").performClick()
        compose.runOnIdle { assertEquals(listOf(2.5), values) }
    }

    @Test fun modalDisplaysOnlyCenteredStarsNotRedundantNumbers() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 3.5,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("3.5 stars selected").assertIsDisplayed()
        compose.onNodeWithText("3.5 / 5").assertDoesNotExist()
        compose.onNodeWithText("— / 5").assertDoesNotExist()
        compose.onNodeWithText("Clear rating").assertDoesNotExist()
    }

    @Test fun editRatingUsesSameMenuTextAlignmentAsOtherActions() {
        compose.setContent {
            MaterialTheme {
                Column {
                    CanonicalRecordingFeedbackAction(onClick = {})
                    ActionRow(
                        icon = Icons.Rounded.Radio,
                        label = "Start radio",
                        onClick = {},
                    )
                }
            }
        }
        val edit = compose.onNodeWithText("Edit rating")
        val radio = compose.onNodeWithText("Start radio")
        edit.assertIsDisplayed()
        radio.assertIsDisplayed()
        assertEquals(
            radio.getUnclippedBoundsInRoot().left,
            edit.getUnclippedBoundsInRoot().left,
        )
    }
}
