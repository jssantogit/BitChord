package com.music.bitchord.ui.screens

import android.app.Application
import com.music.bitchord.playback.CanonicalCollectionIntent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.listening.AdaptiveCollectionItem
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.listening.AdaptiveCollectionPreview
import com.music.bitchord.data.listening.AdaptiveCollectionReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AdaptiveCollectionsLibraryTest {
    @get:Rule val compose = createComposeRule()

    private fun snapshots(vararg items: AdaptiveCollectionItem) =
        AdaptiveCollectionKind.entries.map { kind ->
            AdaptiveCollectionPreview(kind, items.filter {
                when (kind) {
                    AdaptiveCollectionKind.REDISCOVERY ->
                        AdaptiveCollectionReason.CONFIRMED_AUDIBLE_ABSENCE in it.reasons
                    AdaptiveCollectionKind.EXPLICIT_POSITIVES ->
                        AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING in it.reasons
                    AdaptiveCollectionKind.CONTROLLED_EXPLORATION ->
                        AdaptiveCollectionReason.EXPLORATION_PROMOTION in it.reasons
                }
            })
        }

    private fun item(id: String, reason: AdaptiveCollectionReason) =
        AdaptiveCollectionItem(id, 1, 4.0, setOf(reason))

    @Test fun smartCollectionDoesNotPlayOrEnqueueOnDisplayOnlyOnExplicitAction() {
        val actions = mutableListOf<CanonicalCollectionIntent>()
        val state = AdaptiveCollectionsUiState.Ready(listOf(
            AdaptiveCollectionRows(AdaptiveCollectionKind.EXPLICIT_POSITIVES, listOf(
                AdaptiveCollectionRow("rec-a", "Song A", "Artist",
                    AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING),
            )),
        ))
        compose.setContent {
            AdaptiveCollectionsContent(
                state = state, onRecordingClick = {},
                onCollectionAction = { _, intent -> actions += intent },
            )
        }
        compose.runOnIdle { assertEquals(emptyList<CanonicalCollectionIntent>(), actions) }
        compose.onNodeWithText("Add to queue").performClick()
        compose.runOnIdle { assertEquals(listOf(CanonicalCollectionIntent.ADD_TO_QUEUE), actions) }
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CanonicalCollectionIntent.ADD_TO_QUEUE,
                CanonicalCollectionIntent.PLAY_NOW), actions)
        }
    }

    @Test fun previewsHaveExactlyThreeCollectionsAndKeepCanonicalOrder() = runTest {
        val reader = AdaptiveCollectionsLibraryReader(
            preview = { snapshots(
                item("rec-a", AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING),
                item("rec-b", AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING),
            ) },
            availability = CanonicalLibraryAvailabilityReader { listOf("rec-a", "rec-b") },
            recording = { id -> RecordingEntity(id, "Title $id", "Artist", 100_000L) },
            currentRating = { null },
        )
        val state = reader.load()
        assertTrue(state is AdaptiveCollectionsUiState.Ready)
        assertEquals(listOf("rec-a", "rec-b"),
            (state as AdaptiveCollectionsUiState.Ready).collections
                .first { it.kind == AdaptiveCollectionKind.EXPLICIT_POSITIVES }.rows.map { it.recordingId })
        assertEquals(3, state.collections.size)
    }

    @Test fun navigationRevalidatesAvailabilityInsteadOfTrustingRenderedSnapshot() = runTest {
        var available = listOf("rec-a")
        val reader = AdaptiveCollectionsLibraryReader(
            preview = { snapshots(item("rec-a", AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING)) },
            availability = CanonicalLibraryAvailabilityReader { available },
            recording = { id -> RecordingEntity(id, "Title", "Artist", 100_000L) },
            currentRating = { null },
        )
        assertEquals("rec-a", reader.resolveForNavigation(AdaptiveCollectionKind.EXPLICIT_POSITIVES, "rec-a")?.recordingId)
        available = emptyList()
        assertNull(reader.resolveForNavigation(AdaptiveCollectionKind.EXPLICIT_POSITIVES, "rec-a"))
    }

    @Test fun invalidNegativePreferenceFailsClosedRatherThanRendering() = runTest {
        val reader = AdaptiveCollectionsLibraryReader(
            preview = { snapshots(item("rec-a", AdaptiveCollectionReason.EXPLORATION_PROMOTION)) },
            availability = CanonicalLibraryAvailabilityReader { listOf("rec-a") },
            recording = { id -> RecordingEntity(id, "Title", "Artist", 100_000L) },
            currentRating = { id -> com.music.bitchord.data.listening.RecordingRating(id, 1.0, 1L) },
        )
        assertTrue(reader.load() is AdaptiveCollectionsUiState.Error)
    }

    @Test fun composeShowsReasonsAndOnlyEmitsExplicitCanonicalClick() {
        val clicks = mutableListOf<String>()
        val ready = AdaptiveCollectionsUiState.Ready(
            listOf(AdaptiveCollectionKind.REDISCOVERY,
                AdaptiveCollectionKind.EXPLICIT_POSITIVES,
                AdaptiveCollectionKind.CONTROLLED_EXPLORATION).map { kind ->
                AdaptiveCollectionRows(kind,
                    if (kind == AdaptiveCollectionKind.EXPLICIT_POSITIVES)
                        listOf(AdaptiveCollectionRow("rec-a", "Title A", "Artist A",
                            AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING)) else emptyList())
            },
        )
        compose.setContent {
            AdaptiveCollectionsContent(state = ready, onRecordingClick = { clicks += it })
        }
        compose.onNodeWithText("Explicit favorites").assertExists()
        compose.onNodeWithText("Title A").performClick()
        compose.runOnIdle { assertEquals(listOf("rec-a"), clicks) }
    }
}
