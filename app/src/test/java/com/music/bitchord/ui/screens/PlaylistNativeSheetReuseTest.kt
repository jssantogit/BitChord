package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        var queued = 0
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                CanonicalLibraryBatchActionBar(
                    selectedEntityCount = 2,
                    summary = "23 faixas · 2 itens",
                    onClear = {},
                    onAddToPlaylist = { added++ },
                    onPlayNext = {},
                    onAddToQueue = { queued++ },
                    onRateSelection = {},
                )
            }
        }
        compose.onNodeWithTag("native-settings-group-surface").assertExists()
        compose.onNodeWithContentDescription("Adicionar seleção à playlist").performClick()
        compose.onNodeWithContentDescription("Adicionar seleção à fila").performClick()
        compose.runOnIdle {
            assertEquals(1, added)
            assertEquals(1, queued)
        }
        compose.onNodeWithContentDescription("Recolher ações").performClick()
        compose.onNodeWithContentDescription("Adicionar seleção à playlist").assertDoesNotExist()
    }

    @Test fun nativePlaylistSheetKeepsWizardHeaderAndSettingsRowBehavior() {
        var selected by mutableStateOf(0)
        compose.setContent {
            PlaylistWizardSheetChrome(title = "Adicionar à playlist", onClose = {}) {
                PlaylistWizardSelectionRow(
                    label = "2000s", selected = selected > 0,
                    onClick = { selected++ },
                )
            }
        }
        compose.waitForIdle() // ModalBottomSheet must finish its entrance animation.
        compose.onNodeWithText("Adicionar à playlist").assertExists()
        compose.onNodeWithTag("native-settings-group-surface").assertExists()
        // A Robolectric bottom-sheet scrim can intercept synthetic pointer
        // coordinates even when the target is exposed. The accessible OnClick
        // exercises the same native SettingsRow handler deterministically.
        compose.onNodeWithTag("native-playlist-selection-row")
            .assertHasClickAction()
            .assertIsNotSelected()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("native-playlist-selection-row").assertIsSelected()
    }

    @Test fun nativeSelectionRowRespondsToRealPointerClicksOutsideModalScrim() {
        var selected by mutableStateOf(false)
        compose.setContent {
            PlaylistWizardSelectionRow(
                label = "Faixas locais",
                selected = selected,
                onClick = { selected = !selected },
            )
        }
        compose.onNodeWithTag("native-playlist-selection-row")
            .assertIsNotSelected()
            .performClick()
        compose.runOnIdle { assertEquals(true, selected) }
        compose.onNodeWithTag("native-playlist-selection-row").assertIsSelected()
    }
}
