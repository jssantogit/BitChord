package com.music.bitchord

import com.music.bitchord.data.providers.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ListeningSubmissionGateTest {
    private val lastfm = BuiltInProviderKeys.LASTFM
    private val listenbrainz = BuiltInProviderKeys.LISTENBRAINZ

    private fun policy(lastfmOn: Boolean, listenbrainzOn: Boolean) =
        ProviderCapabilityPolicy(
            descriptors = listOf(lastfm, listenbrainz).map {
                ProviderDescriptor(it, setOf(ProviderCapability.LISTENING_STATISTICS),
                    ProviderReadiness.READY)
            },
            choices = mapOf(ProviderCapability.LISTENING_STATISTICS to listOf(
                ProviderChoice(lastfm, lastfmOn, 0),
                ProviderChoice(listenbrainz, listenbrainzOn, 1),
            )),
        )

    @Test fun removingTheLastEnabledSubmissionProviderPreventsDispatch() = runBlocking {
        val gate = ProviderPolicyGate(policy(false, false))
        var invoked = false
        val result = ListeningSubmissionGate.execute(gate, listenbrainz) {
            invoked = true
            true
        }
        assertEquals(ProviderCallResult.Denied, result)
        assertFalse(invoked)
    }

    @Test fun revokingListenBrainzCancelsPendingSubmissionWithoutTouchingLastfm() = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val submission = async {
            ListeningSubmissionGate.execute(gate, listenbrainz) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        withTimeout(5_000) { entered.await() }
        gate.replace(policy(true, false))
        withTimeout(5_000) { cancelled.await() }
        assertEquals(ProviderCallResult.Stale, withTimeout(5_000) { submission.await() })
        assertTrue(ListeningSubmissionGate.permitted(gate, lastfm))
        assertFalse(ListeningSubmissionGate.permitted(gate, listenbrainz))
    }
}
