package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchWindowBudgetTest {
    @Test fun neverExceedsTwentySevenRequestsInsideSlidingWindow() {
        val budget=A0SearchWindowBudget(maxRequests=27,windowMillis=220_000L)
        val leases=List(9) { checkNotNull(budget.tryAcquire(3,1_000L)) }
        leases.forEach { lease ->
            repeat(3) { assertTrue(lease.commit(1_000L)) }
            lease.close()
        }
        assertNull(budget.tryAcquire(1,1_001L))
        assertEquals(219_999L,budget.waitMillis(1,1_001L))
        assertNotNull(budget.tryAcquire(3,221_000L))
    }

    @Test fun unusedSearchVariationsAreRefundedButSentQueriesRemainCharged() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val lease=checkNotNull(budget.tryAcquire(3,500))
        assertTrue(lease.commit(520))
        lease.close() // two fallbacks never sent
        assertEquals(2,budget.available(550))
        val other=checkNotNull(budget.tryAcquire(2,551))
        assertTrue(other.commit(552))
        assertTrue(other.commit(559))
        other.close()
        assertEquals(0,budget.available(600))
        assertEquals(219_920L,budget.waitMillis(1,600))
        assertNotNull(budget.tryAcquire(1,220_520L))
    }

    @Test fun leaseCannotCommitMoreThanReservedOrBeDoubleClosed() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val lease=checkNotNull(budget.tryAcquire(2,1_000L))
        assertTrue(lease.commit(1_001L))
        assertTrue(lease.commit(1_002L))
        assertFalse(lease.commit(1_003L))
        lease.close()
        lease.close()
        assertEquals(1,budget.available(1_010L))
    }

    @Test fun cancelledReservationFreesAllCapacityWithoutSendingQueries() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val lease=checkNotNull(budget.tryAcquire(3,30_000L))
        assertEquals(0,budget.available(30_001L))
        lease.close()
        assertEquals(3,budget.available(30_001L))
    }

    @Test fun partialCapacityStartsTheInitialSearchWithoutThreeFreeSlots() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val earlier=checkNotNull(budget.tryAcquire(2,100L))
        assertTrue(earlier.commit(101L))
        assertTrue(earlier.commit(102L))
        earlier.close()

        val search=checkNotNull(budget.tryAcquireUpTo(3,200L))
        assertEquals(1,search.remaining())
        assertTrue(search.commit(201L))
        assertEquals(0,search.remaining())
        assertFalse(search.commit(202L)) // no unauthorized optional fallback
        assertNull(budget.tryAcquireUpTo(3,203L))
        search.close()
        assertEquals(0,budget.available(204L))
        assertEquals(219_897L,budget.waitMillis(1,204L))
    }

    @Test fun fullCapacityStillReservesThreeAndUnusedFallbacksReturn() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val search=checkNotNull(budget.tryAcquireUpTo(3,100L))
        assertEquals(3,search.remaining())
        assertTrue(search.commit(110L))
        assertEquals(2,search.remaining())
        search.close()
        assertEquals(0,search.remaining())
        assertEquals(2,budget.available(120L))
        val next=checkNotNull(budget.tryAcquireUpTo(3,121L))
        assertEquals(2,next.remaining())
        next.close()
    }

    @Test fun aPartialSearchCanUseSlotsThatExpireDuringDiscovery() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val preceding=checkNotNull(budget.tryAcquire(2,1_000L))
        assertTrue(preceding.commit(1_000L))
        assertTrue(preceding.commit(1_001L))
        preceding.close()
        val current=checkNotNull(budget.tryAcquireUpTo(3,220_990L))
        assertEquals(1,current.remaining())
        assertTrue(current.commit(220_991L))
        assertFalse(current.reserveOneMore(220_995L))
        // The preceding two committed queries age out while this session runs.
        assertTrue(current.reserveOneMore(221_002L))
        assertTrue(current.commit(221_003L))
        assertTrue(current.reserveOneMore(221_004L))
        assertTrue(current.commit(221_005L))
        assertFalse(current.reserveOneMore(221_006L)) // max 3 for session
        assertFalse(current.commit(221_006L))
        current.close()
        assertEquals(0,budget.available(221_007L))
    }

    @Test fun extensionRespectsOtherSessionsAndClosedLease() {
        val budget=A0SearchWindowBudget(3,220_000L)
        val first=checkNotNull(budget.tryAcquireUpTo(3,1_000L))
        assertEquals(3,first.remaining())
        assertFalse(first.reserveOneMore(1_001L))
        first.close()
        assertFalse(first.reserveOneMore(1_002L))
        val second=checkNotNull(budget.tryAcquire(3,1_003L))
        assertEquals(3,second.remaining())
        assertFalse(first.commit(1_004L))
        second.close()
    }

    @Test fun reservationIsSharedAcrossIndependentSearchSessions() {
        val shared=A0SearchWindowBudget(4,220_000L)
        val first=checkNotNull(shared.tryAcquire(3,100L))
        assertNull(shared.tryAcquire(2,101L))
        first.commit(102L)
        first.close()
        assertEquals(3,shared.available(103L))
        val second=checkNotNull(shared.tryAcquire(3,104L))
        second.close()
        assertEquals(3,shared.available(105L))
    }
}
