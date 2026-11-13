package com.music.bitchord

import com.music.bitchord.data.scrobbling.ListenBrainzSubmissionResult
import com.music.bitchord.data.scrobbling.listenBrainzSubmissionDisposition
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenBrainzSubmissionStatusTest {
    @Test fun accepted2xxResponsesAreSafeToRemoveFromOutbox() {
        assertEquals(ListenBrainzSubmissionResult.ACCEPTED, listenBrainzSubmissionDisposition(200))
        assertEquals(ListenBrainzSubmissionResult.ACCEPTED, listenBrainzSubmissionDisposition(202))
    }

    @Test fun rateLimitServerErrorsAndUnknownResultsRemainPending() {
        listOf(408, 429, 500, 502, 503, 599).forEach {
            assertEquals(ListenBrainzSubmissionResult.RETRYABLE, listenBrainzSubmissionDisposition(it))
        }
    }

    @Test fun authorizationFailureMustStopInsteadOfSpammingRetries() {
        listOf(401, 403).forEach {
            assertEquals(ListenBrainzSubmissionResult.AUTH_ERROR, listenBrainzSubmissionDisposition(it))
        }
    }

    @Test fun invalidPayloadNeverLoopsIndefinitely() {
        listOf(400, 413, 422).forEach {
            assertEquals(ListenBrainzSubmissionResult.REJECTED, listenBrainzSubmissionDisposition(it))
        }
    }
}
