package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ServerReaderShutdownPolicyTest {
    @Test fun intentionalStopMustNotBeMarkedAsServerFailure() {
        assertFalse(A0ServerReaderShutdownPolicy.shouldReportReadFailure(
            userStopRequested=true))
        assertFalse(A0ServerReaderShutdownPolicy.shouldReportPendingFrameFailure(
            userStopRequested=true))
    }

    @Test fun unexpectedFailureBeforeStopRemainsVisibleAndFailClosed() {
        assertTrue(A0ServerReaderShutdownPolicy.shouldReportReadFailure(
            userStopRequested=false))
        assertTrue(A0ServerReaderShutdownPolicy.shouldReportPendingFrameFailure(
            userStopRequested=false))
    }
}
