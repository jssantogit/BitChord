package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertWidthIsAtMost
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    @Test fun sharedSettingsGroupSelectionDockFitsPhoneAndCollapsesNativeActions() {
        var added = 0
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                CanonicalLibraryBatchActionBar(
                    selectedEntityCount = 2,
                    summary = "23 faixas · 2 itens",
                    onClear = {},
                    onAddToPlaylist = { added++ },
                    onPlayNext = {},
                    onRateSelection = {},
                )
            }
        }
        compose.onNodeWithTag("native-settings-group-surface").assertExists()
        compose.onNodeWithContentDescription("Adicionar seleção à playlist").performClick()
        compose.runOnIdle { assertEquals(1, added) }
        compose.onNodeWithContentDescription("Recolher ações").performClick()
        compose.onNodeWithContentDescription("Adicionar seleção à playlist").assertDoesNotExist()
    }

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
