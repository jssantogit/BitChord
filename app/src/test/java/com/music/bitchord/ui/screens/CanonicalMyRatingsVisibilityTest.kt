package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import com.music.bitchord.data.listening.CanonicalRatedLibrarySnapshot
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** L14: reproduce invisible page chrome without starting Room or a provider. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalMyRatingsVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyRoomStillShowsHeaderCloseAndEmptyMessageBelowFloatingChrome() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    onBack = { backs++ },
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("My ratings").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("My ratings").fetchSemanticsNode().boundsInRoot.top >= 72f)
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.")
            .assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun populatedRoomKeepsFractionalLocalRatingAndCanonicalMetadataVisible() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Content(
                        items = listOf(MyRatingsRow("canonical-rec-a", 4.5, "Track A", "Artist A")),
                        hasMore = false,
                    ),
                    onBack = {},
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("My ratings").assertIsDisplayed()
        compose.onNodeWithText("Track A").assertIsDisplayed()
        compose.onNodeWithText("Artist A").assertIsDisplayed()
        compose.onNodeWithText("Rating: 4.5").assertIsDisplayed()
    }

    @Test fun failedRoomReadShowsErrorInsteadOfFalseEmptyState() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Error,
                    onBack = {},
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("My ratings").assertIsDisplayed()
        compose.onNodeWithText("Could not load ratings. Try reopening this page.").assertIsDisplayed()
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.")
            .assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun pendingRoomReadIsLoadingRatherThanEmpty() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Loading,
                    onBack = {},
                    onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Loading ratings…").assertIsDisplayed()
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.")
            .assertDoesNotExist()
    }

    @Test fun smartPlaylistHeaderStaysBelowChromeAndBackWorks() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                ListeningLibraryPage {
                    SmartPlaylistBuilderHeader(onBack = { backs++ })
                }
            }
        }
        compose.onNodeWithText("Smart playlists").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Smart playlists").fetchSemanticsNode().boundsInRoot.top >= 72f)
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun failedRoomFlowIsPresentedAsErrorWithoutCrashingComposition() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsScreen(
                    onBack = {},
                    ratingFeedOverride = flow<CanonicalRatedLibrarySnapshot> {
                        throw IllegalStateException("simulated Room read failure")
                    },
                )
            }
        }
        compose.onNodeWithText("Could not load ratings. Try reopening this page.")
            .assertIsDisplayed()
    }

    @Test fun emptyRoomFlowEmitsEmptyStateAndCloseControl() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsScreen(
                    onBack = {},
                    ratingFeedOverride = flowOf(CanonicalRatedLibrarySnapshot(emptyList(), false)),
                )
            }
        }
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.")
            .assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
