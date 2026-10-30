package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** No UI dismissal or transient review eviction may authorize a second auto P/F. */
class SoulseekAutoAttemptGuardTest {
    private fun entry(id:String,query:String="ILLIT - Magnetic") =
        SoulseekBatchPlan.Entry(id,query,"SUPER REAL ME",180)

    @Test fun sameCanonicalSlotCannotBeQueuedAgainAfterCompletionOrClear() {
        val gate=SoulseekAutoAttemptGuard()
        assertTrue(gate.reserve(listOf(entry("recording-1"))))
        assertFalse(gate.reserve(listOf(entry("recording-1"))))
        assertFalse(gate.reserve(listOf(entry("recording-1","ILLIT - Magnetic (alt)"))))
        assertEquals(1,gate.slotCount)
    }

    @Test fun differentIdsWithSameArtistTitleAndAlbumAreNotRepeated() {
        val gate=SoulseekAutoAttemptGuard()
        assertTrue(gate.reserve(listOf(entry("catalog-id-1"))))
        assertFalse(gate.reserve(listOf(entry("manual-id-abc"))))
        assertTrue(gate.reserve(listOf(entry("catalog-id-2","ILLIT - Lucky Girl Syndrome"))))
    }

    @Test fun overlappingAlbumsAreAtomicallyRefusedWithoutPoisoningNewSlots() {
        val gate=SoulseekAutoAttemptGuard()
        val first=listOf(entry("a","Artist - A"),entry("b","Artist - B"))
        assertTrue(gate.reserve(first))
        assertFalse(gate.reserve(listOf(entry("c","Artist - C"),entry("b","Artist - B"))))
        assertTrue(gate.reserve(listOf(entry("c","Artist - C"))))
        assertEquals(3,gate.slotCount)
    }

    @Test fun serviceStartFailureCanReleaseOnlyTheReservationItCreated() {
        val gate=SoulseekAutoAttemptGuard()
        val a=listOf(entry("a","Artist - A"))
        val b=listOf(entry("b","Artist - B"))
        assertTrue(gate.reserve(a))
        assertTrue(gate.reserve(b))
        gate.rollback(b)
        assertFalse(gate.reserve(a))
        assertTrue(gate.reserve(b))
    }

    @Test fun guardFailsClosedAtCapacityWithoutEvictingOlderAttempts() {
        val gate=SoulseekAutoAttemptGuard(maxSlots=2)
        assertTrue(gate.reserve(listOf(entry("a","Artist - A"),entry("b","Artist - B"))))
        assertFalse(gate.reserve(listOf(entry("c","Artist - C"))))
        assertFalse(gate.reserve(listOf(entry("a","Artist - A"))))
        gate.rollback(listOf(entry("b","Artist - B")))
        assertTrue(gate.reserve(listOf(entry("c","Artist - C"))))
        assertFalse(gate.reserve(listOf(entry("a","Artist - A"))))
    }

    @Test fun incompleteAndDuplicateBatchCannotCreateAttemptRecord() {
        val gate=SoulseekAutoAttemptGuard()
        assertFalse(gate.reserve(emptyList()))
        assertFalse(gate.reserve(listOf(entry("a"),entry("a"))))
        assertEquals(0,gate.slotCount)
    }
}
