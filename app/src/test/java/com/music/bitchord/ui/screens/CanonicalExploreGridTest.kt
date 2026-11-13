package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.music.bitchord.ui.LocalReduceAnimations
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The original native Explore grid is reused for canonical genre categories. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalExploreGridTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchResultsKeepHoldActionsButNoMoreButtonOrDurationText() {
        compose.setContent {
            MaterialTheme {
                NativeSearchResultRow(
                    row = NativeSearchRowUi(
                        title = "How Sweet",
                        subtitle = "Música · NewJeans",
                        artworkUri = null,
                        artistPortrait = false,
                        durationLabel = "3:20",
                    ),
                    onClick = {},
                    onMoreActions = listOf(NativeSearchRowAction("Ver detalhes") {}),
                )
            }
        }
        compose.onNodeWithText("How Sweet").assertExists()
        compose.onNodeWithText("3:20").assertDoesNotExist()
        compose.onNodeWithTag("search-row-more-actions").assertDoesNotExist()
        compose.onNodeWithTag("native-search-result-row")
            .performTouchInput { longClick() }
        compose.onNodeWithText("Ver detalhes").assertExists()
    }

    @Test fun libraryCategoryBackIsIndependentlyCompactAndKeepsItsAction() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.ArtworkPageBackButton(
                    onClick = { backs++ },
                    hazeState = null,
                    isCategory = true,
                )
            }
        }
        compose.onNodeWithTag("apple-category-back-button").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun appleSearchOriginTabsSupportCatalogAndAvailableLocalWithoutServer() {
        var selected = -1
        var backs = 0
        var submits = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    AppleSearchOriginTabs(
                        options = listOf("Catálogo", "Local"),
                        selectedIndex = 0,
                        onSelect = { selected = it },
                    )
                    com.music.bitchord.ui.components.SearchField(
                        query = "newjeans",
                        onQueryChange = {},
                        onSubmit = { submits++ },
                        placeholder = "Artistas, músicas e mais",
                        appleSearchChrome = true,
                        onBack = { backs++ },
                    )
                }
            }
        }
        // The Apple search origin row intentionally renders localized labels in uppercase.
        compose.onNodeWithText("CATÁLOGO").assertExists()
        compose.onNodeWithText("LOCAL").performClick()
        compose.onNodeWithText("SERVIDOR").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("apple-search-back").performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, submits)
        }
    }


    @Test fun submittedResultsHaveVisibleBlueUnderlineWithHorizontalScrolling() {
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    query = "newjeans",
                    onQueryChange = {},
                    filter = SearchFilter.ALL,
                    currentSong = null,
                    isPlaying = false,
                    onFilterChange = {},
                    results = null,
                    loadingMore = false,
                    onLoadMore = {},
                    listState = rememberLazyListState(),
                    scrollResetTrigger = 0,
                    focusRequested = false,
                    onFocusHandled = {},
                    onSongClick = { _, _ -> },
                    onSongLongPress = {},
                    onSongSwipe = {},
                    onTopResultPlay = {},
                    onTopResultPlaylist = {},
                    onBrowseClick = {},
                    history = emptyList(),
                    suggestions = emptyList(),
                    typeaheadResults = emptyList(),
                    onSubmit = {},
                    onSuggestionClick = {},
                    onHistoryClick = {},
                    onHistoryRemove = {},
                    onHistoryClear = {},
                    contentPadding = PaddingValues(0.dp),
                    topPadding = 0.dp,
                    showField = false,
                    searchingCanonical = true,
                    canonicalResults = UiState.Success(emptyList<CanonicalCatalogSearchRow>()),
                    searchCommitted = true,
                    appleResultTabs = true,
                )
            }
        }
        val indicator = compose.onNodeWithTag("apple-selected-result-indicator", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue("Submitted result underline must have width", indicator.boundsInRoot.width > 1f)
    }

    @Test fun reducedMotionSourceTabsStillChangeSelectionWithoutAnimation() {
        compose.setContent {
            var selected by androidx.compose.runtime.remember { mutableIntStateOf(0) }
            CompositionLocalProvider(LocalReduceAnimations provides true) {
                MaterialTheme {
                    AppleSearchOriginTabs(
                        options = listOf("Catálogo", "Local", "Servidor"),
                        selectedIndex = selected,
                        onSelect = { selected = it },
                    )
                }
            }
        }
        compose.onNodeWithText("LOCAL").performClick()
        compose.onNodeWithTag("apple-selected-origin-indicator", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("SERVIDOR").performClick()
        compose.onNodeWithTag("apple-selected-origin-indicator", useUnmergedTree = true).assertExists()
    }

    @Test fun threeOrMoreSearchSourcesKeepTheirSelectedUnderline() {
        compose.setContent {
            MaterialTheme {
                AppleSearchOriginTabs(
                    options = listOf("Catálogo", "Local", "Servidor remoto"),
                    selectedIndex = 2,
                    onSelect = {},
                )
            }
        }
        val indicator = compose.onNodeWithTag("apple-selected-origin-indicator", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue("Scrollable source tabs must have width", indicator.boundsInRoot.width > 1f)
    }

    @Test fun exploreAndLibraryShareCompactSettingsOnlyOverflow() {
        var openedSettings = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.AppleRootSettingsOverflow(
                    hazeState = null,
                    description = "Mais opções de Explorar",
                    onSettings = { openedSettings++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Mais opções de Explorar").performClick()
        compose.onNodeWithTag("apple-root-settings-popup").assertExists()
        compose.onNodeWithTag("apple-root-settings-item").assertExists()
        compose.onNodeWithText("Editar Fixadas").assertDoesNotExist()
        compose.onNodeWithText("Conta").assertDoesNotExist()
        compose.onNodeWithTag("apple-root-settings-item").performClick()
        compose.runOnIdle { assertEquals(1, openedSettings) }
        compose.onNodeWithTag("apple-root-settings-popup").assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun exploreKeepsOnlyCatalogGenresWithoutSearchOrEditorialEntries() {
        val genres = listOf(
            ShelfItem("Metal", "", null, null, "catalog:GENRE:metal"),
            ShelfItem("K-pop", "", null, null, "catalog:GENRE:k-pop"),
        )
        compose.setContent {
            MaterialTheme {
                ExploreScreen(
                    state = UiState.Success(emptyList<MoodGenreSection>()),
                    listState = rememberLazyListState(),
                    onCategoryClick = {},
                    onRetry = {},
                    refreshing = false,
                    onRefresh = {},
                    pullState = rememberPullToRefreshState(),
                    contentPadding = PaddingValues(),
                    canonicalGenres = genres,
                )
            }
        }
        // Heading is localized via shared resources; do not assert a Portuguese literal.
        compose.onNodeWithText("Metal").assertExists()
        compose.onNodeWithText("K-pop").assertExists()
        compose.onNodeWithText("Essenciais").assertDoesNotExist()
        compose.onNodeWithText("Shows").assertDoesNotExist()
        compose.onNodeWithText("Cantar").assertDoesNotExist()
        compose.onNodeWithText("Artistas, Músicas, Letras e mais").assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun exploreRootOnlyShowsGenresAndOpensCanonicalGenreId() {
        val opened = mutableListOf<String>()
        val genres = listOf(
            ShelfItem("Pop", "", null, null, "catalog:GENRE:pop"),
            ShelfItem("Rock", "", null, null, "catalog:GENRE:rock"),
            ShelfItem("Jazz", "", null, null, "catalog:GENRE:jazz"),
        )
        compose.setContent {
            MaterialTheme {
                ExploreScreen(
                    state = UiState.Success(emptyList<MoodGenreSection>()),
                    listState = rememberLazyListState(),
                    onCategoryClick = {},
                    onRetry = {},
                    refreshing = false,
                    onRefresh = {},
                    pullState = rememberPullToRefreshState(),
                    contentPadding = PaddingValues(),
                    canonicalGenres = genres,
                    onCanonicalGenreClick = { opened += it.browseId.orEmpty() },
                )
            }
        }
        compose.onNodeWithText("Pop").assertExists()
        compose.onNodeWithText("Rock").assertExists()
        compose.onNodeWithText("Lançamentos").assertDoesNotExist()
        compose.onNodeWithText("Escolha a dedo").assertDoesNotExist()
        compose.onNodeWithText("Rock").performClick()
        compose.runOnIdle { assertEquals(listOf("catalog:GENRE:rock"), opened) }
    }
}
