package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.A3AcquisitionLedger
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A0AutoJobJournalTest {
    private val id="3dd32621-16d9-4dfb-881b-10bb630d7484"
    private class InMemory:A3AcquisitionLedger.Store {
        var data:String?=null
        override fun read():String?=data
        override fun commit(raw:String):Boolean { data=raw;return true }
    }
    private fun new():Pair<A3AcquisitionLedger,A0AutoJobJournal> {
        val ledger=A3AcquisitionLedger(InMemory())
        return ledger to A0AutoJobJournal(ledger,id)
    }
    @Test fun firstAttemptCreatesOneDurableJobAndRetriesDoNotRestartItsState() {
        val (ledger,job)=new()
        job.beginTransfer()
        job.beginTransfer() // same canonical file, second transport peer
        assertEquals(1,ledger.all().size)
        assertEquals(A3AcquisitionLedger.Phase.TRANSFERRING,ledger.get(id)?.phase)
        job.staged()
        assertEquals(A3AcquisitionLedger.Phase.STAGED,ledger.get(id)?.phase)
        job.finish()
        assertEquals(A3AcquisitionLedger.Phase.FAILED,ledger.get(id)?.phase)
    }
    @Test fun reservedAutomaticSlotUsesSameA3JobWithoutSecondCreate() {
        val store=InMemory()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(A3AcquisitionLedger.AutomaticSlot(
            id,"recording-1","ILLIT - Magnetic","SUPER REAL ME")))
        val job=A0AutoJobJournal(ledger,id,reservedAutomatic=true)
        job.beginTransfer()
        assertEquals(1,ledger.all().size)
        assertEquals(A3AcquisitionLedger.Phase.TRANSFERRING,
            ledger.get(id)?.phase)
        assertNotNull(ledger.get(id)?.autoRecordingDigest)
        job.finish()
        assertEquals(A3AcquisitionLedger.Phase.FAILED,
            ledger.get(id)?.phase)
    }

    @Test fun reservedJobRefusesUnboundOrCompletedLedgerRecord() {
        val empty=A3AcquisitionLedger(InMemory())
        try {
            A0AutoJobJournal(empty,id,reservedAutomatic=true).beginTransfer()
            fail("must have durable preflight")
        }catch(_:IllegalStateException){}
        empty.create(id)
        try {
            A0AutoJobJournal(empty,id,reservedAutomatic=true).beginTransfer()
            fail("legacy job not reserved")
        }catch(_:IllegalStateException){}
    }

    @Test fun noAttemptMeansNoJournalEntry() {
        val (ledger,job)=new()
        job.finish()
        assertTrue(ledger.all().isEmpty())
    }
    @Test fun durableCommitIsNotRewrittenAsFailure() {
        val (ledger,job)=new()
        job.beginTransfer()
        job.staged()
        ledger.advance(id,A3AcquisitionLedger.Phase.PUBLISHING)
        ledger.available(id,"content://media/external/audio/media/712",128*1024*1024L)
        job.finish()
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,ledger.get(id)?.phase)
    }
    @Test fun uncertainProviderCommitStaysReconcilableWithoutAutomaticRetry() {
        val (ledger,job)=new()
        job.beginTransfer()
        job.staged()
        ledger.advance(id,A3AcquisitionLedger.Phase.PUBLISHING)
        job.markUncertain()
        job.finish()
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,ledger.get(id)?.phase)
        assertTrue(job.needsReconciliation)
    }
    @Test fun confirmedRollbackMarksFailedAndNeverPretendsFileSaved() {
        val (ledger,job)=new()
        job.beginTransfer()
        job.staged()
        ledger.advance(id,A3AcquisitionLedger.Phase.PUBLISHING)
        job.markRolledBack()
        job.finish()
        assertEquals(A3AcquisitionLedger.Phase.FAILED,ledger.get(id)?.phase)
    }
    @Test fun journalNeverStoresUntrustedPathsOrClaims() {
        val store=InMemory()
        val ledger=A3AcquisitionLedger(store)
        A0AutoJobJournal(ledger,id).beginTransfer()
        assertFalse(store.data.orEmpty().contains("peer",ignoreCase=true))
        assertFalse(store.data.orEmpty().contains("remote",ignoreCase=true))
    }
}
