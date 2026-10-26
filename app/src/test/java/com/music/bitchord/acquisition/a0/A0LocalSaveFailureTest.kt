package com.music.bitchord.acquisition.a0

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Post-byte failures are not P/F failures; metadata stays off diagnostics. */
class A0LocalSaveFailureTest {
    @Test fun publishedFileConflictDoesNotInviteBlindRedownload() {
        assertEquals(A0LocalSaveFailure.Kind.DESTINATION_COLLISION,
            A0LocalSaveFailure.classify(IOException("A2 destination filename already exists")))
        assertEquals(A0LocalSaveFailure.Kind.UNSUPPORTED_AUDIO,
            A0LocalSaveFailure.classify(IOException("A2 unsupported audio signature or extension")))
    }
    @Test fun destinationAndDurablePublicationRemainDistinguishable() {
        assertEquals(A0LocalSaveFailure.Kind.DESTINATION_UNAVAILABLE,
            A0LocalSaveFailure.classify(IOException("A2 destination requires folder re-selection")))
        assertEquals(A0LocalSaveFailure.Kind.DESTINATION_UNAVAILABLE,
            A0LocalSaveFailure.classify(IOException("A2 MediaStore preflight failed")))
        assertEquals(A0LocalSaveFailure.Kind.DURABLE_COMMIT,
            A0LocalSaveFailure.classify(IOException("A3 failed to commit published URI; artifact removed")))
        assertEquals(A0LocalSaveFailure.Kind.PUBLISHING_FAILED,
            A0LocalSaveFailure.classify(IOException("A2 short local publication")))
        assertEquals(A0LocalSaveFailure.Kind.SCRATCH_CLEANUP,
            A0LocalSaveFailure.classify(IOException("A0 observed scratch cleanup failed")))
    }
    @Test fun neverReturnUntrustedExceptionMessageToTraceOrUi() {
        val error=IOException("peer <secret> remote/path/surprise.mp3")
        val result=A0LocalSaveFailure.classify(error)
        assertEquals(A0LocalSaveFailure.Kind.OTHER,result)
        assertFalse(result.name.contains("secret"))
        assertFalse(result.name.contains("mp3"))
    }
}
