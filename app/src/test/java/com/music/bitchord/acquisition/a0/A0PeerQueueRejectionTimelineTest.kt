package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0PeerQueueRejectionTimelineTest {
    @Test fun rejectsAreSplitAtExactSixAndTwelveSecondBoundaries() {
        val timeline=A0PeerQueueRejectionTimeline()
        for(millis in listOf(-1L,0L,5999L,6000L,11999L,12000L,18000L))
            timeline.rejectedAt(millis)
        val counters=timeline.snapshot()
        assertEquals(2,counters.before6)
        assertEquals(2,counters.sixTo12)
        assertEquals(2,counters.after12)
        assertEquals(6,counters.total)
    }

    @Test fun zeroRejectionsAndBoundariesNeverRequireUserIdentifiers() {
        val timeline=A0PeerQueueRejectionTimeline()
        assertEquals(0,timeline.snapshot().total)
        timeline.rejectedAt(12_001L)
        assertEquals(1,timeline.snapshot().after12)
        assertFalse(timeline.snapshot().toString().contains("peer",ignoreCase=true))
    }
}
