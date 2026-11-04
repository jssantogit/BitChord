package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun chosenPlaylistCoverOverridesFirstSongForPagePalette() {
        val row = V2DisplayRow(
            id = "recording:one",
            title = "Track",
            artist = "Artist",
            localArtworkUri = "content://media/track-art",
        )
        assertEquals(
            "content://media/custom-cover",
            canonicalPlaylistPaletteArtworkUri(
                "content://media/custom-cover", listOf(row),
            ),
        )
        assertEquals(
            "content://media/track-art",
            canonicalPlaylistPaletteArtworkUri(null, listOf(row)),
        )
        assertEquals(null, canonicalPlaylistPaletteArtworkUri(null, emptyList()))
    }

    @Test fun playlistHighlightRequiresExactVerifiedRecordingId() {
        assertEquals(
            true, isVerifiedCanonicalPlaylistCurrentTrack(
                "recording:known", "recording:known",
            ),
        )
        assertEquals(
            false, isVerifiedCanonicalPlaylistCurrentTrack(
                "recording:known", "recording:other",
            ),
        )
        assertEquals(
            false, isVerifiedCanonicalPlaylistCurrentTrack(
                "recording:known", null,
            ),
        )
        assertEquals(false, isVerifiedCanonicalPlaylistCurrentTrack("", "recording:known"))
    }

    @Test fun activeCanonicalPlaylistRowShowsNativePlaybackHighlight() {
        compose.setContent {
            CanonicalPlaylistTrackRow(
                track = V2DisplayRow(
                    id = "recording:active",
                    title = "Current Playlist Track",
                    artist = "Artist",
                ),
                onPlay = {},
                isCurrent = true,
                isPlaying = true,
            )
        }
        compose.onNodeWithTag("canonical-playlist-current-track").assertExists()
        // The same 52dp artwork frame and playing bars as SongRow.
        compose.onNodeWithTag("native-detail-track-artwork").assertExists()
        compose.onNodeWithTag("native-detail-rating-slot").assertExists()
        compose.onNodeWithText("Current Playlist Track").assertExists()
    }

    @Test fun playlistSwipeIntentHonorsPlayerSettingWithoutConvertingRecordingIds() {
        assertEquals(
            com.music.bitchord.playback.CanonicalCollectionIntent.ADD_NEXT,
            canonicalPlaylistSwipeIntent(true),
        )
        assertEquals(
            com.music.bitchord.playback.CanonicalCollectionIntent.ADD_TO_QUEUE,
            canonicalPlaylistSwipeIntent(false),
        )
    }

    @Test fun playlistDetailFrameRetainsNativeListGeometryAcrossSources() {
        compose.setContent {
            // This UI fixture needs only theme colors, not PlayerPlatform.
            val resolved = com.music.bitchord.ui.theme.themeArtworkPalette()
            NativePlaylistDetailFrame(
                palette = resolved,
                listState = androidx.compose.foundation.lazy.rememberLazyListState(),
                bottomPadding = 112.dp,
            ) {
                item { androidx.compose.material3.Text("Faixa da playlist") }
            }
        }
        compose.onNodeWithTag("native-playlist-detail-frame").assertExists()
        compose.onNodeWithText("Faixa da playlist").assertExists()
    }

    @Test fun canonicalPlaylistQueueKeepsNativeReleaseActionGeometry() {
        var queued = 0
        var played = 0
        var shuffled = 0
        compose.setContent {
            NativeReleaseActionRow(
                onPlay = { played++ },
                onShuffle = { shuffled++ },
                isLocal = false,
                onAddToQueue = { queued++ },
            )
        }
        compose.onNodeWithContentDescription("Adicionar à fila").performClick()
        compose.onNodeWithContentDescription(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getString(com.music.bitchord.R.string.shuffle),
        ).assertExists()
        compose.runOnIdle {
            assertEquals(1, queued)
            assertEquals(0, played)
            assertEquals(0, shuffled)
        }
    }

    @Test fun discardPromptUsesNativeAlertAndPreservesBothActions() {
        var discarded = 0
        var continued = 0
        compose.setContent {
            com.music.bitchord.ui.components.ConfirmationAlert(
                hazeState = null,
                title = "Descartar alterações?",
                description = "O rascunho desta playlist não será salvo.",
                confirmLabel = "Descartar",
                dismissLabel = "Continuar editando",
                destructive = true,
                onConfirm = { discarded++ },
                onDismiss = { continued++ },
            )
        }
        compose.onNodeWithTag("native-alert-scaffold").assertExists()
        compose.onNodeWithText("Continuar editando").performClick()
        compose.onNodeWithText("Descartar").performClick()
        compose.runOnIdle {
            assertEquals(1, continued)
            assertEquals(1, discarded)
        }
    }

    @Test fun emptyPlaylistListDoesNotShowDuplicateHeaderCreationAction() {
        assertEquals(false, showPlaylistHeaderCreateAction(0))
        assertEquals(true, showPlaylistHeaderCreateAction(1))
    }

    @Test fun playlistManagerNavigationUsesNativeBackAndSegments() {
        var page by mutableStateOf(0)
        var backs = 0
         compose.setContent {
            PlaylistManagerChrome(
                selectedIndex = page,
                onSelect = { page = it },
                onBack = { backs++ },
            )
        }
        compose.onNodeWithText("Gerenciar coleções").assertExists()
        compose.onNodeWithText("Clássicas").performClick()
        compose.onNodeWithContentDescription(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getString(com.music.bitchord.R.string.back),
        ).performClick()
        compose.runOnIdle {
            assertEquals(2, page)
            assertEquals(1, backs)
        }
    }

    @Test fun recentLocalLoadingUsesCenteredNativeProgressIndicator() {
        compose.setContent {
            CanonicalRecentAdditionsGrid(
                recent = emptyList(),
                onPlayTrack = {},
                loading = true,
            )
        }
        compose.onNodeWithTag("native-recent-loading").assertExists()
        compose.onNodeWithText("Carregando adições locais…").assertDoesNotExist()
    }

    @Test fun nativeLibraryLoadingUsesAnIndicatorWithoutText() {
        compose.setContent { NativeLoadingSpinner() }
        compose.onNodeWithTag("native-library-loading").assertExists()
        compose.onNodeWithText("Carregando músicas…").assertDoesNotExist()
    }

    @Test fun localRecentSnapshotRestoresBothModesWithoutRepeatedQueries() {
        RecentLocalAdditionsSnapshot.clear()
        assertEquals(null, RecentLocalAdditionsSnapshot.get(false))
        assertEquals(null, RecentLocalAdditionsSnapshot.get(true))
        val first = listOf(V2DisplayRow("recording-a", "A", "Artista"))
        RecentLocalAdditionsSnapshot.put(false, first)
        assertEquals(first, RecentLocalAdditionsSnapshot.get(false))
        assertEquals(null, RecentLocalAdditionsSnapshot.get(true))
        val all = (1..12).map { V2DisplayRow("recording-$it", "Faixa $it", "Artista") }
        RecentLocalAdditionsSnapshot.put(true, all)
        assertEquals(all, RecentLocalAdditionsSnapshot.get(true))
        assertEquals(all.take(6), RecentLocalAdditionsSnapshot.get(false))
        RecentLocalAdditionsSnapshot.clear()
    }

    @Test fun nativeSearchFieldReusesPlaylistDetailGeometryWithoutLegacyPage() {
        var query by mutableStateOf("")
        compose.setContent {
            NativeDetailSearchField(
                query = query,
                onQueryChange = { query = it },
                onClose = {},
                autoFocus = false,
                onFocused = {},
                palette = com.music.bitchord.ui.theme.themeArtworkPalette(),
                placeholder = "Buscar faixas",
            )
        }
        compose.onNodeWithTag("native-detail-search-field").assertExists()
        compose.onNodeWithContentDescription("Buscar faixas").performTextInput("Magnetic")
        compose.onNodeWithText("Magnetic").assertExists()
    }

    @Test fun playlistLibrarySearchAndSortWorkOnActualLibraryCardIdentity() {
        fun normal(title: String) = LibraryPlaylistCard.Normal(
            com.music.bitchord.data.model.ShelfItem(
                title = title, subtitle = "", thumbnailUrl = null,
                videoId = null, browseId = "local:playlist:" + title,
            ),
        )
        val records = listOf(normal("Memórias"), normal("k-pop"), normal("Rock"))
        val matched = visibleLibraryPlaylistCards(
            records, "POP", PlaylistLibraryTypeFilter.ALL, PlaylistLibrarySort.TITLE_ASC,
        )
        assertEquals(listOf("k-pop"), matched.map { it.title })
        assertEquals(
            listOf("k-pop", "Memórias", "Rock"),
            visibleLibraryPlaylistCards(
                records, "", PlaylistLibraryTypeFilter.NORMAL, PlaylistLibrarySort.TITLE_ASC,
            ).map { it.title },
        )
        assertTrue(visibleLibraryPlaylistCards(
            records, "", PlaylistLibraryTypeFilter.SMART, PlaylistLibrarySort.TITLE_ASC,
        ).isEmpty())
    }

    @Test fun playlistLibrarySearchReusesNativeDetailGlassInput() {
        var query by mutableStateOf("")
        compose.setContent {
            PlaylistLibrarySearchField(
                query = query,
                onQueryChange = { query = it },
            )
        }
        compose.onNodeWithText("Buscar em Playlists").assertExists()
        compose.onNodeWithContentDescription("Buscar em Playlists").performTextInput("newjeans")
        compose.runOnIdle { assertEquals("newjeans", query) }
    }

    @Test fun unifiedSongGridCanReuseAlbumAndArtistCardActionsWithoutAnotherCard() {
        var plays = 0
        var overflow = 0
        var chosen by mutableStateOf(false)
        var toggles = 0
        compose.setContent {
            NativeLibraryEntityCard(
                title = "Hurt",
                subtitle = "NewJeans",
                imageUrl = null,
                isArtist = false,
                onClick = { plays++ },
                onMore = { overflow++ },
                selectionMode = chosen,
                selected = chosen,
                onToggleSelection = { chosen = !chosen; toggles++ },
            )
        }
        compose.onNodeWithContentDescription("Opções de Hurt").performClick()
        compose.runOnIdle { assertEquals(1, overflow) }
        compose.onNodeWithText("Hurt").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
        compose.runOnIdle { chosen = true }
        compose.onNodeWithContentDescription("Selecionar Hurt").performClick()
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(1, overflow) // selection mode has no overflow.
        }
    }

    @Test fun longPressSelectsNativeGridCardWithoutPlayingAndClickStillPlays() {
        var plays = 0
        var selections = 0
        compose.setContent {
            NativeLibraryEntityCard(
                title = "Hurt",
                subtitle = "NewJeans",
                imageUrl = null,
                isArtist = false,
                onClick = { plays++ },
                onLongPress = { selections++ },
            )
        }
        compose.onNodeWithText("Hurt").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(0, plays)
        }
        compose.onNodeWithText("Hurt").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun longPressWorksOnTheExistingNativeLibraryRow() {
        var opened = 0
        var selected = 0
        compose.setContent {
            LibraryRow(
                title = "Magnetic",
                subtitle = "ILLIT",
                imageUrl = null,
                onClick = { opened++ },
                onLongPress = { selected++ },
            )
        }
        compose.onNodeWithText("Magnetic").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(0, opened)
        }
    }

    @Test fun normalPlaylistPickerAndLibraryCoverUseSameOrderedDeduplicatedMembers() {
        assertEquals(
            listOf("rec-1", "rec-2", "rec-3", "rec-4"),
            canonicalStaticPlaylistCoverCandidateIds(
                listOf("rec-1", "rec-1", "rec-2", "rec-3", "rec-4", "rec-5"),
            ),
        )
        assertTrue(canonicalStaticPlaylistCoverCandidateIds(emptyList()).isEmpty())
    }

    @Test fun playlistCoverDoesNotRepeatTheSameAlbumArtInFourQuadrants() {
        val songs = (1..4).map { index ->
            V2DisplayRow(
                id = "recording:$index",
                title = "Song $index",
                artist = "SAY MY NAME",
                localArtworkUri = "content://art/same-album",
            )
        }
        assertEquals(1, distinctCanonicalPlaylistCoverRows(songs).size)
        assertEquals(4, distinctCanonicalPlaylistCoverRows(
            songs.mapIndexed { index, row ->
                row.copy(localArtworkUri = "content://art/$index")
            },
        ).size)
    }

    @Test fun canonicalHeaderReportsOnlyKnownTrackDurationWithoutGuessing() {
        val known = listOf(
            V2DisplayRow("rec-1", "A", "Artist", durationMs = 210000L),
            V2DisplayRow("rec-2", "B", "Artist", durationMs = 240000L),
        )
        assertEquals("Playlist · 2 faixas · 7min", canonicalPlaylistDetailMeta(false, known))
        assertEquals("Playlist inteligente · 2 faixas",
            canonicalPlaylistDetailMeta(true, known.dropLast(1) + known.last().copy(durationMs = null)))
    }

    @Test fun recentLocalMetadataFallsBackToRealFileNameButNeverAnArtistOnlyLabel() {
        assertEquals("Hurt", recentLocalPreferredDisplayTitle(
            canonicalTitle = "NewJeans", artist = "NewJeans", releaseTitle = null,
            mediaStoreTitle = null, mediaStoreAlbum = null, displayName = "Hurt.flac",
        ))
        assertEquals("Get Up", recentLocalPreferredDisplayTitle(
            canonicalTitle = "NewJeans", artist = "NewJeans", releaseTitle = "Get Up",
            mediaStoreTitle = null, mediaStoreAlbum = null, displayName = null,
        ))
        assertEquals("Título indisponível", recentLocalPreferredDisplayTitle(
            canonicalTitle = "NewJeans", artist = "NewJeans", releaseTitle = null,
            mediaStoreTitle = null, mediaStoreAlbum = null, displayName = null,
        ))
    }

    @Test fun recentAdditionUsesVerifiedAlbumWhenRecordingTitleIsOnlyArtistName() {
        assertEquals("Get Up", preferRecentLocalAdditionTitle("NewJeans", "NewJeans", "Get Up"))
        assertEquals("Hurt", preferRecentLocalAdditionTitle("Hurt", "NewJeans", "Get Up"))
        assertEquals("NewJeans", preferRecentLocalAdditionTitle("NewJeans", "NewJeans", null))
    }

    @Test fun recentAdditionsHeaderAndCanonicalTrackMetadataRemainVisible() {
        var playedRecordingId: String? = null
        compose.setContent {
            CanonicalRecentAdditionsShelf(
                recent = listOf(V2DisplayRow(
                    id = "recording:one", title = "Hurt", artist = "NewJeans",
                )),
                onPlayTrack = { playedRecordingId = it },
            )
        }
        compose.onNodeWithText("Adições recentes").assertExists()
        compose.onNodeWithText("Hurt").assertExists()
        compose.onNodeWithText("NewJeans").assertExists()
        compose.onNodeWithText("Hurt").performClick()
        compose.runOnIdle { assertEquals("recording:one", playedRecordingId) }
    }

    @Test fun recentAdditionsShowFallbackInsteadOfBlankTitle() {
        compose.setContent {
            CanonicalRecentAdditionsShelf(
                recent = listOf(V2DisplayRow(
                    id = "recording:missing-title", title = " ", artist = "NewJeans",
                )),
                onPlayTrack = {},
            )
        }
        compose.onNodeWithText("Título indisponível").assertExists()
        compose.onNodeWithText("NewJeans").assertExists()
    }

    @Test fun unavailableCanonicalPlaylistMemberHasNoFakePlaybackAction() {
        var clicked = false
        compose.setContent {
            CanonicalPlaylistTrackRow(
                track = V2DisplayRow(
                    id = "recording:missing",
                    title = "Gravação indisponível",
                    artist = "Fonte não disponível",
                ),
                onPlay = { clicked = true },
                onQueue = { _, _ -> clicked = true },
            )
        }
        compose.onNodeWithText("Gravação indisponível").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Ações da música").assertDoesNotExist()
        compose.runOnIdle { assertEquals(false, clicked) }
    }

    @Test fun playlistDetailTrackTapPreservesExactCanonicalRecordingId() {
        var clicked: String? = null
        compose.setContent {
            CanonicalPlaylistTrackRow(
                track = V2DisplayRow(
                    id = "recording:exact-27", title = "ASAP", artist = "NewJeans",
                ),
                onPlay = { clicked = it },
            )
        }
        compose.onNodeWithText("ASAP").performClick()
        compose.runOnIdle { assertEquals("recording:exact-27", clicked) }
    }

    @Test fun longPressOnCanonicalPlaylistTrackOpensSameMenuAsThreeDots() {
        compose.setContent {
            CanonicalPlaylistTrackRow(
                track = V2DisplayRow(
                    id = "recording:longpress-1",
                    title = "Long Press Track",
                    artist = "Artist",
                ),
                onPlay = {},
                onQueue = { _, _ -> },
            )
        }
        compose.onNodeWithText("Long Press Track").performTouchInput { longClick() }
        compose.onNodeWithText("Reproduzir daqui").assertExists()
        compose.onNodeWithText("Adicionar à fila").assertExists()
    }

    @Test fun playlistTrackMenuQueuesOnlyExplicitSelectedCanonicalRecording() {
        var queued: Pair<String, com.music.bitchord.playback.CanonicalCollectionIntent>? = null
        compose.setContent {
            CanonicalPlaylistTrackRow(
                track = V2DisplayRow(
                    id = "recording:verified-28", title = "Bubble Gum", artist = "NewJeans",
                ),
                onPlay = {},
                onQueue = { id, intent -> queued = id to intent },
            )
        }
        compose.onNodeWithContentDescription("Ações da música").performClick()
        compose.waitForIdle()
        // Target the clickable ActionRow, never the label or sheet container.
        compose.onNodeWithTag("canonical-track-queue-action")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(
                "recording:verified-28" to
                    com.music.bitchord.playback.CanonicalCollectionIntent.ADD_TO_QUEUE,
                queued,
            )
        }
    }

    @Test fun nativePlaylistMenuShowsExplicitCoverEvenWhenSmartPlaylistHasNoSongs() {
        val saved = LibraryPlaylistCard.Smart(
            com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition(
                id = "empty-smart",
                name = "Coleção sem faixas",
                query = com.music.bitchord.data.listening.SmartPlaylistV2Query(
                    com.music.bitchord.data.listening.SmartPlaylistRule.Comparison(
                        com.music.bitchord.data.listening.SmartPlaylistRuleField.TITLE,
                        com.music.bitchord.data.listening.SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                ),
                coverUri = "content://media/document/saved-cover",
            ),
        )
        compose.setContent {
            com.music.bitchord.ui.components.BrowseActionsSheet(
                target = com.music.bitchord.ui.components.BrowseTarget(
                    browseId = null, title = saved.title,
                    subtitle = "Playlist inteligente · 0 faixas",
                    type = com.music.bitchord.data.model.BrowseType.PLAYLIST,
                    fromCard = false,
                ),
                onPlayNext = {}, onAddToQueue = {},
                headerArtwork = {
                    CanonicalPlaylistCoverArt(
                        rows = emptyList(),
                        modifier = Modifier.fillMaxSize(),
                        customCoverUri = saved.explicitCoverUri(),
                    )
                },
                onEdit = {},
            )
        }
        compose.onNodeWithContentDescription("Capa personalizada").assertExists()
        compose.onNodeWithText("Coleção sem faixas").assertExists()
    }

    @Test fun nativeBrowseSheetReusesArtworkAndCanonicalEditAction() {
        var edited = 0
        var queuedNext = 0
        compose.setContent {
            com.music.bitchord.ui.components.BrowseActionsSheet(
                target = com.music.bitchord.ui.components.BrowseTarget(
                    browseId = null,
                    title = "Playlist canônica",
                    subtitle = "Playlist · 2 faixas",
                    type = com.music.bitchord.data.model.BrowseType.PLAYLIST,
                    fromCard = false,
                ),
                onPlayNext = { queuedNext++ },
                onAddToQueue = {},
                headerArtwork = { Box(Modifier.size(52.dp)) },
                onEdit = { edited++ },
            )
        }
        compose.onNodeWithText("Playlist canônica").assertExists()
        compose.onNodeWithText("Editar playlist").performClick()
        compose.onNodeWithText(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getString(com.music.bitchord.R.string.play_next),
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, edited)
            assertEquals(1, queuedNext)
        }
    }

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

    @Test fun conditionCategoriesUseSevenNativeCardsInsteadOfOneCompactBlock() {
        compose.setContent {
            PlaylistWizardNavigationList(
                (1..7).map { n ->
                    PlaylistWizardNavOption(
                        title = "Categoria $n",
                        description = "Descrição $n",
                        icon = androidx.compose.material.icons.Icons.Rounded.LibraryMusic,
                        onClick = {},
                    )
                },
            )
        }
        compose.onAllNodesWithTag("native-settings-group-surface").assertCountEquals(7)
        compose.onNodeWithText("Categoria 7").assertExists()
    }

    @Test fun explicitSavedCoverOverridesTheAutomaticCollageWithoutChangingCardIdentity() {
        val normal = LibraryPlaylistCard.CanonicalNormal(
            com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist(
                id = "normal-saved",
                name = "Estrelas",
                recordingIds = listOf("recording1"),
                coverUri = "content://media/document/3",
            ),
        )
        val smart = LibraryPlaylistCard.Smart(
            com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition(
                id = "smart-saved", name = "Recentes",
                query = com.music.bitchord.data.listening.SmartPlaylistV2Query(
                    com.music.bitchord.data.listening.SmartPlaylistRule.Comparison(
                        com.music.bitchord.data.listening.SmartPlaylistRuleField.TITLE,
                        com.music.bitchord.data.listening.SmartPlaylistRuleOperator.IS_PRESENT,
                    ),
                ),
                coverUri = "content://media/document/4",
            ),
        )
        assertEquals("content://media/document/3", normal.explicitCoverUri())
        assertEquals("content://media/document/4", smart.explicitCoverUri())
        assertEquals("normal:normal-saved", normal.id)
        assertEquals("v2:smart-saved", smart.id)
    }

    @Test fun playlistSynopsisUsesSameExpandableAboutComponentAsAlbums() {
        compose.setContent {
            val palette = com.music.bitchord.ui.theme.themeArtworkPalette()
            AboutSection(
                title = "Sobre a playlist",
                text = "Uma descrição longa que o usuário pode expandir.",
                palette = palette,
                horizontalPadding = 0.dp,
            )
        }
        compose.onNodeWithText("Sobre a playlist").assertExists()
        compose.onNodeWithText(
            "Uma descrição longa que o usuário pode expandir.",
        ).assertExists()
    }

    @Test fun normalPlaylistFiltersMenuOffersOnlyCompatibleActions() {
        val inNormal = savedFilterActionsForDraft(normalMode = true, editingNormalId = "normal-1")
        assertEquals(listOf(
            "Editar filtro", "Criar playlist com este filtro", "Excluir filtro",
        ), inNormal)
        val inSmart = savedFilterActionsForDraft(normalMode = false, editingNormalId = null)
        assertTrue("Smart drafts must offer applying rules", "Aplicar regras ao rascunho" in inSmart)
        assertTrue("Smart drafts must offer AND combination", "Combinar com o rascunho (E)" in inSmart)
    }

    @Test fun applyingReusableSmartRulesDoesNotConvertAnExistingNormalPlaylist() {
        assertEquals(true, canApplySmartFilterToDraft(
            normalMode = false, editingNormalId = null,
        ))
        assertEquals(false, canApplySmartFilterToDraft(
            normalMode = true, editingNormalId = "normal-1",
        ))
        assertEquals(false, canApplySmartFilterToDraft(
            normalMode = false, editingNormalId = "normal-1",
        ))
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

    @Test fun reviewLibraryRowHasNoFakePlaybackClickAction() {
        compose.setContent {
            LibraryRow(
                title = "Apenas revisão",
                subtitle = "Artista",
                imageUrl = null,
                horizontalPadding = 0.dp,
                artwork = { Box(Modifier.size(52.dp)) },
            )
        }
        compose.onNodeWithText("Apenas revisão").assertExists()
        compose.onNodeWithText("Apenas revisão").assertHasNoClickAction()
    }

    @Test fun playlistDescriptionUsesNativeMultilineFieldAndPreservesLineBreaks() {
        var description by mutableStateOf("")
        compose.setContent {
            PlaylistWizardTextEntry(
                value = description,
                onValueChange = { description = it },
                label = "Descrição (opcional)",
                multiline = true,
            )
        }
        compose.onNodeWithContentDescription("Descrição (opcional)")
            .performTextInput("Primeiro verso\\nSegundo verso")
        compose.runOnIdle { assertEquals("Primeiro verso\\nSegundo verso", description) }
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
