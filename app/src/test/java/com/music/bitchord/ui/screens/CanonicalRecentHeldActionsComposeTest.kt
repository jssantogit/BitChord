package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class CanonicalRecentHeldActionsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recentLocalTrackOffersLibraryActionsBeyondQueue() {
        val invoked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CanonicalRecentHeldActions(
                    isPinned = false,
                    onPlayNext = { invoked += "next" },
                    onQueue = { invoked += "queue" },
                    onAddToPlaylist = { invoked += "playlist" },
                    onEditRating = { invoked += "rating" },
                    onTogglePin = { invoked += "pin" },
                )
            }
        }
        listOf("Tocar a seguir", "Adicionar à fila", "Adicionar à playlist",
            "Editar avaliação", "Fixar na Biblioteca").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Adicionar à playlist").performClick()
        compose.onNodeWithText("Editar avaliação").performClick()
        compose.onNodeWithText("Fixar na Biblioteca").performClick()
        compose.runOnIdle { assertEquals(listOf("playlist", "rating", "pin"), invoked) }
    }

    @Test fun pinnedRecordingShowsUnpinWithoutRemovingItsOtherActions() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecentHeldActions(
                    isPinned = true, onPlayNext = {}, onQueue = {},
                    onAddToPlaylist = {}, onEditRating = {}, onTogglePin = {},
                )
            }
        }
        compose.onNodeWithText("Desafixar da Biblioteca").assertExists()
        compose.onNodeWithText("Adicionar à playlist").assertExists()
    }
}
