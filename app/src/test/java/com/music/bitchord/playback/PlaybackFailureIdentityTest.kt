package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackFailureIdentityTest {

    @Test
    fun `repeat all cycles share cascade identity but keep distinct playback occurrences`() {
        val logicalCycleOne = failureEntryKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 4,
            queueEntryId = "physical-one",
            physicalIndex = 2,
        )
        val logicalCycleTwo = failureEntryKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 4,
            queueEntryId = "physical-two",
            physicalIndex = 11,
        )
        val occurrenceCycleOne = failureOccurrenceKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 4,
            contextCycleId = 7L,
            queueEntryId = "physical-one",
            physicalIndex = 2,
        )
        val occurrenceCycleTwo = failureOccurrenceKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 4,
            contextCycleId = 8L,
            queueEntryId = "physical-two",
            physicalIndex = 11,
        )

        assertEquals(logicalCycleOne, logicalCycleTwo)
        assertNotEquals(occurrenceCycleOne, occurrenceCycleTwo)
    }

    @Test
    fun `same physical queue occurrence keeps same audible identity across retry`() {
        val firstOpen = failureOccurrenceKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            contextCycleId = null,
            queueEntryId = "user-entry-42",
            physicalIndex = 3,
        )
        val retry = failureOccurrenceKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            contextCycleId = null,
            queueEntryId = "user-entry-42",
            physicalIndex = 3,
        )

        assertEquals(firstOpen, retry)
    }

    @Test
    fun `duplicate queue entry ids at different physical slots are distinct playback occurrences`() {
        val firstOccurrence = failureOccurrenceKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            contextCycleId = null,
            queueEntryId = "duplicate-entry",
            physicalIndex = 1,
        )
        val secondOccurrence = failureOccurrenceKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            contextCycleId = null,
            queueEntryId = "duplicate-entry",
            physicalIndex = 2,
        )

        assertNotEquals(firstOccurrence, secondOccurrence)
    }

    @Test
    fun `occurrence identity remains credential free`() {
        val key = failureOccurrenceKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 9,
            contextCycleId = 3L,
            queueEntryId = "https://should-not-win.invalid/rest/stream.view?token=secret",
            physicalIndex = 6,
        )

        assertEquals("context:9:cycle:3", key)
    }
}
