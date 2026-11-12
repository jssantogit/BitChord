package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeReorderPlaylistActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reorderCloseUses40dpCircleAndInvokesCallback() {
        var closed = 0
        compose.setContent {
            MaterialTheme { ReorderCloseAction(onClose = { closed++ }) }
        }
        compose.onNodeWithTag("reorder-close-native")
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun reorderSaveRetains48dpHeightAndCanSubmit() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ReorderSaveAction(
                    label = "Salvar ordem",
                    enabled = true,
                    saving = false,
                    onClick = { saves++ },
                )
            }
        }
        compose.onNodeWithTag("reorder-save-native").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Salvar ordem").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun savingStateDisablesTheActionAndExposesSpinner() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ReorderSaveAction(
                    label = "Salvar ordem",
                    enabled = false,
                    saving = true,
                    onClick = { saves++ },
                )
            }
        }
        compose.onNodeWithTag("reorder-save-native").assertIsNotEnabled()
        compose.onNodeWithTag("reorder-save-loading", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
