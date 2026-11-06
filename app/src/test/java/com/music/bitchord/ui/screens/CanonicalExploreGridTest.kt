package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
