package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.music.bitchord.playback.CanonicalCollectionIntent
import org.junit.Assert.assertEquals
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The canonical recent card really follows the same fixed-width shelf geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRecentCardsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recentLocalCardLongPressOpensItsRealCanonicalActions() {
        var result: Pair<String, CanonicalCollectionIntent>? = null
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsShelf(
                    recent = listOf(V2DisplayRow(id = "recording-id", title = "Recent", artist = "Artist")),
                    onPlayTrack = {},
                    onTrackAction = { id, action -> result = id to action },
                )
            }
        }
        compose.onNodeWithTag("canonical-recent-card", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("Tocar a seguir").performClick()
        compose.runOnIdle {
            assertEquals("recording-id" to CanonicalCollectionIntent.ADD_NEXT, result)
        }
    }

    @Test fun recentLocalFullListUsesTheRowLongPressAndKeepsTapPlaybackSeparate() {
        var queued: Pair<String, CanonicalCollectionIntent>? = null
        var played = 0
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsGrid(
                    recent = listOf(V2DisplayRow(id = "local-recording", title = "Recent song", artist = "Artist")),
                    onPlayTrack = { played++ },
                    viewType = com.music.bitchord.data.settings.LibraryViewType.LIST,
                    onTrackAction = { id, action -> queued = id to action },
                )
            }
        }
        compose.onNodeWithText("Recent song").performTouchInput { longClick() }
        compose.onNodeWithText("Adicionar à fila").performClick()
        compose.runOnIdle {
            assertEquals("local-recording" to CanonicalCollectionIntent.ADD_TO_QUEUE, queued)
            assertEquals(0, played)
        }
    }

    @Test fun recentRecordingCardSharesShelfWidthInsteadOfLegacy142Dp() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsShelf(
                    recent = listOf(V2DisplayRow(id = "one", title = "Test Track", artist = "Test Artist")),
                    onPlayTrack = {},
                )
            }
        }
        compose.onNodeWithTag("canonical-recent-card", useUnmergedTree = true)
            .assertWidthIsEqualTo(SHELF_CARD_WIDTH)
    }
    @Test fun expandedRecentSearchStartsBelowFloatingToolbar() {
        assertTrue(recentAdditionsSearchTopClearance >= 80.dp)
    }

    @Test fun recentShelfDelegatesHeldCardToGlobalNativePreview() {
        var openedId: String? = null
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsShelf(
                    recent = listOf(V2DisplayRow("verified-recording", "Recent song", "Artist")),
                    onPlayTrack = {},
                    onTrackAction = { _, _ -> },
                    onTrackHold = { openedId = it.id },
                )
            }
        }
        compose.onNodeWithTag("canonical-recent-card", useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("verified-recording", openedId) }
        compose.onNodeWithText("Tocar a seguir").assertDoesNotExist()
    }

    @Test fun recentGridDelegatesHoldWithoutOpeningLegacyPopup() {
        var openedId: String? = null
        compose.setContent {
            MaterialTheme {
                CanonicalRecentAdditionsGrid(
                    recent = listOf(V2DisplayRow("recording", "Held track", "Artist")),
                    onPlayTrack = {},
                    onTrackAction = { _, _ -> },
                    onTrackHold = { openedId = it.id },
                    viewType = com.music.bitchord.data.settings.LibraryViewType.LIST,
                )
            }
        }
        compose.onNodeWithText("Held track").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("recording", openedId) }
        compose.onNodeWithText("Tocar a seguir").assertDoesNotExist()
    }

}
