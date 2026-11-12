package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Compact, secondary and footer controls do not inherit Material3 button layout. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistWizardNativeActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun neutralSecondaryActionHas48dpTargetAndPreservesClick() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardSecondaryAction(label = "Avançar", onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-secondary-button")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Avançar").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun compactTextActionUsesNativeNeutralControl() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(label = "Organizar", onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-native-compact-action")
            .assertHasClickAction()
        compose.onNodeWithText("Organizar").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun disabledCompactTextActionCannotActivate() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(label = "Organizar", enabled = false, onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-native-compact-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
