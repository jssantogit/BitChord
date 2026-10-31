package com.music.bitchord.acquisition.a0

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class A0ManualExceptionStageTest {
    @Test fun fetchFailureNeverClaimsAPossiblePublication() {
        assertEquals(A0ManualExceptionStage.Phase.FETCH,
            A0ManualExceptionStage.phase(false,false))
        assertFalse(A0ManualExceptionStage.publicationMayBeUncertain(false))
        assertEquals(A0ManualExceptionStage.Category.IO,
            A0ManualExceptionStage.category(IOException("private message")))
    }

    @Test fun postReceiptFailureBeforePublishIsNotAnUncertainCommit() {
        assertEquals(A0ManualExceptionStage.Phase.PROBE_OR_PREPARE,
            A0ManualExceptionStage.phase(true,false))
        assertFalse(A0ManualExceptionStage.publicationMayBeUncertain(false))
    }

    @Test fun publicationExceptionMustStillRequireReconciliation() {
        assertEquals(A0ManualExceptionStage.Phase.PUBLISH,
            A0ManualExceptionStage.phase(true,true))
        assertTrue(A0ManualExceptionStage.publicationMayBeUncertain(true))
    }
}
