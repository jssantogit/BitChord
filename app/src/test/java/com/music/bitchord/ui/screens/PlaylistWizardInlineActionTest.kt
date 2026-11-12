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

/** Inline playlist editor links are distinct from generic Material3 text buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistWizardInlineActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun classicEditorLinkKeepsARealTapTargetAndCallback() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardInlineAction(label = "Editor clássico", onClick = { opened++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-inline-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Editor clássico").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun destructiveActionCanBeActivatedWithoutChangingItsCallback() {
        var removed = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardInlineAction(
                    label = "Excluir playlist",
                    onClick = { removed++ },
                    destructive = true,
                )
            }
        }
        compose.onNodeWithTag("playlist-wizard-inline-action").assertHasClickAction()
        compose.onNodeWithText("Excluir playlist").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun disabledArtworkLinkCannotRemoveTheCover() {
        var removals = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardInlineAction(
                    label = "Remover capa",
                    onClick = { removals++ },
                    enabled = false,
                    subdued = true,
                )
            }
        }
        compose.onNodeWithTag("playlist-wizard-inline-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, removals) }
    }
}
