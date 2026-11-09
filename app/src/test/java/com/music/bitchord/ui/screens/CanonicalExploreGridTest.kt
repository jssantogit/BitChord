package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
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

    @Test fun libraryCategoryBackIsIndependentlyCompactAndKeepsItsAction() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                com.music.bitchord.ui.components.ArtworkPageBackButton(
                    onClick = { backs++ },
                    hazeState = null,
                    size = 40.dp,
                )
            }
        }
        compose.onNodeWithTag("apple-category-back-button").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun appleSearchOriginTabsPreserveAllThreeNativeSources() {
        var selected = -1
        var backs = 0
        var submits = 0
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    AppleSearchOriginTabs(
                        options = listOf("Geral", "Servidor", "Sua biblioteca"),
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
        compose.onNodeWithText("Servidor").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithContentDescription("Voltar da pesquisa").performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, submits)
        }
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
