package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
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

    @Test fun conditionFieldRowsUseSharedDrilldownAffordance() {
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
            .assertCountEquals(2)
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

    @Test fun conditionCategoriesUseOneNativeGroupWithSevenVerticalRows() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                PlaylistWizardNavigationList(
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
        compose.onNodeWithTag("playlist-category-group", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("native-settings-group-surface", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithTag("playlist-category-icon-badge", useUnmergedTree = true)
            .assertCountEquals(7)
        compose.onAllNodesWithTag("playlist-navigation-chevron", useUnmergedTree = true)
            .assertCountEquals(7)
        compose.onAllNodesWithTag("playlist-category-tile", useUnmergedTree = true)
            .assertCountEquals(0)
        val first = compose.onNodeWithText("Categoria 1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Categoria 2").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(second.top > first.top)
        compose.onNodeWithText("Categoria 7").performClick()
        compose.runOnIdle { assertEquals("category-7", opened) }
    }


    @Test fun categoryRowsNavigateAndKeepLabelsAccessible() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                PlaylistWizardNavigationList(
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
        compose.onNodeWithTag("playlist-category-group", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("playlist-category-icon-badge", useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithText("Avaliações").performClick()
        compose.runOnIdle { assertEquals("ratings", opened) }
        compose.onNodeWithText("Músicas e metadados").assertExists()
        compose.onNodeWithText("Notas em estrelas").assertExists()
    }

}
