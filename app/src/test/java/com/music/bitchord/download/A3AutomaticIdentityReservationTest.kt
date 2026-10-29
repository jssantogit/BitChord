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

    @Test fun generationGateNeverRunsUnderA3Monitor() {
        val store=Store()
        val journal=A3AcquisitionLedger(store)
        val second=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            journal.reserveAutomaticBatch(listOf(slot(first)),commitGuard={ commit ->
                // Publication already takes the account-generation monitor
                // first, then enters A3. The opposite order deadlocks.
                // This second thread models a concurrent A3 reader while the
                // generation gate is being entered.
                second.submit { journal.all().size }.get(
                    2,java.util.concurrent.TimeUnit.SECONDS)
                commit()
            })
            assertEquals(1,store.writes)
        }finally { second.shutdownNow() }
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

    @Test fun noPeerTransferMayReleaseWholeUnusedAutoAlbum() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"recording-2","Artist - Track Two")))
        val reopened=A3AcquisitionLedger(store)
        assertTrue(reopened.releaseUntransferredAutomaticBatch(listOf(first,second)))
        assertTrue(reopened.all().isEmpty())
        val next=A3AcquisitionLedger(store)
        next.reserveAutomaticBatch(listOf(slot(third)))
        assertEquals(1,next.all().size)
        assertEquals(3,store.writes)
    }

    @Test fun anyStartedPeerMakesEntireAlbumIneligibleForRelease() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"recording-2","Artist - Two")))
        ledger.advance(first,A3AcquisitionLedger.Phase.SEARCHING)
        ledger.advance(first,A3AcquisitionLedger.Phase.AWAITING_SELECTION)
        ledger.advance(first,A3AcquisitionLedger.Phase.SELECTED)
        ledger.advance(first,A3AcquisitionLedger.Phase.TRANSFERRING)
        val rawBefore=store.raw
        assertFalse(ledger.releaseUntransferredAutomaticBatch(listOf(first,second)))
        assertEquals(rawBefore,store.raw)
        try {
            ledger.reserveAutomaticBatch(listOf(slot(third)))
            fail("any started P/F retains the whole album's fence")
        }catch(_:IllegalStateException) {}
    }

    @Test fun onlyACompleteExactBatchCanReleaseReservedSlots() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"recording-2","Artist - Two")))
        val before=store.raw
        assertFalse(ledger.releaseUntransferredAutomaticBatch(listOf(first,"wrong")))
        assertFalse(ledger.releaseUntransferredAutomaticBatch(listOf(first,first)))
        assertFalse(ledger.releaseUntransferredAutomaticBatch(emptyList()))
        assertEquals(before,store.raw)
        assertEquals(2,ledger.all().size)
        assertTrue(ledger.releaseUntransferredAutomaticBatch(listOf(first,second)))
    }

    @Test fun interruptedColdStartReservationsAreNeverAutoReleased() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(slot(first)))
        val restarted=A3AcquisitionLedger(store)
        restarted.interruptActiveOnColdStart()
        assertFalse(restarted.releaseUntransferredAutomaticBatch(listOf(first)))
        assertEquals(A3AcquisitionLedger.Phase.INTERRUPTED,
            restarted.get(first)?.phase)
    }

    @Test fun releaseDiskFailureRetainsCompleteIdentityHistory() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(slot(first)))
        val before=store.raw
        store.failWrites=true
        try {
            ledger.releaseUntransferredAutomaticBatch(listOf(first))
            fail("failed commit never releases a durable identity")
        }catch(_:IOException) {}
        assertEquals(before,store.raw)
        assertNotNull(ledger.get(first))
        store.failWrites=false
        assertTrue(ledger.releaseUntransferredAutomaticBatch(listOf(first)))
    }

    @Test fun legacyRetryApiMustNeverReopenFailedAutomaticPFSnapshot() {
        val store=Store()
        val firstProcess=A3AcquisitionLedger(store)
        firstProcess.reserveAutomaticBatch(listOf(slot(first)))
        firstProcess.advance(first,A3AcquisitionLedger.Phase.SEARCHING)
        firstProcess.advance(first,A3AcquisitionLedger.Phase.AWAITING_SELECTION)
        firstProcess.advance(first,A3AcquisitionLedger.Phase.SELECTED)
        firstProcess.advance(first,A3AcquisitionLedger.Phase.TRANSFERRING)
        firstProcess.advance(first,A3AcquisitionLedger.Phase.FAILED)
        val reopened=A3AcquisitionLedger(store)
        try {
            reopened.retry(first)
            fail("FAILED automatic transfer may already have published media")
        }catch(_:IllegalStateException) {}
        assertEquals(A3AcquisitionLedger.Phase.FAILED,reopened.get(first)?.phase)
    }

    @Test fun legacyRetryApiMustNeverReopenColdStartAutomaticReservation() {
        val store=Store()
        val original=A3AcquisitionLedger(store)
        original.reserveAutomaticBatch(listOf(slot(first)))
        val cold=A3AcquisitionLedger(store)
        cold.interruptActiveOnColdStart()
        try {
            cold.retry(first)
            fail("INTERRUPTED automatic A3 identity must not rerun")
        }catch(_:IllegalStateException) {}
        assertEquals(A3AcquisitionLedger.Phase.INTERRUPTED,cold.get(first)?.phase)
    }

    @Test fun legacyManualRetryRemainsAvailableAfterInterruption() {
        val store=Store()
        val manual=A3AcquisitionLedger(store)
        manual.create(first)
        val cold=A3AcquisitionLedger(store)
        cold.interruptActiveOnColdStart()
        cold.retry(first)
        assertEquals(A3AcquisitionLedger.Phase.SEARCHING,cold.get(first)?.phase)
    }

    @Test fun manualReviewCanReuseOnlyExactUntouchedAutomaticSlot() {
        val store=Store()
        val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"rec-two","Artist - Another Track")))
        assertEquals(first,ledger.reservedForExplicitManualReview(
            "recording-1","Artist - Track","Album")?.id)
        try {
            ledger.reservedForExplicitManualReview(
                "recording-1","Artist - Wrong Song","Album")
            fail("mixed canonical identity not accepted")
        }catch(_:A3AcquisitionLedger.AutomaticIdentityAlreadyReserved) {}
        ledger.advance(first,A3AcquisitionLedger.Phase.SEARCHING)
        assertEquals(A3AcquisitionLedger.Phase.SEARCHING,
            ledger.reservedForExplicitManualReview(
                "recording-1","Artist - Track","Album")?.phase)
    }

    @Test fun multipleUniqueSlotsReserveInOneDurableCommit() {
        val store=Store();val ledger=A3AcquisitionLedger(store)
        ledger.reserveAutomaticBatch(listOf(
            slot(first),slot(second,"recording-2","ILLIT - Lucky Girl Syndrome")))
        assertEquals(1,store.writes)
        assertEquals(2,A3AcquisitionLedger(store).all().size)
    }
}
