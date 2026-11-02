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
