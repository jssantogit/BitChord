package com.music.bitchord.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A3AcquisitionLedgerTest {
    private class MemoryStore(var data:String?=null):A3AcquisitionLedger.Store {
        var refuseWrites=false
        override fun read():String?=data
        override fun commit(raw:String):Boolean {
            if(refuseWrites)return false
            data=raw;return true
        }
    }
    private val id1="a8e39a2c-8bbd-458d-b62e-c1ae490dad3f"
    private val id2="10e39a2c-8bbd-458d-b62e-c1ae490dad3f"

    private fun ready(j:A3AcquisitionLedger,id:String) {
        j.create(id)
        j.advance(id,A3AcquisitionLedger.Phase.SEARCHING)
        j.advance(id,A3AcquisitionLedger.Phase.AWAITING_SELECTION)
        j.advance(id,A3AcquisitionLedger.Phase.SELECTED)
        j.advance(id,A3AcquisitionLedger.Phase.TRANSFERRING)
        j.advance(id,A3AcquisitionLedger.Phase.STAGED)
        j.advance(id,A3AcquisitionLedger.Phase.PUBLISHING)
    }

    @Test fun durableStateSurvivesRecreationAndOnlyCompletePublicationHasUri() {
        val store=MemoryStore()
        val journal=A3AcquisitionLedger(store)
        ready(journal,id1)
        val pending=A3AcquisitionLedger(store).get(id1)!!
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,pending.phase)
        assertNull(pending.contentUri)
        journal.available(id1,"content://media/external/audio/media/52",128)
        val saved=A3AcquisitionLedger(store).get(id1)!!
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,saved.phase)
        assertEquals("content://media/external/audio/media/52",saved.contentUri)
        assertEquals(128L,saved.byteCount)
    }

    @Test fun restartNeverPretendsToResumeSocketOrIncompletePublication() {
        val store=MemoryStore();val j=A3AcquisitionLedger(store)
        j.create(id1)
        j.advance(id1,A3AcquisitionLedger.Phase.SEARCHING)
        j.create(id2)
        j.advance(id2,A3AcquisitionLedger.Phase.SEARCHING)
        j.advance(id2,A3AcquisitionLedger.Phase.AWAITING_SELECTION)
        j.advance(id2,A3AcquisitionLedger.Phase.SELECTED)
        j.advance(id2,A3AcquisitionLedger.Phase.TRANSFERRING)
        assertEquals(2,A3AcquisitionLedger(store).interruptActiveOnColdStart())
        val again=A3AcquisitionLedger(store)
        assertEquals(A3AcquisitionLedger.Phase.INTERRUPTED,again.get(id1)?.phase)
        assertEquals(A3AcquisitionLedger.Phase.INTERRUPTED,again.get(id2)?.phase)
        assertNull(again.get(id2)?.contentUri)
        assertEquals(0,again.interruptActiveOnColdStart())
        again.retry(id2)
        assertEquals(A3AcquisitionLedger.Phase.SEARCHING,again.get(id2)?.phase)
    }

    @Test fun cannotJumpToAvailableWithoutRequiredTransferPhases() {
        val j=A3AcquisitionLedger(MemoryStore())
        j.create(id1)
        try {
            j.available(id1,"content://media/external/audio/media/52",128)
            fail("No completion without transfer")
        }catch(_:IllegalStateException){}
        assertEquals(A3AcquisitionLedger.Phase.REQUESTED,j.get(id1)?.phase)
    }

    @Test fun invalidUriZeroBytesAndDuplicateClaimFailClosed() {
        val j=A3AcquisitionLedger(MemoryStore())
        ready(j,id1)
        for(uri in listOf("file:///tmp/a.wav","https://server/file","content://","content://bad auth/a")) {
            try { j.available(id1,uri,128);fail("must reject $uri") }
            catch(_:IllegalArgumentException){}
        }
        try { j.available(id1,"content://media/external/audio/media/52",0);fail("zero bytes") }
        catch(_:IllegalArgumentException){}
        j.available(id1,"content://media/external/audio/media/52",128)
        ready(j,id2)
        try {j.available(id2,"content://media/external/audio/media/52",128);fail("duplicate URI")}
        catch(_:IllegalStateException){}
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,j.get(id2)?.phase)
    }

    @Test fun highResolutionReceiptLargerThan64MiBIsAcceptedUpToShared256MiBLimit() {
        val ledger=A3AcquisitionLedger(MemoryStore())
        ready(ledger,id1)
        val large=128L*1024L*1024L
        ledger.available(id1,"content://media/external/audio/media/128",large)
        assertEquals(large,ledger.get(id1)?.byteCount)
        ready(ledger,id2)
        try {
            ledger.available(id2,"content://media/external/audio/media/256",
                com.music.bitchord.acquisition.a0.A0ObservedTransferLimits.MAX_AUDIO_BYTES+1)
            fail("Must reject over-limit audio")
        }catch(_:IllegalArgumentException) {}
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,ledger.get(id2)?.phase)
    }

    @Test fun failedDiskCommitLeavesInMemoryAndPersistedStateUnchanged() {
        val store=MemoryStore()
        val j=A3AcquisitionLedger(store)
        j.create(id1)
        store.refuseWrites=true
        try{j.advance(id1,A3AcquisitionLedger.Phase.SEARCHING);fail("disk commit failed")}
        catch(_:IOException){}
        assertEquals(A3AcquisitionLedger.Phase.REQUESTED,j.get(id1)?.phase)
        assertEquals(A3AcquisitionLedger.Phase.REQUESTED,A3AcquisitionLedger(store).get(id1)?.phase)
    }

    @Test fun corruptedJournalFailsClosedInsteadOfWipingHistory() {
        val store=MemoryStore("{bad json")
        try{A3AcquisitionLedger(store);fail("Corruption cannot mean empty downloads")}
        catch(_:IOException){}
        assertEquals("{bad json",store.data)
    }

    @Test fun manyAlbumsCannotExhaustJournalAfterOnly128Tracks() {
        val store=MemoryStore()
        val ledger=A3AcquisitionLedger(store)
        repeat(160) { index ->
            val next=java.util.UUID.nameUUIDFromBytes(
                "auto-album-track-$index".toByteArray()).toString()
            ledger.create(next)
        }
        assertEquals(160,ledger.all().size)
        assertEquals(160,A3AcquisitionLedger(store).all().size)
    }

    @Test fun noRawPeerFileOrSearchFieldsAreWritten() {
        val store=MemoryStore()
        val j=A3AcquisitionLedger(store)
        j.create(id1)
        j.advance(id1,A3AcquisitionLedger.Phase.SEARCHING)
        val raw=store.data!!
        assertFalse(raw.contains("peer",true))
        assertFalse(raw.contains("filename",true))
        assertFalse(raw.contains("searchQuery",true))
    }

    @Test fun completedItemsStayAvailableAcrossRecoveryAndCannotAutoRetry() {
        val store=MemoryStore();val j=A3AcquisitionLedger(store)
        ready(j,id1)
        j.available(id1,"content://media/external/audio/media/52",128)
        assertEquals(0,A3AcquisitionLedger(store).interruptActiveOnColdStart())
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,A3AcquisitionLedger(store).get(id1)?.phase)
        try{j.retry(id1);fail("don't retry saved file")}catch(_:IllegalStateException){}
    }
}
