package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import com.music.bitchord.NativeCollectionRatingToolbarAction
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.ui.components.LocalCompactRatingStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Centered rating modal preserves player and album navigation on dismissal. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeFrostedRatingInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissingAnchoredRatingRestoresAlbumToolbarInteractions() {
        val editing = mutableStateOf(true)
        var otherClicks = 0
        compose.setContent {
            MaterialTheme {
                TextButton(onClick = { otherClicks++ }) { Text("Other album action") }
                if (editing.value) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 4.0,
                        onSave = {},
                        onDismiss = { editing.value = false },
                        anchorBounds = IntRect(24, 96, 72, 144),
                    )
                }
            }
        }
        compose.onNodeWithTag("native-rating-modal").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("native-rating-modal").assertDoesNotExist()
        compose.onNodeWithText("Other album action").performClick()
        compose.runOnIdle { assertEquals(1, otherClicks) }
    }

    @Test fun albumToolbarRatingLauncherOpensAndReleasesSiblingActions() {
        val editing = mutableStateOf(false)
        var sortClicks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    NativeCollectionRatingToolbarAction(
                        rating = 4.0,
                        subject = "álbum",
                        onClick = { editing.value = true },
                    )
                    TextButton(onClick = { sortClicks++ }) { Text("Sort album") }
                }
                if (editing.value) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 4.0,
                        onSave = {},
                        onDismiss = { editing.value = false },
                        anchorBounds = IntRect(24, 96, 72, 144),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Avaliar álbum", substring = true).performClick()
        compose.onNodeWithTag("native-rating-modal").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("native-rating-modal").assertDoesNotExist()
        compose.onNodeWithText("Sort album").performClick()
        compose.runOnIdle { assertEquals(1, sortClicks) }
    }

    @Test fun playerRatingEditorUsesCenteredModalWithoutSelectedStarOverlay() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 4.0,
                    onSave = {},
                    onDismiss = {},
                    anchorBounds = IntRect(24, 96, 64, 136),
                    highlightPlayerLauncher = true,
                )
            }
        }
        compose.onNodeWithTag("native-rating-modal").assertExists()
        compose.onNodeWithTag("player-rating-launcher-highlight").assertDoesNotExist()
        compose.onNodeWithTag("player-overflow-highlight").assertDoesNotExist()
    }

    @Test fun numericStyleUsesExistingV3DigitVectorsInTheEditor() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCompactRatingStyle provides CompactRatingStyle.NUMERIC) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 4.0,
                        onSave = {},
                        onDismiss = {},
                        anchorBounds = IntRect(24, 96, 72, 144),
                    )
                }
            }
        }
        val numericStars = compose.onAllNodesWithTag("compact-rating-numeric", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, numericStars.size)
    }
}
