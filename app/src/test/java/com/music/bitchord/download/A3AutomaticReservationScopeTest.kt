package com.music.bitchord.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A3AutomaticReservationScopeTest {
    private class Store:A3AcquisitionLedger.Store {
        var state:String?=null
        override fun read():String?=state
        override fun commit(raw:String):Boolean { state=raw;return true }
    }
    private val id="90ce3cb8-0369-4e07-8904-f1064efb0933"
    private fun ledger():A3AcquisitionLedger {
        return A3AcquisitionLedger(Store()).also {
            it.reserveAutomaticBatch(listOf(A3AcquisitionLedger.AutomaticSlot(
                id,"catalog-recording","Artist - Track","Album")))
        }
    }

    @Test fun cleanSearchOnlyReturnReleasesReservationAndReportsRetryAllowed() {
        val journal=ledger()
        val outcome=A3AutomaticReservationScope(journal).run(listOf(id)) {
            assertEquals(A3AcquisitionLedger.Phase.REQUESTED,
                journal.get(id)?.phase)
            "no sources"
        }
        assertEquals("no sources",outcome.value)
        assertTrue(outcome.unusedReservationReleased)
        assertNull(journal.get(id))
    }

    @Test fun throwDuringDiscoveryStillFinalizesUnstartedReservation() {
        val journal=ledger()
        try {
            A3AutomaticReservationScope(journal).run(listOf(id)) {
                throw IOException("temporary discovery failure")
            }
            fail("must propagate discovery failure")
        }catch(_:IOException) {}
        assertNull(journal.get(id))
    }

    @Test fun begunTransferRetainsReservationOnNormalOrExceptionalExit() {
        for(crash in listOf(false,true)) {
            val journal=ledger()
            if(crash) {
                try {
                    A3AutomaticReservationScope(journal).run(listOf(id)) {
                        journal.advance(id,A3AcquisitionLedger.Phase.SEARCHING)
                        throw IOException("interrupted after P/F admission")
                    }
                    fail("must rethrow")
                }catch(_:IOException) {}
            } else {
                val result=A3AutomaticReservationScope(journal).run(listOf(id)) {
                    journal.advance(id,A3AcquisitionLedger.Phase.SEARCHING)
                    "started"
                }
                assertFalse(result.unusedReservationReleased)
            }
            assertEquals(A3AcquisitionLedger.Phase.SEARCHING,
                journal.get(id)?.phase)
        }
    }
}
