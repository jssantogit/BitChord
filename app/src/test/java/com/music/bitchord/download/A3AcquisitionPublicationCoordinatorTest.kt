package com.music.bitchord.download

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A3AcquisitionPublicationCoordinatorTest {
    private class MemoryStore:A3AcquisitionLedger.Store {
        var data:String?=null
        var refuse=false
        override fun read():String?=data
        override fun commit(raw:String):Boolean =
            if(refuse)false else { data=raw;true }
    }
    private val id="d8e39a2c-8bbd-458d-b62e-c1ae490dad3f"
    private val uri="content://media/external/audio/media/99"
    private fun staged(store:MemoryStore):A3AcquisitionLedger {
        val j=A3AcquisitionLedger(store)
        j.create(id)
        for(s in listOf(
            A3AcquisitionLedger.Phase.SEARCHING,
            A3AcquisitionLedger.Phase.AWAITING_SELECTION,
            A3AcquisitionLedger.Phase.SELECTED,
            A3AcquisitionLedger.Phase.TRANSFERRING,
            A3AcquisitionLedger.Phase.STAGED,
        )) j.advance(id,s)
        return j
    }

    private fun result()=A2LocalAudioPublisher.Published(uri,128,"a".repeat(64),"audio/x-wav")

    @Test fun completedPublisherBecomesDurableLocalUriNotSeparateLibraryKind() {
        val store=MemoryStore();val journal=staged(store)
        val deleted=mutableListOf<String>()
        val coordinator=A3AcquisitionPublicationCoordinator(journal) {
            deleted+=it;true
        }
        assertEquals(result(),coordinator.publish(id) { result() })
        val reopened=A3AcquisitionLedger(store).get(id)!!
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,reopened.phase)
        assertEquals(uri,reopened.contentUri)
        assertTrue(deleted.isEmpty())
    }

    @Test fun publicationErrorCannotClaimLocalAvailability() {
        val store=MemoryStore();val journal=staged(store)
        val coordinator=A3AcquisitionPublicationCoordinator(journal) { true }
        try { coordinator.publish(id) { throw IOException("provider unavailable") }
            fail("A2 failure expected")
        }catch(_:IOException){}
        val state=A3AcquisitionLedger(store).get(id)!!
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,state.phase)
        assertNull(state.contentUri)
        assertEquals(1,A3AcquisitionLedger(store).interruptActiveOnColdStart())
    }

    @Test fun failedJournalCommitAfterPublicationAttemptsToDeleteVisibleFile() {
        val store=MemoryStore();val journal=staged(store)
        val deleted=mutableListOf<String>()
        val coordinator=A3AcquisitionPublicationCoordinator(journal) {
            deleted+=it;true
        }
        try {
            coordinator.publish(id) {
                store.refuse=true
                result()
            }
            fail("Journal commit failure must fail")
        }catch(_:IOException){}
        assertEquals(listOf(uri),deleted)
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,journal.get(id)?.phase)
    }

    @Test fun rollbackFailureIsReportedRatherThanClaimingSuccess() {
        val store=MemoryStore();val journal=staged(store)
        val coordinator=A3AcquisitionPublicationCoordinator(journal) { false }
        try {
            coordinator.publish(id) { store.refuse=true;result() }
            fail("Incomplete rollback must be surfaced")
        }catch(e:IOException) {
            assertTrue(e.message.orEmpty().contains("rollback"))
        }
        assertEquals(A3AcquisitionLedger.Phase.PUBLISHING,journal.get(id)?.phase)
    }

    @Test fun duplicatePublicationOnAvailableJobCannotTouchStorage() {
        val store=MemoryStore();val journal=staged(store)
        val coordinator=A3AcquisitionPublicationCoordinator(journal) { true }
        coordinator.publish(id) { result() }
        var calls=0
        try {
            coordinator.publish(id) { calls++;result() }
            fail("A published file cannot publish again")
        }catch(_:IllegalStateException){}
        assertEquals(0,calls)
    }
}
