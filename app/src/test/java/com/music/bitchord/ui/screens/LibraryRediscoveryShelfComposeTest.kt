package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.listening.RediscoveryPresentationAvailability
import com.music.bitchord.data.listening.RediscoveryPresentationItem
import com.music.bitchord.data.listening.RediscoveryReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibraryRediscoveryShelfComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String, title: String, days: Long) = RediscoveryShelfRow(
        item = RediscoveryPresentationItem(
            recordingId = id, shelfPosition = 1, rankPosition = 8,
            lastAudibleListenAtEpochMs = 42L, lastInteractionAtEpochMs = null,
            timeSinceLastAudibleMs = days * 86_400_000L,
            daysSinceLastAudible = days,
            reasonCodes = listOf(RediscoveryReason.POSITIVE_EXPLICIT_PREFERENCE,
                RediscoveryReason.LONG_ABSENCE),
            availability = RediscoveryPresentationAvailability.AVAILABLE_AT_SNAPSHOT,
        ),
        title = title,
        artistDisplay = "Artist",
    )

    @Test fun emptyStateHidesShelfButLoadingIsAccessible() {
        val state = mutableStateOf<RediscoveryShelfUiState>(RediscoveryShelfUiState.Loading)
        compose.setContent { LibraryRediscoveryShelf(state.value, onRecordingClick = {}) }
        compose.onNodeWithText("Rediscover").assertExists()
        compose.onNodeWithText("Finding songs…").assertExists()
        compose.runOnIdle { state.value = RediscoveryShelfUiState.Empty }
        compose.onNodeWithText("Rediscover").assertDoesNotExist()
        compose.onNodeWithText("Finding songs…").assertDoesNotExist()
    }

    @Test fun recompositionKeepsCanonicalItemOrderAndClickSendsIdOnly() {
        val state = mutableStateOf<RediscoveryShelfUiState>(
            RediscoveryShelfUiState.Ready(listOf(row("rec-old", "Older Song", 70),
                row("rec-newer", "Another Song", 40))),
        )
        val clicks = mutableListOf<String>()
        compose.setContent { LibraryRediscoveryShelf(state.value, onRecordingClick = { clicks += it }) }
        compose.onNodeWithText("Older Song").assertExists()
        compose.onNodeWithText("70 days since last listen").assertExists()
        compose.onNodeWithText("Another Song").performClick()
        compose.runOnIdle { assertEquals(listOf("rec-newer"), clicks) }
        compose.runOnIdle {
            state.value = RediscoveryShelfUiState.Ready(listOf(row("rec-old", "Older Song", 70)))
        }
        compose.onNodeWithText("Older Song").assertExists()
        compose.onNodeWithText("Another Song").assertDoesNotExist()
    }

    @Test fun missingAssetNeverRendersStaleRecording() {
        val state = mutableStateOf<RediscoveryShelfUiState>(
            RediscoveryShelfUiState.Ready(listOf(row("rec-old", "Older Song", 70))),
        )
        compose.setContent { LibraryRediscoveryShelf(state.value, onRecordingClick = {}) }
        compose.onNodeWithText("Older Song").assertExists()
        compose.runOnIdle { state.value = RediscoveryShelfUiState.Empty }
        compose.onNodeWithText("Older Song").assertDoesNotExist()
    }
}
