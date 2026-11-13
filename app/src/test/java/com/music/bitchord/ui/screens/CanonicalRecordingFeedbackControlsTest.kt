package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.music.bitchord.ui.components.LocalCompactRatingStyle
import com.music.bitchord.ui.components.CompactRatingStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.components.ActionRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
/**
 * Robolectric dispatches Compose accessibility actions reliably for a Dialog
 * window, while raw touch injection into this separate window is unreliable.
 * These tests verify the editor's actual save/cancel/half-star callbacks and
 * state transitions, not physical pointer positioning. The latter remains a
 * device-smoke requirement and must not be claimed green from this suite.
 */
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
        compose.onNodeWithTag("native-rating-bottom-sheet").assertExists()
        compose.onNodeWithText("User rating").assertDoesNotExist()
        compose.onNodeWithContentDescription("4 stars").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(emptyList<Double>(), values)
            assertEquals(0, dismissals)
        }
        compose.onNodeWithText("Save rating").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(4.0), values) }
    }

    @Test fun ratingCompositionMatchesMinimalSolidBottomSheetConcept() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 4.5,
                    title = "Rate playlist",
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("native-rating-bottom-sheet").assertExists()
        compose.onNodeWithText("Rate playlist").assertDoesNotExist()
        compose.onNodeWithTag("rating-editor-sheet-handle").assertDoesNotExist()
        compose.onNodeWithTag("rating-editor-artwork-header").assertDoesNotExist()
        compose.onNodeWithTag("rating-editor-cancel-action").assertExists()
        compose.onNodeWithTag("rating-editor-save-action").assertExists()
        val cancelBounds = compose.onNodeWithTag("rating-editor-cancel-action")
            .getUnclippedBoundsInRoot()
        val saveBounds = compose.onNodeWithTag("rating-editor-save-action")
            .getUnclippedBoundsInRoot()
        assertEquals(cancelBounds.right - cancelBounds.left, saveBounds.right - saveBounds.left)
    }

    @Test fun ratingSheetBackgroundFollowsSolidPlayerThemeWithoutFrosting() {
        assertEquals(Color.Black, ratingSheetSolidColor(true))
        assertEquals(Color.White, ratingSheetSolidColor(false))
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
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
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
        compose.onNodeWithText("Save rating").performSemanticsAction(SemanticsActions.OnClick)
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
        compose.onNodeWithContentDescription("2.5 stars").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
        compose.onNodeWithContentDescription("2.5 stars selected").assertIsDisplayed()
        compose.onNodeWithText("Save rating").performSemanticsAction(SemanticsActions.OnClick)
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

    @Test fun editorRendersPlayerVectorStatesIncludingExactHalf() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 2.5,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("rating-editor-star-0-full").assertExists().assertWidthIsEqualTo(56.dp)
        compose.onNodeWithTag("rating-editor-star-1-full").assertExists()
        compose.onNodeWithTag("rating-editor-star-2-half").assertExists()
        compose.onNodeWithTag("rating-editor-star-3-empty").assertExists()
        compose.onNodeWithTag("rating-editor-star-4-empty").assertExists()
        compose.onNodeWithContentDescription("4 stars").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("rating-editor-star-3-full").assertExists()
        compose.onNodeWithTag("rating-editor-star-4-empty").assertExists()
    }

    @Test fun loadingDisablesRatingChangesAndSaveButKeepsSelectionVisible() {
        val values = mutableListOf<Double>()
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 3.5,
                    onSave = { values += it },
                    onDismiss = {},
                    saving = true,
                )
            }
        }
        compose.onNodeWithTag("rating-editor-star-3-half").assertExists()
        compose.onNodeWithTag("rating-editor-star-4-empty").assertExists()
        compose.onNodeWithContentDescription("4 stars").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<Double>(), values) }
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

    @Test fun lateRoomEmissionMustNotEraseAUserSelectionBeforeSave() {
        val incoming = androidx.compose.runtime.mutableStateOf<Double?>(null)
        val values = mutableListOf<Double>()
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = incoming.value,
                    onSave = { values += it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("4 stars").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("4.0 stars selected").assertIsDisplayed()
        compose.runOnIdle { incoming.value = 3.0 }
        compose.onNodeWithContentDescription("4.0 stars selected").assertIsDisplayed()
        compose.onNodeWithText("Save rating").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(4.0), values) }
    }

    @Test fun changedRatingDiscardsImmediatelyOnCancellationWithoutWriting() {
        val values = mutableListOf<Double>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 3.0,
                    onSave = { values += it },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithContentDescription("4 stars")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Cancel")
            .performSemanticsAction(SemanticsActions.OnClick)
        // No second Alert, no duplicate editor UI, and no database write.
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(emptyList<Double>(), values)
        }
    }

    @Test fun numericRatingStylePlacesOneNumberOnlyOnTerminalStar() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCompactRatingStyle provides CompactRatingStyle.NUMERIC) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 2.0,
                        onSave = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onAllNodesWithTag("compact-rating-numeric", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithTag("rating-editor-star-1-full").assertExists()
        compose.onNodeWithTag("rating-editor-star-2-empty").assertExists()
    }

    @Test fun ratingActionUsesThePlainStandaloneOutlineVector() {
        assertEquals(
            com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
            ratingActionIcon(),
        )
    }

    @Test fun latestRoomScoreIsPreselectedWhenTheUserHasNotEditedAnything() {
        val incoming = androidx.compose.runtime.mutableStateOf<Double?>(null)
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = incoming.value,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.runOnIdle { incoming.value = 3.5 }
        compose.onNodeWithContentDescription("3.5 stars selected").assertIsDisplayed()
    }

}
