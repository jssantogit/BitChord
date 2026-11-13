package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Compact, secondary and footer controls do not inherit Material3 button layout. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistWizardNativeActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryWizardActionHasNative50dpTargetAndCallsSave() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardPrimaryAction(label = "Salvar", onClick = { saves++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(50.dp)
        compose.onNodeWithText("Salvar").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun classicEditorPrimarySaveFitsBesidePreviewAndReset() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier.width(340.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistWizardPrimaryAction(
                        label = "Salvar",
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                        onClick = { saves++ },
                    )
                    PlaylistWizardInlineAction(label = "Prévia", onClick = {})
                    PlaylistWizardInlineAction(label = "Limpar", subdued = true, onClick = {})
                }
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action")
            .assertHeightIsAtLeast(50.dp)
        compose.onNodeWithText("Prévia").assertExists()
        compose.onNodeWithText("Limpar").assertExists()
        compose.onNodeWithText("Salvar").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun disabledPrimaryWizardActionCannotCommit() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardPrimaryAction(label = "Salvar", enabled = false, onClick = { saves++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-primary-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun neutralSecondaryActionHas48dpTargetAndPreservesClick() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardSecondaryAction(label = "Avançar", onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-secondary-button")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Avançar").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun compactTextActionUsesNativeNeutralControl() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(label = "Organizar", onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-native-compact-action")
            .assertHasClickAction()
        compose.onNodeWithText("Organizar").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun disabledCompactTextActionCannotActivate() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(label = "Organizar", enabled = false, onClick = { calls++ })
            }
        }
        compose.onNodeWithTag("playlist-wizard-native-compact-action").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun emphasizedNativeTextEntriesHaveVisibleIdleContrastAndAppleFocusState() {
        val background = Color(0xFF202021)
        val ink = Color.White
        val key = Color(0xFF0099FF)
        val normal = playlistWizardFieldVisuals(
            emphasized = false, focused = false, invalid = false,
            surface = background, onSurface = ink, outline = Color.Gray,
            key = key, error = Color.Red,
        )
        val prominent = playlistWizardFieldVisuals(
            emphasized = true, focused = false, invalid = false,
            surface = background, onSurface = ink, outline = Color.Gray,
            key = key, error = Color.Red,
        )
        val focused = playlistWizardFieldVisuals(
            emphasized = true, focused = true, invalid = false,
            surface = background, onSurface = ink, outline = Color.Gray,
            key = key, error = Color.Red,
        )
        assertTrue(prominent.fill != normal.fill)
        assertTrue(prominent.stroke != normal.stroke)
        assertTrue(prominent.label != normal.label)
        assertTrue(prominent.width > normal.width)
        assertEquals(key, focused.stroke)
        assertTrue(focused.width > prominent.width)
    }

    @Test fun nativePlaylistKindCardsUseEntireTileAsSelectionTarget() {
        var selected = ""
        compose.setContent {
            MaterialTheme {
                PlaylistWizardKindCards(
                    normalSelected = true,
                    onNormal = { selected = "normal" },
                    onSmart = { selected = "smart" },
                )
            }
        }
        compose.onNodeWithTag("playlist-kind-normal").assertIsSelected()
        compose.onNodeWithTag("playlist-kind-smart").performClick()
        compose.runOnIdle { assertEquals("smart", selected) }
    }

    @Test fun sourceChoicesPreserveExactProvidersWithoutPretendingMetadataHasAudio() {
        val normal = playlistWizardSourceChoices(
            normalMode = true,
            availableSourceKeys = listOf(
                "local-mediastore", "opensubsonic:alpha", "opensubsonic:beta",
            ),
        )
        assertEquals(
            listOf(null, "local-mediastore", "opensubsonic:alpha", "opensubsonic:beta"),
            normal.map { it.value },
        )
        assertTrue(normal.map { it.title }.distinct().size == normal.size)
        assertTrue(normal.all { it.caption.isNotBlank() })
        val smart = playlistWizardSourceChoices(
            normalMode = false,
            availableSourceKeys = listOf("local-mediastore", "opensubsonic:alpha"),
        )
        assertEquals(listOf(null, "local", "opensubsonic"), smart.map { it.value })
        assertEquals(
            listOf(null, "local"),
            playlistWizardSourceChoices(false, listOf("local-mediastore")).map { it.value },
        )
    }

    @Test fun sourceSelectionSummaryTracksExactNavidromeAccountAndDisconnectedSource() {
        val connected = listOf(
            "local-mediastore", "opensubsonic:alpha", "opensubsonic:beta",
        )
        val second = playlistWizardSelectedSourceChoice(
            normalMode = true,
            availableSourceKeys = connected,
            selectedValue = "opensubsonic:beta",
        )
        assertEquals("Navidrome 2", second.title)
        assertEquals(com.music.bitchord.ui.icons.PlaylistEditorIcons.Audio, second.icon)
        val local = playlistWizardSelectedSourceChoice(true, connected, "local-mediastore")
        assertEquals("Música local", local.title)
        assertEquals(com.music.bitchord.ui.icons.PlaylistEditorIcons.Music, local.icon)
        val missing = playlistWizardSelectedSourceChoice(
            normalMode = true,
            availableSourceKeys = connected,
            selectedValue = "opensubsonic:removed",
        )
        assertEquals("Fonte desconectada", missing.title)
        assertEquals(com.music.bitchord.ui.icons.PlaylistEditorIcons.Audio, missing.icon)
        assertEquals("Todas as fontes",
            playlistWizardSelectedSourceChoice(true, connected, null).title)
    }

    @Test fun sourceSummaryDisplaysProviderIdentityAndAccessibleChangeAction() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardSourceSummary(
                    choice = playlistWizardSelectedSourceChoice(
                        normalMode = true,
                        availableSourceKeys = listOf(
                            "local-mediastore", "opensubsonic:alpha", "opensubsonic:beta",
                        ),
                        selectedValue = "opensubsonic:beta",
                    ),
                    onClick = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Navidrome 2").assertExists()
        compose.onNodeWithContentDescription("Fonte selecionada: Navidrome 2").assertExists()
        compose.onNodeWithTag("playlist-source-selector").assertHasClickAction()
        compose.onNodeWithText("Alterar").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun sourceSelectionCardsHaveDescriptionAndNoLegacyRadioIndicators() {
        var chosen: String? = "not-chosen"
        compose.setContent {
            MaterialTheme {
                PlaylistWizardSourceChoices(
                    options = listOf(
                        PlaylistWizardSourceChoice(null, "Todas as fontes", "Local e servidor",
                            com.music.bitchord.ui.icons.PlaylistEditorIcons.Globe),
                        PlaylistWizardSourceChoice("local", "Música local", "Dispositivo",
                            com.music.bitchord.ui.icons.PlaylistEditorIcons.Music),
                    ),
                    selectedValue = null,
                    onChoose = { chosen = it },
                )
            }
        }
        compose.onNodeWithTag("playlist-source-option-all").assertIsSelected()
        compose.onNodeWithText("Dispositivo").assertExists()
        compose.onNodeWithTag("playlist-source-option-local").performClick()
        compose.runOnIdle { assertEquals("local", chosen) }
    }
}
