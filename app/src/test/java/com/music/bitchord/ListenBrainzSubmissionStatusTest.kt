package com.music.bitchord

import com.music.bitchord.data.scrobbling.ListenBrainzSubmissionResult
import com.music.bitchord.data.scrobbling.listenBrainzSubmissionDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import com.music.bitchord.data.scrobbling.awaitListenBrainzSubmissionResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Test

class ListenBrainzSubmissionStatusTest {
    @Test fun cancelledWorkManagerSubmissionAbortsHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("delayed"))
            server.start()
            val call = OkHttpClient().newCall(
                Request.Builder().url(server.url("/submit-listens")).build())
            val pending = async(Dispatchers.IO) { awaitListenBrainzSubmissionResponse(call) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancel()
            withTimeout(5_000) { pending.join() }
            assertTrue(call.isCanceled())
        }
    }

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
