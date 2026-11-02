package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Native Library filter action: the toolbar opens one sheet; no separate
 * in-list buttons or second filter navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeCanonicalLibraryFiltersComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previousToolbarRequestIsNotReplayedWhenReturningFromArtistOrAlbumDetail() {
        // In the real Library, opening a detail unmounts this sheet; returning
        // recreates it with the toolbar's existing, nonzero request counter.
        val actionToken = mutableIntStateOf(2)
        val source = mutableStateOf<String?>(null)
        val stars = mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                NativeCanonicalLibraryFilters(
                    availableKeys = listOf("local-mediastore", "opensubsonic:account"),
                    selectedSource = source.value,
                    onSourceChange = { source.value = it },
                    selectedStars = stars.value,
                    onStarsChange = { stars.value = it },
                    requestToken = actionToken.intValue,
                )
            }
        }
        compose.onNodeWithText("Filtrar biblioteca").assertDoesNotExist()
        // Only a fresh click on the existing toolbar filter icon may open it.
        compose.runOnIdle { actionToken.intValue++ }
        compose.onNodeWithText("Filtrar biblioteca").assertExists()
    }

    @Test fun libraryFilterIsHiddenUntilNativeToolbarRequestAndScopesSource() {
        val actionToken = mutableIntStateOf(0)
        val source = mutableStateOf<String?>(null)
        val stars = mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                NativeCanonicalLibraryFilters(
                    availableKeys = listOf("local-mediastore", "opensubsonic:account"),
                    selectedSource = source.value,
                    onSourceChange = { source.value = it },
                    selectedStars = stars.value,
                    onStarsChange = { stars.value = it },
                    requestToken = actionToken.intValue,
                )
            }
        }
        compose.onNodeWithText("Fonte: Todas").assertDoesNotExist()
        compose.onNodeWithText("Avaliação: Todas").assertDoesNotExist()
        compose.onNodeWithText("Filtrar biblioteca").assertDoesNotExist()
        compose.runOnIdle { actionToken.intValue++ }
        compose.onNodeWithText("Filtrar biblioteca").assertExists()
        // Invoke the row's semantic action directly: Robolectric cannot
        // reliably hit a moving ModalBottomSheet during its entrance animation.
        // A physical touch on Galaxy A54 remains a separate smoke gate.
        compose.onNodeWithTag("library-source-local-mediastore")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("local-mediastore", source.value) }
    }
}
