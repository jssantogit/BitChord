package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.PlaylistEditorIcons
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
class PartyPrimaryActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createRoomHas52DpNativeActionAndKeepsCallback() {
        var creates = 0
        compose.setContent {
            MaterialTheme {
                PartyPrimaryAction(label = "Criar sala", onClick = { creates++ })
            }
        }
        compose.onNodeWithTag("party-primary-action")
            .assertHasClickAction()
            .assertHeightIsEqualTo(52.dp)
        compose.onNodeWithText("Criar sala").performClick()
        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test fun busyJoinKeepsLabelButCannotCommitAgain() {
        var joins = 0
        compose.setContent {
            MaterialTheme {
                PartyPrimaryAction(
                    label = "Entrando...",
                    enabled = false,
                    onClick = { joins++ },
                )
            }
        }
        compose.onNodeWithTag("party-primary-action").assertIsNotEnabled()
        compose.onNodeWithText("Entrando...").assertExists()
        compose.runOnIdle { assertEquals(0, joins) }
    }

    @Test fun sharingKeepsLinkIconAndShareCallback() {
        var shares = 0
        compose.setContent {
            MaterialTheme {
                PartyPrimaryAction(
                    label = "Compartilhar link",
                    icon = PlaylistEditorIcons.Link,
                    onClick = { shares++ },
                )
            }
        }
        compose.onNodeWithTag("party-primary-action").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithText("Compartilhar link").performClick()
        compose.runOnIdle { assertEquals(1, shares) }
    }
}
