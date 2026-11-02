package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.CompositionLocalProvider
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
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

    @Test fun emptyPlaylistSuggestionsShowFiveDistinctAvailableTracksAndRotateWithoutFabrication() {
        val rows = (1..12).map { index ->
            V2DisplayRow(id = "recording-$index", title = "Faixa $index", artist = "Artista")
        }
        val selected = setOf("recording-1", "recording-3")
        val first = suggestedCanonicalPlaylistTracks(rows, selected, page = 0)
        val second = suggestedCanonicalPlaylistTracks(rows, selected, page = 1)
        assertEquals(5, first.size)
        assertEquals(5, second.size)
        assertEquals(5, first.map { it.id }.distinct().size)
        assertEquals(0, first.count { it.id in selected })
        assertEquals(0, first.map { it.id }.intersect(second.map { it.id }.toSet()).size)
        // A new cycle reshuffles instead of always restarting the alphabetical
        // first page. Its own contents remain reproducible for the same seed.
        val third = suggestedCanonicalPlaylistTracks(rows, selected, page = 2)
        assertEquals(5, third.size)
        assertEquals(5, third.map { it.id }.distinct().size)
        assertEquals(0, third.count { it.id in selected })
        assertEquals(third, suggestedCanonicalPlaylistTracks(rows, selected, page = 2))
    }

    @Test fun suggestionRefreshShufflesDeterministicallyAndAvoidsRepeatsUntilExhausted() {
        val rows = (1..30).map { index ->
            V2DisplayRow(id = "recording-$index", title = "Faixa $index", artist = "Artista")
        }
        val pages = (0..5).map { page ->
            suggestedCanonicalPlaylistTracks(rows, emptySet(), page, shuffleSeed = 42L)
        }
        val ids = pages.flatten().map { it.id }
        assertEquals(30, ids.distinct().size)
        assertEquals(30, ids.size)
        assertEquals(false, ids == rows.map { it.id })
        assertEquals(pages[0], suggestedCanonicalPlaylistTracks(rows, emptySet(), 0, shuffleSeed = 42L))
    }

    @Test fun suggestionsDoNotImmediatelyRepeatAcrossRandomCycleBoundaries() {
        val rows = (1..24).map { index ->
            V2DisplayRow(id = "recording-$index", title = "Faixa $index", artist = "Artista")
        }
        // 24 candidates: four full groups and one four-track final group.
        // The last four songs must not immediately reappear on the next refresh.
        for (seed in 0L..20L) {
            val last = suggestedCanonicalPlaylistTracks(rows, emptySet(), 4, seed)
                .map { it.id }.toSet()
            val next = suggestedCanonicalPlaylistTracks(rows, emptySet(), 5, seed)
                .map { it.id }.toSet()
            assertEquals(emptySet<String>(), last.intersect(next))
        }
        val small = rows.take(6)
        for (seed in 0L..20L) {
            val last = suggestedCanonicalPlaylistTracks(small, emptySet(), 1, seed)
                .map { it.id }.toSet()
            val next = suggestedCanonicalPlaylistTracks(small, emptySet(), 2, seed)
                .map { it.id }.toSet()
            assertEquals(emptySet<String>(), last.intersect(next))
        }
    }

    @Test fun localGenreAsksForTextRatherThanSilentPresence() {
        val comparison = smartRuleNewComparison(SmartPlaylistRuleField.LOCAL_GENRE)
        assertEquals(SmartPlaylistRuleOperator.CONTAINS, comparison.operator)
        assertEquals("", comparison.textValue)
        assertEquals("music", smartConditionCategories.single {
            SmartPlaylistRuleField.LOCAL_GENRE in it.fields
        }.id)
    }

    @Test fun addingSuggestedRecordingPreservesMetadataAndPreventsDuplicates() {
        val existing = com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist(
            id = "saved-1", name = "Minha playlist", recordingIds = listOf("rec-1"),
            description = "Descrição", sourceProviderKey = "local-mediastore",
            coverUri = "content://media/example",
        )
        val appended = appendSuggestedRecording(existing, "rec-2")
        assertEquals(listOf("rec-1", "rec-2"), appended.recordingIds)
        assertEquals(existing.description, appended.description)
        assertEquals(existing.coverUri, appended.coverUri)
        assertEquals(existing.sourceProviderKey, appended.sourceProviderKey)
        assertEquals(appended, appendSuggestedRecording(appended, "rec-2"))
    }

    @Test fun countOfSmartConditionsUsesNaturalPortugueseForZeroOneAndPlural() {
        assertEquals("0 condições", smartConditionCountLabel(0))
        assertEquals("1 condição", smartConditionCountLabel(1))
        assertEquals("2 condições", smartConditionCountLabel(2))
        assertEquals("1 condição configurada", smartConditionCountLabel(1, configured = true))
        assertEquals("2 condições configuradas", smartConditionCountLabel(2, configured = true))
    }

    @Test fun categoryRowsUseReadableNativeIconBadgesAndKeepTheirClickTarget() {
        var pressed = 0
        compose.setContent {
            PlaylistWizardNavigationList(listOf(
                PlaylistWizardNavOption(
                    title = "Músicas e metadados",
                    description = "Título e artista",
                    icon = Icons.Rounded.MusicNote,
                    onClick = { pressed++ },
                ),
            ))
        }
        // The native clickable SettingsRow merges its descendants for accessibility.
        // Inspect the unmerged tree to assert the actual icon badge is rendered.
        compose.onNodeWithTag(
            "playlist-category-icon-badge", useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("Músicas e metadados").performClick()
        compose.runOnIdle { assertEquals(1, pressed) }
    }

    @Test fun secondaryPlaylistActionsAreExplicitNativeButtonsNotBareText() {
        var clicked = 0
        compose.setContent {
            PlaylistWizardSecondaryAction(label = "Escolher capa", onClick = { clicked++ })
        }
        compose.onNodeWithTag("playlist-wizard-secondary-button").assertExists()
        compose.onNodeWithText("Escolher capa").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun playlistWizardUsesNativeSettingsContainersAndSearchSurface() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Nova", rule = rule, hasConfiguredRules = false, normalMode = false,
                sort = SmartPlaylistV2Sort.TITLE_ASC, limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        // The LazyColumn virtualizes cards on compact phone windows; assert
        // each group when it is visible rather than requiring both composed.
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("Fontes de origem").assertExists()
        assertEquals(true, compose.onAllNodesWithTag("native-settings-group-surface")
            .fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(4)
        compose.onNodeWithText("Tipo de playlist").assertExists()
        assertEquals(true, compose.onAllNodesWithTag("native-settings-group-surface")
            .fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("+ Adicionar primeira condição").performClick()
        compose.onNodeWithTag("native-detail-search-field").assertExists()
    }

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

    @Test fun normalCatalogSelectionUsesSharedTrackRowAndPreservesRecordingId() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        var selectedId: String? = null
        var browseType = CanonicalPlaylistBrowseType.TRACKS
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Nova playlist",
                rule = rule,
                normalMode = true,
                normalRows = listOf(V2DisplayRow("canonical:recording-25", "Faixa exata", "Artista real")),
                onToggleNormalTrack = { selectedId = it },
                onNormalBrowseTypeChange = { browseType = it },
                sort = SmartPlaylistV2Sort.TITLE_ASC, limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        // A LazyColumn intentionally does not compose off-screen recording rows.
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("Faixa exata").performClick()
        compose.runOnIdle { assertEquals("canonical:recording-25", selectedId) }
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(1)
        compose.onNodeWithText("Álbuns").performClick()
        compose.runOnIdle { assertEquals(CanonicalPlaylistBrowseType.ALBUMS, browseType) }
    }

    @Test fun normalPickerUsesNativeTrackGeometryAndAlbumCredit() {
        val rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        val album = CanonicalPlaylistBrowseGroup(
            "release:verified", "Bad Idea", CanonicalPlaylistBrowseType.ALBUMS,
            artworkRow = V2DisplayRow("recording:verified", "Bad Idea", "SAY MY NAME"),
        )
        var added = 0
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Minha playlist",
                rule = rules, normalMode = true,
                normalBrowseType = CanonicalPlaylistBrowseType.ALBUMS,
                normalGroups = listOf(album),
                onAddNormalAlbum = { added++ },
                sort = SmartPlaylistV2Sort.TITLE_ASC, limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(2)
        compose.onNodeWithText("SAY MY NAME").assertExists()
        compose.onNodeWithTag("canonical-album-picker-release:verified").assertExists()
        compose.onNodeWithContentDescription("Adicionar álbum inteiro").performClick()
        compose.runOnIdle { assertEquals(1, added) }
    }

    @Test fun albumRowOffersBulkAddWithoutNavigatingToAlbum() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        val album = CanonicalPlaylistBrowseGroup(
            "release:exact-1", "Get Up", CanonicalPlaylistBrowseType.ALBUMS,
        )
        var addedId: String? = null
        var openedId: String? = null
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Minha playlist", rule = rule, normalMode = true,
                normalBrowseType = CanonicalPlaylistBrowseType.ALBUMS,
                normalGroups = listOf(album),
                onAddNormalAlbum = { addedId = it.id },
                onNormalBrowseGroupChange = { openedId = it?.id },
                sort = SmartPlaylistV2Sort.TITLE_ASC, limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Selecionadas: 0").assertExists()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithContentDescription("Adicionar álbum inteiro").performClick()
        compose.runOnIdle {
            assertEquals("release:exact-1", addedId)
            assertEquals(null, openedId)
        }
    }

    @Test fun expandingAlbumKeepsItsRowAndRendersTracksNestedUnderIt() {
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        val album = CanonicalPlaylistBrowseGroup(
            "release:newjeans", "Get Up", CanonicalPlaylistBrowseType.ALBUMS,
        )
        val expanded = mutableStateOf<CanonicalPlaylistBrowseGroup?>(null)
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Nova playlist", rule = rule, normalMode = true,
                normalBrowseType = CanonicalPlaylistBrowseType.ALBUMS,
                normalBrowseGroup = expanded.value, normalGroups = listOf(album),
                normalRows = if (expanded.value != null)
                    listOf(V2DisplayRow("recording:hurt", "Hurt", "NewJeans"))
                    else emptyList(),
                onNormalBrowseGroupChange = { expanded.value = it },
                sort = SmartPlaylistV2Sort.TITLE_ASC, limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null, savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
        compose.onNodeWithText("Get Up").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(4)
        compose.onNodeWithText("Hurt").assertExists()
        compose.onNodeWithText("Get Up").assertExists()
        compose.runOnIdle { assertEquals(album, expanded.value) }
    }

    @Test fun normalReviewShowsTrackAfterFiftyWithoutArtificialTruncation() {
        val rows = (1..81).map { index ->
            V2DisplayRow("rec-${index}", "Track ${index}", "Artist ${index}")
        }
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Todas as faixas",
                rule = rule,
                normalMode = true,
                normalSelectedIds = rows.map { it.id },
                normalSelectedRows = rows,
                sort = SmartPlaylistV2Sort.RATING_DESC,
                limit = "100", seed = "0",
                preview = null, loading = false, error = null,
                editingId = null, editingFilterId = null,
                savedDefinitions = emptyList(),
                onNameChange = {}, onRuleChange = {}, onSortChange = {},
                onLimitChange = {}, onSeedChange = {}, onPreview = {},
                onSave = {}, onSaveFilter = {}, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Revisar playlist").performClick()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(83)
        compose.onNodeWithText("Track 81").assertExists()
        compose.onNodeWithText("Artist 81").assertExists()
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
        compose.onNodeWithText(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getString(com.music.bitchord.R.string.cancel),
        ).performClick()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, cancelled) }
        compose.onNodeWithText("Excluir definitivamente").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun existingNormalPlaylistOpensDedicatedEditPageInsteadOfNewPlaylistWizard() {
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Minha lista", description = "Faixas selecionadas",
                rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                )),
                sort = SmartPlaylistV2Sort.TITLE_ASC,
                limit = "100", seed = "0", preview = null, loading = false,
                error = null, editingId = null, editingFilterId = null,
                savedDefinitions = emptyList(), onNameChange = {}, onRuleChange = {},
                onSortChange = {}, onLimitChange = {}, onSeedChange = {},
                onPreview = {}, onSave = {}, onSaveFilter = {},
                normalMode = true, editingNormalId = "normal-1",
                onPreviewAction = null, currentPreviewQuery = null,
                onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Editar playlist").assertExists()
        compose.onNodeWithText("Editar músicas").assertExists()
        compose.onNodeWithText("Nova playlist").assertDoesNotExist()
        compose.onNodeWithText("Excluir playlist").performClick()
        compose.onNodeWithText("Excluir playlist normal?").assertExists()
    }

    @Test fun existingSmartPlaylistCanOpenNativeRuleEditorFromEditPage() {
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Automática",
                rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                )),
                sort = SmartPlaylistV2Sort.TITLE_ASC,
                limit = "100", seed = "0", preview = null, loading = false,
                error = null, editingId = "smart-1", editingFilterId = null,
                savedDefinitions = emptyList(), onNameChange = {}, onRuleChange = {},
                onSortChange = {}, onLimitChange = {}, onSeedChange = {},
                onPreview = {}, onSave = {}, onSaveFilter = {},
                normalMode = false, onPreviewAction = null, currentPreviewQuery = null,
                onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Editar playlist").assertExists()
        compose.onNodeWithText("Excluir playlist").performClick()
        compose.onNodeWithText("Excluir playlist inteligente?").assertExists()
        compose.onNodeWithText(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getString(com.music.bitchord.R.string.cancel),
        ).performClick()
        compose.onNodeWithText("Editar regras").performClick()
        compose.onNodeWithText("Regras da playlist").assertExists()
    }

    @Test fun editingReusableFilterShowsOnlyPersistentFieldsAndNamesItsRuleEditor() {
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Filtro de avaliações",
                rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                )),
                sort = SmartPlaylistV2Sort.TITLE_ASC,
                limit = "100", seed = "0", preview = null, loading = false,
                error = null, editingId = null, editingFilterId = "filter-1",
                savedDefinitions = emptyList(), onNameChange = {}, onRuleChange = {},
                onSortChange = {}, onLimitChange = {}, onSeedChange = {},
                onPreview = {}, onSave = {}, onSaveFilter = {},
                normalMode = false, onPreviewAction = null, currentPreviewQuery = null,
                onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Editar filtro").assertExists()
        compose.onNodeWithText("Nome do filtro").assertExists()
        compose.onNodeWithText("Nome da playlist").assertDoesNotExist()
        compose.onNodeWithText("Descrição (opcional)").assertDoesNotExist()
        compose.onNodeWithText("Editar regras").performClick()
        compose.onNodeWithText("Regras do filtro").assertExists()
        compose.onNodeWithText("Limite e ordenação").assertDoesNotExist()
    }


    @Test fun existingSmartPlaylistCanExportReusableFilterWithoutLeavingEditFlow() {
        var exports = 0
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Minha inteligente",
                rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                )),
                hasConfiguredRules = true, sort = SmartPlaylistV2Sort.TITLE_ASC,
                limit = "100", seed = "0", preview = null, loading = false,
                error = null, editingId = "smart-1", editingFilterId = null,
                savedDefinitions = emptyList(), onNameChange = {},
                onRuleChange = {}, onSortChange = {}, onLimitChange = {},
                onSeedChange = {}, onPreview = {}, onSave = {},
                onSaveFilter = { exports++ }, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Editar playlist").assertExists()
        compose.onNodeWithText("Salvar como filtro reutilizável").performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        compose.onNodeWithText("Editar playlist").assertExists()
    }

    @Test fun newReusableFilterUsesExistingRuleEditorAndSavesWithoutPlaylistControls() {
        var saves = 0
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
            ),
        ))
        compose.setContent {
            SmartPlaylistV2WizardUi(
                name = "Só músicas",
                rule = rule, creatingFilter = true, hasConfiguredRules = true,
                sort = SmartPlaylistV2Sort.RATING_DESC,
                limit = "100", seed = "0", preview = null, loading = false,
                error = null, editingId = null, editingFilterId = null,
                savedDefinitions = emptyList(), onNameChange = {},
                onRuleChange = {}, onSortChange = {}, onLimitChange = {},
                onSeedChange = {}, onPreview = {}, onSave = {},
                onSaveFilter = { saves++ }, onPreviewAction = null,
                currentPreviewQuery = null, onManage = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Novo filtro").assertExists()
        compose.onNodeWithText("Nome do filtro").assertExists()
        compose.onNodeWithText("Nome da playlist").assertDoesNotExist()
        compose.onNodeWithText("Descrição (opcional)").assertDoesNotExist()
        compose.onNodeWithText("Editar regras").performClick()
        compose.onNodeWithText("Regras do filtro").assertExists()
        compose.onNodeWithText("Concluir regras").performClick()
        compose.onNodeWithText("Salvar filtro").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun maximalDepthFilterRemainsValidWhenOpeningItsEditor() {
        var deep: SmartPlaylistRule = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        repeat(8) { deep = SmartPlaylistRule.Not(deep) }
        SmartPlaylistV2Query(deep)
        assertEquals(deep, editableSmartPlaylistRuleRoot(deep))
    }

    @Test fun singleConditionOrNegatedFilterGetsEditableRootWithoutChangingMeaning() {
        val condition = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        val negated = SmartPlaylistRule.Not(condition)
        assertEquals(
            SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(condition)),
            editableSmartPlaylistRuleRoot(condition),
        )
        assertEquals(
            SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(negated)),
            editableSmartPlaylistRuleRoot(negated),
        )
        val grouped = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
            condition, negated,
        ))
        assertEquals(grouped, editableSmartPlaylistRuleRoot(grouped))
    }

    @Test fun savedFilterActionsIncludeIntersectionUnionAndSafeNormalMode() {
        assertEquals(listOf(
            "Aplicar regras ao rascunho",
            "Combinar com o rascunho (E)",
            "Combinar com o rascunho (OU)",
            "Editar filtro", "Criar playlist com este filtro", "Excluir filtro",
        ), savedFilterActionsForDraft(false, null))
        assertEquals(listOf(
            "Editar filtro", "Criar playlist com este filtro", "Excluir filtro",
        ), savedFilterActionsForDraft(true, "normal-id"))
    }

    @Test fun savingExportedFilterKeepsPlaylistDraftWhileEditingFilterReturnsToManager() {
        assertEquals(false, shouldClosePlaylistDraftAfterSavingFilter(null, false))
        assertEquals(true, shouldClosePlaylistDraftAfterSavingFilter("filter-existing", false))
        assertEquals(true, shouldClosePlaylistDraftAfterSavingFilter(null, true))
    }

    @Test fun derivedPlaylistFromSavedFilterDoesNotInheritOriginalEditMode() {
        assertEquals(true, shouldOpenPlaylistAsEdit(
            initialPlaylistId = null, initialNormalPlaylistId = "normal-1",
            editorEpoch = 0, editingId = null, editingNormalId = null, editingFilterId = null,
        ))
        assertEquals(true, shouldOpenPlaylistAsEdit(
            initialPlaylistId = null, initialNormalPlaylistId = "normal-1",
            editorEpoch = 1, editingId = null, editingNormalId = "normal-1", editingFilterId = null,
        ))
        assertEquals(false, shouldOpenPlaylistAsEdit(
            initialPlaylistId = null, initialNormalPlaylistId = "normal-1",
            editorEpoch = 2, editingId = null, editingNormalId = null, editingFilterId = null,
        ))
        assertEquals(true, shouldOpenPlaylistAsEdit(
            initialPlaylistId = "smart-1", initialNormalPlaylistId = null,
            editorEpoch = 3, editingId = null, editingNormalId = null, editingFilterId = "filter-2",
        ))
    }

    @Test fun switchingEditorsRequiresConfirmationForExistingOrPopulatedDraft() {
        assertEquals(true, shouldConfirmReplacingDraft(
            editing = true, name = "", description = "", coverUri = null,
            selectedCount = 0, hasConfiguredRules = false,
        ))
        assertEquals(true, shouldConfirmReplacingDraft(
            editing = false, name = "", description = "", coverUri = null,
            selectedCount = 1, hasConfiguredRules = false,
        ))
        assertEquals(true, shouldConfirmReplacingDraft(
            editing = false, name = "", description = "", coverUri = "content://covers/1",
            selectedCount = 0, hasConfiguredRules = false,
        ))
        assertEquals(false, shouldConfirmReplacingDraft(
            editing = false, name = "", description = "", coverUri = null,
            selectedCount = 0, hasConfiguredRules = false,
        ))
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
        // Scroll to the primary empty-state action on Robolectric's compact viewport.
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(3)
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
        compose.onNodeWithText("Fontes de origem").assertExists()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Regras da playlist").assertExists()
        compose.onNodeWithText("Grupo 1").assertExists()
        compose.onNodeWithText("Revisar playlist").performClick()
        compose.onNodeWithText("Revisar e criar").assertExists()
        compose.onNodeWithTag("smart-playlist-wizard-list").performScrollToIndex(4)
        compose.onNodeWithText("Músicas selecionadas").assertExists()
        compose.onNodeWithText("Preparando prévia automaticamente…").assertExists()
        compose.onNodeWithText("Criar playlist").assertIsNotEnabled()
    }

    @Test fun playlistTrackRowShowsTrustedNativeAndRemoteStarsWithoutPerRowLookup() {
        compose.setContent {
            CompositionLocalProvider(LocalCanonicalRatingDisplay provides
                CanonicalRatingDisplaySnapshot(byRecordingId = mapOf(
                    "rated" to RatingDisplay(remoteValue = 5),
                ))) {
                CanonicalPlaylistTrackRow(
                    track = V2DisplayRow(id = "rated", title = "Faixa", artist = "Artista"),
                    onPlay = {},
                )
            }
        }
        compose.onNodeWithContentDescription("5/5", substring = true).assertExists()
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
        compose.onNodeWithContentDescription("Texto a comparar").performTextInput("novo")
        compose.onNodeWithText("Adicionar condição").performClick()
        compose.runOnIdle {
            val root = state.value as SmartPlaylistRule.Group
            val group = root.children.single() as SmartPlaylistRule.Group
            assertEquals(original, group.children.first())
            assertEquals(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.CONTAINS,
                textValue = "novo",
            ), group.children.last())
        }
    }
}
