package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalFolderSelectionPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelSelectionKeeps44DpTargetAndClearsOnlyOnClick() {
        var clears = 0
        compose.setContent {
            MaterialTheme {
                LocalFolderInlineAction(label = "Cancelar", onClick = { clears++ })
            }
        }
        compose.onNodeWithTag("local-folder-inline-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test fun unavailableDestructiveDeleteCannotFire() {
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                LocalFolderInlineAction(
                    label = "Excluir",
                    icon = Icons.Rounded.Delete,
                    destructive = true,
                    enabled = false,
                    onClick = { deletes++ },
                )
            }
        }
        compose.onNodeWithTag("local-folder-inline-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, deletes) }
    }
}
