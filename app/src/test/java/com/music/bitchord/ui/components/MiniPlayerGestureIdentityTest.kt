package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A user can drag the mini while metadata hydrates or a remote queue skip
 * changes tracks. Only a real track change may interrupt the pointer stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MiniPlayerGestureIdentityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacingQueueEntryMidSwipeDoesNotSkipTheNewTrack() {
        val queueEntry = mutableStateOf("queue-first")
        var nextCalls = 0
        var previousCalls = 0
        val horizontalFeedback = mutableListOf<Pair<Float, Boolean>>()
        var horizontalCancels = 0
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.size(width = 320.dp, height = 120.dp)
                        .miniPlayerGestures(
                            pull = null,
                            onNext = { nextCalls++ },
                            onPrevious = { previousCalls++ },
                            gestureIdentity = queueEntry.value,
                            onHorizontalSwipe = { distance, active ->
                                horizontalFeedback += distance to active
                            },
                            onHorizontalCancel = { horizontalCancels++ },
                        )
                        .testTag("gesture-zone"),
                )
            }
        }
        compose.onNodeWithTag("gesture-zone").performTouchInput {
            down(center)
            moveBy(Offset(-center.x * 0.85f, 0f))
        }
        compose.runOnIdle { queueEntry.value = "queue-second" }
        compose.waitForIdle()
        compose.onNodeWithTag("gesture-zone").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, nextCalls)
            assertEquals(0, previousCalls)
            assertTrue("The interrupted swipe must have started", horizontalFeedback.any { it.second })
            assertEquals(
                "A cancelled pointer must end horizontal feedback, not leave it active",
                0f to false,
                horizontalFeedback.last(),
            )
            assertEquals("The gesture ends feedback exactly once", 1, horizontalFeedback.count { !it.second })
            assertEquals("The interrupted gesture must reset rather than settle", 1, horizontalCancels)
        }
    }

    @Test fun replacingQueueEntryDuringVerticalPullCancelsWithoutInferringAFling() {
        val queueEntry = mutableStateOf("queue-first")
        val deltas = mutableListOf<Float>()
        val releases = mutableListOf<Float>()
        var cancellations = 0
        val pull = object : MiniPlayerPull {
            override fun drag(delta: Float) { deltas += delta }
            override fun release(velocity: Float) { releases += velocity }
            override fun cancel() { cancellations++ }
        }
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.size(width = 320.dp, height = 120.dp)
                        .miniPlayerGestures(
                            pull = pull,
                            onNext = {},
                            onPrevious = {},
                            gestureIdentity = queueEntry.value,
                        )
                        .testTag("vertical-gesture-zone"),
                )
            }
        }
        compose.onNodeWithTag("vertical-gesture-zone").performTouchInput {
            down(center)
            moveBy(Offset(0f, -center.y * 0.85f))
        }
        compose.runOnIdle { queueEntry.value = "queue-second" }
        compose.waitForIdle()
        compose.onNodeWithTag("vertical-gesture-zone").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("The player received the initial upward drag", deltas.isNotEmpty())
            assertEquals("A cancelled pull must invoke cancel exactly once", 1, cancellations)
            assertTrue("Cancellation must not be interpreted as a fling release", releases.isEmpty())
        }
    }

    @Test fun updatingDisplayMetadataKeepsOneInFlightSwipeForSameQueueEntry() {
        val displayArtist = mutableStateOf("Original artist")
        var nextCalls = 0
        var horizontalCancels = 0
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier.size(width = 320.dp, height = 120.dp)
                        .miniPlayerGestures(
                            pull = null,
                            onNext = { nextCalls++ },
                            onPrevious = {},
                            gestureIdentity = "queue-first",
                            onHorizontalCancel = { horizontalCancels++ },
                        )
                        .testTag("gesture-zone"),
                ) {
                    Text(displayArtist.value)
                }
            }
        }
        compose.onNodeWithTag("gesture-zone").performTouchInput {
            down(center)
            moveBy(Offset(-center.x * 0.85f, 0f))
        }
        compose.runOnIdle { displayArtist.value = "Updated artist" }
        compose.waitForIdle()
        compose.onNodeWithTag("gesture-zone").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, nextCalls)
            assertEquals("A normal release must not trigger cancellation", 0, horizontalCancels)
        }
    }
}
