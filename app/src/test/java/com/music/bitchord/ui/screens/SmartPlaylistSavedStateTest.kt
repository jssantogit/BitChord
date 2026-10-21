package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.listening.SavedSmartPlaylistDefinition
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** L15/L18: Room query failure cannot masquerade as no saved smart playlists. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistSavedStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSavedDefinitionsFlowPublishesErrorNotEmpty() = runTest {
        val seen = mutableListOf<SmartPlaylistSavedUiState>()
        collectSmartPlaylistSavedState(
            updates = flow<List<SavedSmartPlaylistDefinition>> {
                throw IllegalStateException("simulated Room failure")
            },
            publish = { seen += it },
        )
        assertEquals(listOf(SmartPlaylistSavedUiState.Error), seen)
    }

    @Test fun emptyRoomFlowProducesExplicitEmptyState() = runTest {
        val seen = mutableListOf<SmartPlaylistSavedUiState>()
        collectSmartPlaylistSavedState(flowOf(emptyList()), { seen += it })
        assertEquals(listOf(SmartPlaylistSavedUiState.Empty), seen)
    }

    @Test fun populatedRoomFlowPreservesDefinitions() = runTest {
        val definition = SavedSmartPlaylistDefinition(
            id = "canonical-rule-id",
            name = "My rule",
            query = SmartPlaylistEditorDraft(minimumRating = "4").toQuery(),
        )
        val seen = mutableListOf<SmartPlaylistSavedUiState>()
        collectSmartPlaylistSavedState(flowOf(listOf(definition)), { seen += it })
        assertEquals(
            listOf(SmartPlaylistSavedUiState.Content(listOf(definition))),
            seen,
        )
    }

    @Test fun failedSavedDefinitionsHasVisibleErrorNotFalseEmptyState() {
        compose.setContent {
            MaterialTheme { SmartPlaylistSavedStatus(SmartPlaylistSavedUiState.Error) }
        }
        compose.onNodeWithText("Could not load saved playlists").assertIsDisplayed()
        compose.onNodeWithText("No saved playlists yet").assertDoesNotExist()
    }

    @Test fun savedDefinitionsPendingShowsLoadingNotFalseEmptyState() {
        compose.setContent {
            MaterialTheme { SmartPlaylistSavedStatus(SmartPlaylistSavedUiState.Loading) }
        }
        compose.onNodeWithText("Loading saved playlists…").assertIsDisplayed()
        compose.onNodeWithText("Could not load saved playlists").assertDoesNotExist()
    }
}
