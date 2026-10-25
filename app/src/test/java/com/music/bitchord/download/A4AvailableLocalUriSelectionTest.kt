package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class A4AvailableLocalUriSelectionTest {
    private fun row(
        id:String,
        state:A3AcquisitionLedger.Phase,
        uri:String?=null
    )=A3AcquisitionLedger.Entry(
        id=id,phase=state,contentUri=uri,
        byteCount=if(state==A3AcquisitionLedger.Phase.AVAILABLE)128L else null
    )
    private val media="content://media/external/audio/media/52"
    private val saf="content://com.android.externalstorage.documents/document/primary%3AMusic%2FFile.wav"

    @Test fun availableSafUriBecomesRegularLocalInputNotSpecialSource() {
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(row("1",A3AcquisitionLedger.Phase.AVAILABLE,saf)),
            alreadyScanned=emptySet()
        ) { true }
        assertEquals(listOf(saf),candidates)
    }

    @Test fun mediaStoreUriAlreadyInLocalScanIsNotDuplicated() {
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(row("1",A3AcquisitionLedger.Phase.AVAILABLE,media)),
            alreadyScanned=setOf(media)
        ) { true }
        assertEquals(emptyList<String>(),candidates)
    }

    @Test fun activeAndInterruptedAndFailedTransfersDoNotBecomeMusic() {
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(
                row("1",A3AcquisitionLedger.Phase.TRANSFERRING),
                row("2",A3AcquisitionLedger.Phase.INTERRUPTED),
                row("3",A3AcquisitionLedger.Phase.FAILED),
                row("4",A3AcquisitionLedger.Phase.AVAILABLE,saf)
            ),
            alreadyScanned=emptySet()
        ){true}
        assertEquals(listOf(saf),candidates)
    }

    @Test fun duplicateUrisReturnedOnlyOnce() {
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(
                row("1",A3AcquisitionLedger.Phase.AVAILABLE,saf),
                row("2",A3AcquisitionLedger.Phase.AVAILABLE,saf)
            ),
            alreadyScanned=emptySet()
        ){true}
        assertEquals(listOf(saf),candidates)
    }

    @Test fun inaccessibleSafUriIsOmittedWithoutMutatingDurableState() {
        val source=row("1",A3AcquisitionLedger.Phase.AVAILABLE,saf)
        val entries=listOf(source)
        val candidates=A4AvailableLocalUriSelection.choose(
            entries,alreadyScanned=emptySet()
        ) { false }
        assertEquals(emptyList<String>(),candidates)
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,source.phase)
        assertEquals(saf,source.contentUri)
    }

    @Test fun permissionProbeIsNotInvokedForUnpublishedOrAlreadyKnownRows() {
        var probes=0
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(
                row("1",A3AcquisitionLedger.Phase.AVAILABLE,media),
                row("2",A3AcquisitionLedger.Phase.PUBLISHING)
            ),
            alreadyScanned=setOf(media)
        ) { probes++; true }
        assertTrue(candidates.isEmpty())
        assertEquals(0,probes)
    }
    @Test fun mediaStoreAndSafAliasShareOnePhysicalEntryWithoutDeletingLedger() {
        val mediaAlias="content://media/external_primary/audio/media/52"
        val ledger=row("1",A3AcquisitionLedger.Phase.AVAILABLE,mediaAlias)
        val result=A4AvailableLocalUriSelection.choose(
            listOf(ledger),alreadyScanned=setOf(media),
            identityOf=A4LocalPhysicalIdentity::key
        ) { true }
        assertTrue(result.isEmpty())
        assertEquals(A3AcquisitionLedger.Phase.AVAILABLE,ledger.phase)
    }

    @Test fun safDocumentOfAlreadyScannedPathIsNotASecondLibraryRow() {
        val document="content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2FBitChord%2FLooks.flac"
        val candidates=A4AvailableLocalUriSelection.choose(
            listOf(row("1",A3AcquisitionLedger.Phase.AVAILABLE,document)),
            alreadyScanned=emptySet(),
            alreadyIdentities=setOf(
                A4LocalPhysicalIdentity.pathKey("/storage/emulated/0/Music/BitChord/Looks.flac")),
            identityOf=A4LocalPhysicalIdentity::key
        ) { true }
        assertTrue(candidates.isEmpty())
    }

}
