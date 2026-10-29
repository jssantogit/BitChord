package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Sort
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Screen contract without any DB, playback or fake catalog responses. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistV2WizardComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewDisplaysOnlyReturnedCanonicalPreviewMetadata() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        val query = SmartPlaylistV2Query(rule, SmartPlaylistV2Sort.TITLE_ASC, 100)
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Favoritas",
                rule = rule, sort = query.sort, limit = "100", seed = "0",
                preview = listOf(V2DisplayRow("rec:verified", "Hurt", "NewJeans")),
                loading = false, error = null, editingId = null,
                editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {},
                onSortChange = {}, onLimitChange = {}, onSeedChange = {},
                onPreview = {}, onSave = {}, onSaveFilter = {},
                onPreviewAction = null, currentPreviewQuery = query,
                onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Revisar playlist").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(5)
        compose.onNodeWithText("Hurt").assertExists()
        compose.onNodeWithText("NewJeans").assertExists()
    }

    @Test fun deletingSavedPlaylistRequiresExplicitConfirmation() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            SmartPlaylistDeletionConfirmation(
                name = "Favoritas", isFilter = false,
                onCancel = { cancelled++ }, onConfirm = { confirmed++ },
            )
        }
        compose.onNodeWithText("Excluir playlist?").assertExists()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, cancelled) }
        compose.onNodeWithText("Excluir definitivamente").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun newPlaylistDoesNotClaimAnAutomaticPlaybackRule() {
        val placeholder = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                1.0,
            ),
        ))
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Nova seleção",
                rule = placeholder, hasConfiguredRules = false,
                sort = SmartPlaylistV2Sort.RATING_DESC,
                limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null,
                savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {},
                onSortChange = {}, onLimitChange = {}, onSeedChange = {},
                onPreview = {}, onSave = {}, onSaveFilter = {},
                onPreviewAction = null, currentPreviewQuery = null,
                onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Nenhuma condição adicionada").assertExists()
        compose.onNodeWithText("Adicionar primeira condição", substring = true).performClick()
        compose.onNodeWithText("Adicionar condição").assertExists()
    }

    @Test fun editorStartsWithIdentityAndKeepsRuleEngineOutOfTheMainForm() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                1.0,
            ),
        ))
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Só lançamentos",
                rule = rule,
                sort = SmartPlaylistV2Sort.RATING_DESC,
                limit = "100",
                seed = "0",
                preview = null,
                loading = false,
                error = null,
                editingId = null,
                editingFilterId = null,
                savedDefinitions = emptyList(),
                onNameChange = {},
                onRuleChange = {},
                onSortChange = {},
                onLimitChange = {},
                onSeedChange = {},
                onPreview = {},
                onSave = {},
                onSaveFilter = {},
                onPreviewAction = null,
                currentPreviewQuery = null,
                onManage = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Nova playlist").assertExists()
        // Identity now includes a 112dp neutral artwork preview; the sources
        // card is below the fold on Robolectric's compact test window.
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("Fontes do catálogo").assertExists()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Regras da playlist").assertExists()
        compose.onNodeWithText("Grupo 1").assertExists()
        compose.onNodeWithText("Revisar playlist").performClick()
        compose.onNodeWithText("Revisar e criar").assertExists()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(4)
        compose.onNodeWithText("Músicas selecionadas").assertExists()
        compose.onNodeWithText("Ainda não calculada para estas regras").assertExists()
        compose.onNodeWithText("Criar playlist").assertIsNotEnabled()
    }

    @Test fun conditionCatalogAddsOnlyTheSelectedFieldAndPreservesExistingGroup() {
        val original = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TITLE,
            SmartPlaylistRuleOperator.CONTAINS,
            textValue = "shy",
        )
        val state = mutableStateOf<SmartPlaylistRule>(
            SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(original)),
            )),
        )
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Teste",
                rule = state.value,
                sort = SmartPlaylistV2Sort.RATING_DESC,
                limit = "100",
                seed = "0",
                preview = null,
                loading = false,
                error = null,
                editingId = null,
                editingFilterId = null,
                savedDefinitions = emptyList(),
                onNameChange = {},
                onRuleChange = { state.value = it },
                onSortChange = {},
                onLimitChange = {},
                onSeedChange = {},
                onPreview = {},
                onSave = {},
                onSaveFilter = {},
                onPreviewAction = null,
                currentPreviewQuery = null,
                onManage = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Grupo 1").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("+ Adicionar condição").performClick()
        compose.onNodeWithText("Adicionar condição").assertExists()
        // This category used to be offscreen because GROUP's scroll index
        // was retained in the newly opened CATEGORIES page.
        compose.onNodeWithText("Músicas e metadados").assertExists()
        compose.onNodeWithText("Músicas e metadados").performClick()
        compose.onNodeWithText("Título da música").performClick()
        compose.onNodeWithText("Adicionar condição").performClick()
        compose.runOnIdle {
            val root = state.value as SmartPlaylistRule.Group
            val group = root.children.single() as SmartPlaylistRule.Group
            assertEquals(original, group.children.first())
            assertEquals(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.IS_PRESENT,
            ), group.children.last())
        }
    }
}
