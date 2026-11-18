package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQueryExecutorTest {
    private val apple = ProviderKey("itunes")
    private val descriptor = ProviderDescriptor(apple, setOf(
        ProviderCapability.CATALOG_SEARCH,
        ProviderCapability.RELEASE_ARTWORK,
    ), readiness = ProviderReadiness.READY)

    private fun gate(search: Boolean = true, art: Boolean = true) = ProviderPolicyGate(
        ProviderCapabilityPolicy(
            descriptors = listOf(descriptor),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(apple, search, 0)),
                ProviderCapability.RELEASE_ARTWORK to listOf(ProviderChoice(apple, art, 0)),
            ),
        ),
    )

    private fun policy(search: Boolean = true, art: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(descriptor),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(apple, search, 0)),
                ProviderCapability.RELEASE_ARTWORK to listOf(ProviderChoice(apple, art, 0)),
            ),
        )

    @Test fun disabledCapabilityNeverExecutesTheAdapter() = runBlocking {
        val executor = ProviderQueryExecutor(gate(search = false))
        var invoked = false
        val result = executor.execute(ProviderCapability.CATALOG_SEARCH, apple) {
            invoked = true
            "should-not-be-called"
        }
        assertEquals(ProviderCallResult.Denied, result)
        assertFalse(invoked)
    }

    @Test fun disablingAnInflightProviderActivelyCancelsOperationAndReturnsStale() = runBlocking {
        val gate = gate()
        val entered = CompletableDeferred<Unit>()
        val operationCancelled = CompletableDeferred<Unit>()
        val response = async {
            ProviderQueryExecutor(gate).execute(ProviderCapability.CATALOG_SEARCH, apple) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    operationCancelled.complete(Unit)
                }
            }
        }
        withTimeout(2_000) { entered.await() }
        gate.replace(policy(search = false))
        withTimeout(2_000) { operationCancelled.await() }
        assertEquals(ProviderCallResult.Stale, withTimeout(2_000) { response.await() })
    }

    @Test fun changingArtworkChoiceDoesNotInvalidateAnUnrelatedCatalogResponse() = runBlocking {
        val gate = gate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val response = async {
            ProviderQueryExecutor(gate).execute(ProviderCapability.CATALOG_SEARCH, apple) {
                entered.complete(Unit)
                release.await()
                "catalog data"
            }
        }
        entered.await()
        gate.replace(policy(art = false))
        release.complete(Unit)
        assertEquals(ProviderCallResult.Success("catalog data"), response.await())
    }

    @Test fun enabledProviderFailureRemainsARealFailure() = runBlocking {
        val error = IllegalStateException("HTTP 503")
        val result = ProviderQueryExecutor(gate()).execute(
            ProviderCapability.CATALOG_SEARCH, apple,
        ) { throw error }
        assertTrue("Expected Failed, got $result", result is ProviderCallResult.Failed)
        val failure = (result as ProviderCallResult.Failed).cause
        // Coroutine stacktrace recovery can copy the exception; identity is not a contract.
        assertTrue(failure is IllegalStateException)
        assertEquals(error.message, failure.message)
    }

    @Test fun cancellationIsNeverSwallowedAsProviderFailure() = runBlocking {
        var cancelled = false
        try {
            ProviderQueryExecutor(gate()).execute(ProviderCapability.CATALOG_SEARCH, apple) {
                throw CancellationException("navigate away")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
