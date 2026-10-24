package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.provider.RemoteNumericRating
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.RemoteStarredKind
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerItem
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingCoverage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Listening L16–L18: an explicit local preference is NEVER an imported remote rating. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemoteRatingsPresentationTest {
    @get:Rule val compose = createComposeRule()

    private fun evidence(
        id: String = "rec-1",
        asset: String = "asset-1",
        numeric: Int? = 5,
        starred: RemoteStarredKind = RemoteStarredKind.NOT_PROVIDED,
    ) = RemoteRatingConsumerItem(
        recordingId = id,
        mediaAssetId = asset,
        providerKey = "opensubsonic:account",
        scopeId = "scope-instance",
        generation = 7,
        numeric = if (numeric == null) RemoteNumericRating() else
            RemoteNumericRating(RemoteNumericRatingKind.VALUE, numeric),
        starred = starred,
    )

    @Test fun remoteOnlyCanonicalRatingIsVisibleWhenLocalRoomIsEmpty() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    remoteState = RemoteRatingsUiState.Ready(listOf(
                        RemoteRatingUiRow("rec-1", "Remote track", "Remote artist", listOf(
                            RemoteRatingUiEvidence("asset-1", 4, false, false),
                        )),
                    )),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Remote track").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 4/5").assertIsDisplayed()
        compose.onNodeWithText("OpenSubsonic (remote)").assertIsDisplayed()
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.").assertDoesNotExist()
    }

    @Test fun localFractionalRatingAndRemoteIntegerConflictAreBothVisible() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Content(
                        items = listOf(MyRatingsRow("rec-1", 4.5, "Song A", "Artist A")),
                        hasMore = false,
                    ),
                    remoteState = RemoteRatingsUiState.Ready(listOf(
                        RemoteRatingUiRow("rec-1", "Song A", "Artist A", listOf(
                            RemoteRatingUiEvidence("asset-1", 5, false, false),
                        )),
                    )),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Rating: 4.5").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 5/5").assertIsDisplayed()
        compose.onNodeWithText("Local and remote ratings differ").assertIsDisplayed()
    }

    @Test fun starredWithoutNumericDoesNotBecomeFiveStars() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    remoteState = RemoteRatingsUiState.Ready(listOf(
                        RemoteRatingUiRow("rec-star", "Starred track", "Artist", listOf(
                            RemoteRatingUiEvidence("asset-star", null, false, true),
                        )),
                    )),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Starred on server (not a rating)").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 5/5").assertDoesNotExist()
    }

    @Test fun remoteUnavailableNeverHidesPersistedLocalFeedback() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Content(
                        listOf(MyRatingsRow("rec-1", 4.5, "Local track", "Artist")), false,
                    ),
                    remoteState = RemoteRatingsUiState.Unavailable,
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Rating: 4.5").assertIsDisplayed()
        compose.onNodeWithText("Remote ratings unavailable").assertIsDisplayed()
    }

    @Test fun twoRemoteAssetsForOneRecordingRemainSeparateEvidence() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    remoteState = RemoteRatingsUiState.Ready(listOf(
                        RemoteRatingUiRow("rec-1", "Track", "Artist", listOf(
                            RemoteRatingUiEvidence("asset-a", 3, false, false),
                            RemoteRatingUiEvidence("asset-b", 5, false, false),
                        )),
                    )),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Remote sources disagree").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 3/5").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 5/5").assertIsDisplayed()
    }

    @Test fun consumerGroupsByProvenCanonicalRecordingAndNeverByTitle() = runBlocking {
        val snapshot = RemoteRatingConsumerSnapshot(
            availability = RemoteRatingAvailability.CACHED_COMPLETE,
            coverage = RemoteRatingCoverage(enumerated = 3, matched = 3, valuedNumeric = 2),
            items = listOf(
                evidence(id = "rec-a", asset = "asset-a", numeric = 4),
                evidence(id = "rec-a", asset = "asset-a2", numeric = 5),
                evidence(id = "rec-b", asset = "asset-b", numeric = null,
                    starred = RemoteStarredKind.STARRED),
            ),
        )
        val grouped = presentRemoteRatings(snapshot) { id ->
            RecordingEntity(id, "Identical title", "Artist", 100_000L)
        }
        assertEquals(2, grouped.size)
        assertEquals(listOf("rec-a", "rec-b"), grouped.map { it.recordingId })
        assertEquals(2, grouped.first().evidence.size)
        assertEquals(4, grouped.first().evidence.first().numericValue)
        assertTrue(grouped.last().evidence.single().starred)
        assertEquals(null, grouped.last().evidence.single().numericValue)
    }

    @Test fun zeroIsRemoteClearedNotAnExplicitZeroStarRating() = runBlocking {
        val snapshot = RemoteRatingConsumerSnapshot(
            availability = RemoteRatingAvailability.CACHED_COMPLETE,
            coverage = RemoteRatingCoverage(enumerated = 1, matched = 1, clearedNumeric = 1),
            items = listOf(
                RemoteRatingConsumerItem(
                    recordingId = "rec-zero", mediaAssetId = "asset-zero",
                    providerKey = "opensubsonic:account", scopeId = "scope",
                    generation = 1,
                    numeric = RemoteNumericRating(RemoteNumericRatingKind.CLEARED_ZERO),
                    starred = RemoteStarredKind.NOT_STARRED,
                ),
            ),
        )
        val rows = presentRemoteRatings(snapshot) { null }
        assertEquals(1, rows.size)
        assertEquals(null, rows.single().evidence.single().numericValue)
        assertTrue(rows.single().evidence.single().cleared)
    }

    @Test fun brokenLocalRoomStillShowsAuthenticatedRemoteEvidenceAndLocalError() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Error,
                    remoteState = RemoteRatingsUiState.Ready(listOf(
                        RemoteRatingUiRow("rec-remote", "Remote available", "Artist", listOf(
                            RemoteRatingUiEvidence("asset-r", 4, false, false),
                        )),
                    )),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Could not load ratings. Try reopening this page.").assertIsDisplayed()
        compose.onNodeWithText("Remote rating: 4/5").assertIsDisplayed()
    }

    @Test fun partialCanonicalMatchingIsVisibleWithoutGuessingUnknownRecordings() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    remoteState = RemoteRatingsUiState.Ready(
                        rows = emptyList(),
                        coverage = RemoteRatingCoverage(enumerated = 3, matched = 1, unmatched = 2),
                    ),
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Some remote recordings could not be matched").assertIsDisplayed()
        compose.onNodeWithText("No ratings yet. Rate a canonical song from its menu.").assertIsDisplayed()
    }

    @Test fun accountReadFailureIsNotReportedAsAnEmptyRemoteLibrary() {
        compose.setContent {
            MaterialTheme {
                CanonicalMyRatingsContent(
                    state = MyRatingsUiState.Empty,
                    remoteState = RemoteRatingsUiState.Error,
                    onBack = {}, onEdit = {},
                )
            }
        }
        compose.onNodeWithText("Could not read remote ratings").assertIsDisplayed()
    }
}
