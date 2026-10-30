package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Existing library row and input controls must work when reused by playlists. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistLibraryVisualReuseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedLibraryRowKeepsArtworkSlotAndOpensSelectedPlaylist() {
        var opened = 0
        compose.setContent {
            LibraryRow(
                title = "Playlist normal",
                subtitle = "12 músicas",
                imageUrl = null,
                artwork = { Box(Modifier.size(52.dp)) },
                onClick = { opened++ },
            )
        }
        compose.onNodeWithText("Playlist normal").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText("12 músicas").assertExists()
    }

    @Test fun conditionCategoryRowsReuseSettingsInteractionAndNavigate() {
        var opened = ""
        compose.setContent {
            PlaylistWizardNavigationList(listOf(
                PlaylistWizardNavOption(
                    title = "Metadados",
                    description = "Campos do catálogo",
                    onClick = { opened = "metadata" },
                ),
                PlaylistWizardNavOption(
                    title = "Histórico",
                    description = "Estatísticas de reprodução",
                    onClick = { opened = "history" },
                ),
            ))
        }
        compose.onNodeWithText("Histórico").performClick()
        compose.runOnIdle { assertEquals("history", opened) }
    }

    @Test fun sourceSelectionRetainsCallbackAndSelectedSemantics() {
        var changed = 0
        compose.setContent {
            PlaylistWizardSelectionRow(
                label = "Música local",
                selected = true,
                onClick = { changed++ },
            )
        }
        compose.onNodeWithText("Música local").performClick()
        compose.runOnIdle { assertEquals(1, changed) }
        compose.onNodeWithContentDescription("Selecionado").assertExists()
    }

    @Test fun catalogBrowseTabsUseExistingSegmentedControl() {
        var selected = 0
        compose.setContent {
            SegmentedControl(
                options = listOf("Músicas", "Álbuns", "Artistas"),
                selectedIndex = selected,
                onSelect = { selected = it },
            )
        }
        compose.onNodeWithText("Álbuns").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun wizardTextAndSearchFieldsPreserveUserInput() {
        var name by mutableStateOf("")
        var search by mutableStateOf("")
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                PlaylistWizardTextEntry(
                    value = name,
                    onValueChange = { name = it },
                    label = "Nome da playlist",
                )
                PlaylistWizardSearchEntry(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "Buscar músicas",
                )
            }
        }
        compose.onNodeWithContentDescription("Nome da playlist").performTextInput("Favoritas")
        compose.onNodeWithContentDescription("Buscar músicas").performTextInput("Hurt")
        compose.runOnIdle {
            assertEquals("Favoritas", name)
            assertEquals("Hurt", search)
        }
    }
}
