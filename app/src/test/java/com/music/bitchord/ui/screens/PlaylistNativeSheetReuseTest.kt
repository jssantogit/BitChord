package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The normal-playlist destination picker and wizard choose from the SAME native sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistNativeSheetReuseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativePlaylistSheetKeepsWizardHeaderAndSettingsRowBehavior() {
        var selected = 0
        compose.setContent {
            PlaylistWizardSheetChrome(title = "Adicionar à playlist", onClose = {}) {
                PlaylistWizardSelectionRow(
                    label = "2000s", selected = selected > 0,
                    onClick = { selected++ },
                )
            }
        }
        compose.onNodeWithText("Adicionar à playlist").assertExists()
        compose.onNodeWithTag("native-settings-group-surface").assertExists()
        compose.onNodeWithText("2000s").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }
}
