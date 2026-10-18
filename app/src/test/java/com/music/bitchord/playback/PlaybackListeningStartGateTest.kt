package com.music.bitchord.playback

import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackListeningStartGateTest {
    private val recordingA = CanonicalPlaybackIdentity(
        recordingId = "recording-a",
        mediaAssetId = "asset-a",
        provider = "opensubsonic:home",
    )
    private val recordingB = CanonicalPlaybackIdentity(
        recordingId = "recording-b",
        mediaAssetId = "asset-b",
        provider = "local-mediastore",
    )

    @Test
    fun `failure before audible start emits no start and next audible item starts once`() {
        val gate = PlaybackListeningStartGate()

        gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.AUTOMATIC,
            origin = SelectionOrigin.QUEUE,
        )
        gate.onSelection(
            identity = recordingB,
            intent = SelectionIntent.AUTOMATIC,
            origin = SelectionOrigin.QUEUE,
        )

        val start = gate.onAudible(recordingB)

        assertNotNull(start)
        assertEquals(recordingB, start!!.identity)
        assertEquals(SelectionIntent.AUTOMATIC, start.intent)
        assertEquals(SelectionOrigin.QUEUE, start.origin)
        assertNull(gate.onAudible(recordingB))
    }

    @Test
    fun `selection transition by itself never produces a playback start`() {
        val gate = PlaybackListeningStartGate()

        val occurrence = gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.MANUAL,
            origin = SelectionOrigin.QUEUE,
        )

        assertEquals(1L, occurrence)
        assertNull(gate.lastStartForTest())
    }

    @Test
    fun `repeat creates a new occurrence but never duplicates one audible start`() {
        val gate = PlaybackListeningStartGate()

        val firstOccurrence = gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.AUTOMATIC,
            origin = SelectionOrigin.QUEUE,
        )
        val firstStart = requireNotNull(gate.onAudible(recordingA))
        assertNull(gate.onAudible(recordingA))
        gate.onPlaybackEnded(firstOccurrence)

        val replayOccurrence = gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.REPLAY,
            origin = SelectionOrigin.QUEUE,
        )
        val replayStart = requireNotNull(gate.onAudible(recordingA))

        assertNotEquals(firstStart.occurrenceId, replayStart.occurrenceId)
        assertEquals(replayOccurrence, replayStart.occurrenceId)
        assertEquals(SelectionIntent.REPLAY, replayStart.intent)
        assertNull(gate.onAudible(recordingA))
    }

    @Test
    fun `late end callback from previous occurrence cannot erase newer same recording selection`() {
        val gate = PlaybackListeningStartGate()

        val firstOccurrence = gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.AUTOMATIC,
            origin = SelectionOrigin.QUEUE,
        )
        requireNotNull(gate.onAudible(recordingA))

        val replayOccurrence = gate.onSelection(
            identity = recordingA,
            intent = SelectionIntent.REPLAY,
            origin = SelectionOrigin.QUEUE,
        )
        gate.onPlaybackEnded(firstOccurrence)

        val replayStart = requireNotNull(gate.onAudible(recordingA))
        assertEquals(replayOccurrence, replayStart.occurrenceId)
        assertEquals(SelectionIntent.REPLAY, replayStart.intent)
    }
}
