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

    @Test fun primaryWizardActionHasNative50dpTargetAndCallsSave() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardPrimaryAction(label = "Salvar", onClick = { saves++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(50.dp)
        compose.onNodeWithText("Salvar").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun classicEditorPrimarySaveFitsBesidePreviewAndReset() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier.width(340.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistWizardPrimaryAction(
                        label = "Salvar",
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                        onClick = { saves++ },
                    )
                    PlaylistWizardInlineAction(label = "Prévia", onClick = {})
                    PlaylistWizardInlineAction(label = "Limpar", subdued = true, onClick = {})
                }
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action")
            .assertHeightIsAtLeast(50.dp)
        compose.onNodeWithText("Prévia").assertExists()
        compose.onNodeWithText("Limpar").assertExists()
        compose.onNodeWithText("Salvar").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun disabledPrimaryWizardActionCannotCommit() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardPrimaryAction(label = "Salvar", enabled = false, onClick = { saves++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

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
