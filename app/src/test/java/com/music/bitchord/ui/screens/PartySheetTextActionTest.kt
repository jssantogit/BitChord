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

/** Native text actions in the Listen Together join and invitation sheets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PartySheetTextActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun joinExistingActionKeepsFullWidth48dpTargetAndCallback() {
        var joins = 0
        compose.setContent {
            MaterialTheme {
                PartySheetTextAction(label = "Entrar em uma sala", onClick = { joins++ })
            }
        }
        compose.onNodeWithTag("party-sheet-text-action")
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Entrar em uma sala").performClick()
        compose.runOnIdle { assertEquals(1, joins) }
    }

    @Test fun disabledActionDoesNotJoin() {
        var joins = 0
        compose.setContent {
            MaterialTheme {
                PartySheetTextAction(
                    label = "Entrar",
                    enabled = false,
                    onClick = { joins++ },
                )
            }
        }
        compose.onNodeWithTag("party-sheet-text-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, joins) }
    }

    @Test fun inviteCodeActionRetainsCopyCallbackAndCustomLetterSpacing() {
        var copies = 0
        compose.setContent {
            MaterialTheme {
                PartySheetTextAction(
                    label = "ABC123",
                    compact = true,
                    codeStyle = true,
                    onClick = { copies++ },
                )
            }
        }
        compose.onNodeWithTag("party-sheet-text-action").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("ABC123").performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }
}
