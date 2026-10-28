package com.music.bitchord.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Durable identity reservation must precede any discovery socket or P/F. */
class A3AutomaticIdentityReservationTest {
    private class Store(var raw:String?=null):A3AcquisitionLedger.Store {
        var failWrites=false
        var writes=0
        override fun read()=raw
        override fun commit(raw:String):Boolean {
            if(failWrites)return false
            writes++
            this.raw=raw
            return true
        }
    }
    private val first="fb49678b-5d24-4b57-82f8-ccf8f7fbda01"
    private val second="fb49678b-5d24-4b57-82f8-ccf8f7fbda02"
    private val third="fb49678b-5d24-4b57-82f8-ccf8f7fbda03"
    private fun slot(id:String,recording:String="recording-1",
        query:String="ILLIT - Magnetic",album:String?="SUPER REAL ME")=
        A3AcquisitionLedger.AutomaticSlot(id,recording,query,album)

    @Test fun identitySurvivesProcessDeathAndBlocksDuplicateBeforeSocket() {
        val store=Store()
        val firstProcess=A3AcquisitionLedger(store)
        firstProcess.reserveAutomaticBatch(listOf(slot(first)))
        val saved=firstProcess.get(first)!!
        assertEquals(A3AcquisitionLedger.Phase.REQUESTED,saved.phase)
        assertNotNull(saved.autoRecordingDigest)
        assertNotNull(saved.autoReleaseDigest)
        assertFalse(store.raw!!.contains("Magnetic"))
        assertFalse(store.raw!!.contains("SUPER REAL ME"))
        assertFalse(store.raw!!.contains("recording-1"))
        val next=A3AcquisitionLedger(store)
        next.interruptActiveOnColdStart()
        assertEquals(A3AcquisitionLedger.Phase.INTERRUPTED,
            next.get(first)?.phase)
        try {next.reserveAutomaticBatch(listOf(slot(second)));fail("duplicate")}
        catch(_:IllegalStateException) {}
        assertEquals(1,next.all().size)
    }

    @Test fun differentProviderIdWithSameReleaseIdentityCannotDuplicate() {
        val journal=A3AcquisitionLedger(Store())
        journal.reserveAutomaticBatch(listOf(slot(first)))
        try {journal.reserveAutomaticBatch(listOf(slot(second,
            recording="different-provider-track-id")));fail("duplicate hint")}
        catch(_:IllegalStateException) {}
    }

    @Test fun sameRecordingInDifferentMetadataCannotBeDownloadedTwice() {
        val journal=A3AcquisitionLedger(Store())
        journal.reserveAutomaticBatch(listOf(slot(first)))
        try {journal.reserveAutomaticBatch(listOf(slot(second,
            query="ILLIT - Magnetic (alternate)",album="Another album")));
            fail("duplicate recording")}
        catch(_:IllegalStateException) {}
    }

    @Test fun invalidOrPartiallyConflictingAlbumFailsAtomically() {
        val store=Store();val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(slot(first)))
        val before=store.raw
        try {ledger.reserveAutomaticBatch(listOf(
            slot(second,recording="new",query="Artist - New"),
            slot(third)));fail("partial atomic reservation")}
        catch(_:IllegalStateException) {}
        assertEquals(before,store.raw)
        assertNull(ledger.get(second))
        assertEquals(1,ledger.all().size)
    }

    @Test fun failedDurableWriteDoesNotAuthorizeOrReserveMemory() {
        val store=Store();val ledger=A3AcquisitionLedger(store)
        store.failWrites=true
        try{ledger.reserveAutomaticBatch(listOf(slot(first)));fail("commit")}
        catch(_:IOException){}
        assertTrue(ledger.all().isEmpty())
        assertNull(store.raw)
        store.failWrites=false
        ledger.reserveAutomaticBatch(listOf(slot(first)))
        assertEquals(1,A3AcquisitionLedger(store).all().size)
    }

    @Test fun manualA3RecordsRemainBackwardCompatible() {
        val store=Store()
        val old=A3AcquisitionLedger(store)
        old.create(first)
        val restarted=A3AcquisitionLedger(store)
        assertNull(restarted.get(first)?.autoRecordingDigest)
        restarted.reserveAutomaticBatch(listOf(slot(second)))
        assertEquals(2,A3AcquisitionLedger(store).all().size)
    }

    @Test fun legacyFailedAutoReservationMustNeverBeEvicted() {
        val store=Store();val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(slot(first)))
        ledger.advance(first,A3AcquisitionLedger.Phase.FAILED)
        try {A3AcquisitionLedger(store).reserveAutomaticBatch(
            listOf(slot(second)));fail("failed auto is still a physical attempt")}
        catch(_:IllegalStateException) {}
    }

    @Test fun malformedSaltOrIdentityFieldsCauseFailClosedRecovery() {
        val store=Store()
        A3AcquisitionLedger(store).reserveAutomaticBatch(listOf(slot(first)))
        store.raw=store.raw!!.replace(
            Regex("\"autoSalt\":\"[0-9a-f]{64}\""),
            "\"autoSalt\":\"broken\"")
        try{A3AcquisitionLedger(store);fail("salt corrupted")}
        catch(_:IOException){}
    }

    @Test fun revokedAuthorizationDuringCommitDoesNotPersistAnyReservations() {
        val store=Store()
        val journal=A3AcquisitionLedger(store)
        var gateCalled=false
        try {
            journal.reserveAutomaticBatch(listOf(slot(first),slot(second,
                "second-recording","Artist - Other")),commitGuard={ write ->
                gateCalled=true
                throw IOException("Account generation revoked")
            })
            fail("a revoked source cannot leave a permanent A3 claim")
        }catch(_:IOException) {}
        assertTrue(gateCalled)
        assertNull(store.raw)
        assertEquals(0,store.writes)
        assertTrue(journal.all().isEmpty())
        journal.reserveAutomaticBatch(listOf(slot(first)))
        assertEquals(1,store.writes)
    }

    @Test fun allowedCommitGateInvokedOnceForWholeAlbum() {
        val store=Store()
        val journal=A3AcquisitionLedger(store)
        var calls=0
        journal.reserveAutomaticBatch(listOf(slot(first),slot(second,
            "other","Artist - Other")),commitGuard={ action ->
            calls++
            action()
        })
        assertEquals(1,calls)
        assertEquals(1,store.writes)
        assertEquals(2,A3AcquisitionLedger(store).all().size)
    }

    @Test fun multipleUniqueSlotsReserveInOneDurableCommit() {
        val store=Store();val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"recording-2","ILLIT - Lucky Girl Syndrome")))
        assertEquals(1,store.writes)
        assertEquals(2,A3AcquisitionLedger(store).all().size)
    }
}
