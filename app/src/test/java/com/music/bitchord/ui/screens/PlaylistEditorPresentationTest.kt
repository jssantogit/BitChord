package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
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

/** Guard the unified new-playlist visual language without depending on a screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistEditorPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creatorAndGlobalMenuCanShareCompactAnimatedPopupRows() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box {
                    com.music.bitchord.ui.components.NativeCompactOverflowMenu(
                        expanded = true,
                        onDismissRequest = {},
                    ) {
                        com.music.bitchord.ui.components.NativeCompactMenuItem(
                            label = "Seus filtros",
                            icon = PlaylistEditorIcons.Filters,
                            onClick = { opened++ },
                        )
                        com.music.bitchord.ui.components.NativeCompactMenuDivider()
                        com.music.bitchord.ui.components.NativeCompactMenuItem(
                            label = "Excluir playlist",
                            icon = PlaylistEditorIcons.Delete,
                            destructive = true,
                            onClick = { opened++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithText("Seus filtros").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun exitingCompactMenuKeepsItsAnimationButPreventsRepeatActions() {
        val expanded = mutableStateOf(true)
        var actions = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.NativeCompactOverflowMenu(
                    expanded = expanded.value,
                    onDismissRequest = { expanded.value = false },
                ) {
                    com.music.bitchord.ui.components.NativeCompactMenuItem(
                        label = "Ação temporária",
                        onClick = {
                            actions++
                            expanded.value = false
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Ação temporária").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Ação temporária").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, actions) }
        // The exit transition remains visible, but its clickable row is now disabled.
        compose.onNodeWithTag("native-compact-overflow").assertExists()
        compose.onNodeWithTag("native-compact-menu-item").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(300L)
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
    }

    @Test fun selectingOneRowImmediatelyLocksTheOtherRowsOfTheSamePopup() {
        var primary = 0
        var secondary = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.NativeCompactOverflowMenu(
                    expanded = true,
                    onDismissRequest = {},
                ) {
                    com.music.bitchord.ui.components.NativeCompactMenuItem(
                        label = "Primeira ação",
                        onClick = { primary++ },
                    )
                    com.music.bitchord.ui.components.NativeCompactMenuItem(
                        label = "Segunda ação",
                        onClick = { secondary++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Primeira ação").performClick()
        compose.onAllNodesWithTag("native-compact-menu-item").assertCountEquals(2)
        compose.onAllNodesWithTag("native-compact-menu-item")[0].assertIsNotEnabled()
        compose.onAllNodesWithTag("native-compact-menu-item")[1].assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, primary)
            assertEquals(0, secondary)
        }
    }

    @Test fun compactPopupRearmsActionsAfterItHasActuallyBeenReopened() {
        val expanded = mutableStateOf(true)
        var actions = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.NativeCompactOverflowMenu(
                    expanded = expanded.value,
                    onDismissRequest = { expanded.value = false },
                ) {
                    com.music.bitchord.ui.components.NativeCompactMenuItem(
                        label = "Reabrir menu",
                        onClick = {
                            actions++
                            expanded.value = false
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Reabrir menu").performClick()
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
        compose.runOnIdle { expanded.value = true }
        compose.onNodeWithText("Reabrir menu").performClick()
        compose.runOnIdle { assertEquals(2, actions) }
    }

    @Test fun filterManagerUsesPlainTitleAndTopRightCreateControl() {
        var created = 0
        compose.setContent {
            MaterialTheme {
                PlaylistManagerChrome(
                    onBack = {},
                    onCreateFilter = { created++ },
                )
            }
        }
        compose.onNodeWithText("Seus filtros").assertExists()
        compose.onNodeWithText("Gerenciar coleções").assertDoesNotExist()
        compose.onNodeWithText("Inteligentes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Criar filtro").performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun secondaryEditorFooterActionIsACompactTextControl() {
        var clicked = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardTextAction(
                    label = "Salvar como filtro reutilizável",
                    onClick = { clicked++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-wizard-text-action").assertExists()
        compose.onAllNodesWithTag("playlist-wizard-secondary-button").assertCountEquals(0)
        compose.onNodeWithText("Salvar como filtro reutilizável").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun compactEditorActionsUseTheGlobal40DpCircularControl() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCompactAction(
                    label = "Opções",
                    icon = PlaylistEditorIcons.More,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-editor-icon-action").assertWidthIsEqualTo(40.dp)
        compose.onNodeWithTag("playlist-editor-icon-action").assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription("Opções").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun collectionEmptyStateUsesEditorialLayoutWithoutInactiveSettingsCard() {
        compose.setContent {
            MaterialTheme {
                PlaylistManagerEmptyState(
                    title = "Nenhum filtro salvo",
                    message = "Crie um filtro para reutilizar suas regras.",
                    icon = PlaylistEditorIcons.Filters,
                )
            }
        }
        compose.onNodeWithTag("playlist-manager-empty-state").assertExists()
        compose.onNodeWithText("Nenhum filtro salvo").assertExists()
        compose.onNodeWithText("Crie um filtro para reutilizar suas regras.").assertExists()
        compose.onNodeWithTag("native-settings-group-surface").assertDoesNotExist()
    }

    @Test fun conditionFieldsUseOrderedNumbersInsteadOfRepeatedMusicIcons() {
        compose.setContent {
            MaterialTheme {
                PlaylistWizardFieldList(
                    listOf(
                        PlaylistWizardNavOption("Título", "Palavras no título", PlaylistEditorIcons.Music) {},
                        PlaylistWizardNavOption("Artista", "Nome do artista", PlaylistEditorIcons.Music) {},
                    ),
                )
            }
        }
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("2.").assertExists()
        compose.onAllNodesWithTag("playlist-field-icon", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithTag("playlist-field-row").assertCountEquals(2)
    }

    @Test fun conditionFieldRowsKeepNavigationWithoutDuplicatedChevrons() {
        var clicked = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardNavigationList(
                    listOf(
                        PlaylistWizardNavOption("Título", "Filtrar por título") { clicked++ },
                        PlaylistWizardNavOption("Artista", "Filtrar por artista") { clicked++ },
                    ),
                )
            }
        }
        compose.onAllNodesWithTag("playlist-navigation-chevron", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Artista").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun ruleActionsShareOneNativeGroupAndPreserveAllDestinations() {
        var additions = 0
        var nested = 0
        compose.setContent {
            MaterialTheme {
                PlaylistWizardRuleActions(
                    listOf(
                        PlaylistWizardRuleAction(
                            label = "Adicionar condição",
                            icon = PlaylistEditorIcons.Add,
                            onClick = { additions++ },
                        ),
                        PlaylistWizardRuleAction(
                            label = "Grupo aninhado",
                            icon = PlaylistEditorIcons.Folder,
                            onClick = { nested++ },
                        ),
                    ),
                )
            }
        }
        compose.onAllNodesWithTag("native-settings-group-surface", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithText("Adicionar condição").performClick()
        compose.onNodeWithText("Grupo aninhado").performClick()
        compose.runOnIdle {
            assertEquals(1, additions)
            assertEquals(1, nested)
        }
    }

    @Test fun conditionCategoriesUseTwoColumnTilesAndReserveFullWidthForLastCategory() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCategoryGrid(
                    (1..7).map { index ->
                        PlaylistWizardNavOption(
                            title = "Categoria $index",
                            description = "Grupo de condições $index",
                            icon = PlaylistEditorIcons.Music,
                            onClick = { opened = "category-$index" },
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag("playlist-category-grid", useUnmergedTree = true).assertExists()
        val tiles = compose.onAllNodesWithTag("playlist-category-tile", useUnmergedTree = true)
        tiles.assertCountEquals(7)
        compose.onAllNodesWithTag("playlist-category-wide-tile", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithTag("playlist-category-icon", useUnmergedTree = true)
            .assertCountEquals(7)
        val first = tiles[0].fetchSemanticsNode().boundsInRoot
        val second = tiles[1].fetchSemanticsNode().boundsInRoot
        val third = tiles[2].fetchSemanticsNode().boundsInRoot
        val last = tiles[6].fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top)
        org.junit.Assert.assertTrue(first.left < second.left)
        org.junit.Assert.assertTrue(third.top > first.top)
        org.junit.Assert.assertTrue(last.width > first.width + second.width)
        compose.onNodeWithText("Categoria 7").performClick()
        compose.runOnIdle { assertEquals("category-7", opened) }
    }

    @Test fun categoryGridStillNavigatesThroughAnyCategoryAndKeepsLabelsAccessible() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                PlaylistWizardCategoryGrid(
                    listOf(
                        PlaylistWizardNavOption(
                            "Músicas e metadados", "Título, artista, álbum",
                            PlaylistEditorIcons.Music,
                        ) { opened = "music" },
                        PlaylistWizardNavOption(
                            "Avaliações", "Notas em estrelas",
                            PlaylistEditorIcons.Ratings,
                        ) { opened = "ratings" },
                    ),
                )
            }
        }
        compose.onNodeWithText("Avaliações").performClick()
        compose.runOnIdle { assertEquals("ratings", opened) }
        compose.onNodeWithText("Músicas e metadados").assertExists()
        compose.onNodeWithText("Notas em estrelas").assertExists()
    }
}
