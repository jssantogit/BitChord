package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
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

/** The same primary action is used for the picker, create and rename flows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativePlaylistPrimaryActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosePlaylistsPreservesExactlyOneCommitAnd50DpTarget() {
        var commits = 0
        compose.setContent {
            MaterialTheme {
                NativeSettingsPrimaryAction(
                    label = "Adicionar a 2 playlists",
                    onClick = { commits++ },
                    tag = "playlist-picker-confirm-action",
                )
            }
        }
        compose.onNodeWithTag("playlist-picker-confirm-action")
            .assertHeightIsEqualTo(50.dp)
        compose.onNodeWithText("Adicionar a 2 playlists").performClick()
        compose.runOnIdle { assertEquals(1, commits) }
    }

    @Test fun saveInProgressRetainsButtonHeightAndNonClickableSpinner() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                NativeSettingsPrimaryAction(
                    label = "Criar playlist",
                    onClick = { saves++ },
                    enabled = false,
                    loading = true,
                    tag = "playlist-picker-create-action",
                    loadingTag = "playlist-editor-saving",
                )
            }
        }
        compose.onNodeWithTag("playlist-picker-create-action")
            .assertHeightIsEqualTo(50.dp)
            .assertIsNotEnabled()
        compose.onNodeWithTag("playlist-editor-saving", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Criar playlist").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun renameKeepsSaveDisabledWhenNameIsUnchanged() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                NativeSettingsPrimaryAction(
                    label = "Salvar nome",
                    onClick = { saves++ },
                    enabled = false,
                    tag = "playlist-picker-rename-action",
                )
            }
        }
        compose.onNodeWithTag("playlist-picker-rename-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
