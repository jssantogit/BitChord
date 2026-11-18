package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.ProviderSingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Duplicate in-flight requests must share work, without sharing errors across keys. */
class ProviderSingleFlightTest {
    @Test fun identicalConcurrentCallsDispatchOnlyOnce(): Unit = runBlocking {
        val flight = ProviderSingleFlight<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val first = async {
            flight.run("artist:mbid") {
                requests++
                started.complete(Unit)
                release.await()
                "artist"
            }
        }
        withTimeout(2_000) { started.await() }
        val second = async {
            flight.run("artist:mbid") {
                requests++
                "duplicate"
            }
        }
        // Each call enters through the same coordinator while the first is pending.
        withTimeout(2_000) {
            while (flight.activeWaiters("artist:mbid") != 2) yield()
        }
        release.complete(Unit)
        assertEquals("artist", first.await())
        assertEquals("artist", second.await())
        assertEquals(1, requests)
        assertEquals(0, flight.activeCount())
    }

    @Test fun differentKeysDoNotBlockEachOther(): Unit = runBlocking {
        val flight = ProviderSingleFlight<String>()
        val firstHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            flight.run("artist:a") {
                firstHeld.complete(Unit)
                release.await()
                "a"
            }
        }
        withTimeout(2_000) { firstHeld.await() }
        assertEquals("b", withTimeout(2_000) {
            flight.run("artist:b") { "b" }
        })
        release.complete(Unit)
        assertEquals("a", first.await())
        assertEquals(0, flight.activeCount())
    }

    @Test fun cancellationOfOneObserverDoesNotCancelTheOther(): Unit = runBlocking {
        val flight = ProviderSingleFlight<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var underlyingWasCancelled = false
        val first = launch {
            flight.run("recording:mbid") {
                entered.complete(Unit)
                try {
                    release.await()
                    "result"
                } finally {
                    underlyingWasCancelled = !release.isCompleted
                }
            }
        }
        withTimeout(2_000) { entered.await() }
        val second = async {
            flight.run("recording:mbid") { "never run" }
        }
        withTimeout(2_000) {
            while (flight.activeWaiters("recording:mbid") != 2) yield()
        }
        first.cancelAndJoin()
        assertFalse(underlyingWasCancelled)
        release.complete(Unit)
        assertEquals("result", second.await())
        assertEquals(0, flight.activeCount())
    }

    @Test fun cancellationOfLastObserverStopsUnneededWorkAndReleasesKey(): Unit = runBlocking {
        val flight = ProviderSingleFlight<String>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val caller = launch {
            flight.run("missing") {
                entered.complete(Unit)
                try {
                    CompletableDeferred<String>().await()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        withTimeout(2_000) { entered.await() }
        caller.cancelAndJoin()
        withTimeout(2_000) { cancelled.await() }
        assertEquals(0, flight.activeCount())
        assertEquals("recovered", flight.run("missing") { "recovered" })
    }

    @Test fun policyRevocationActivelyCancelsSharedInflightSlot(): Unit = runBlocking {
        val mb = ProviderKey("musicbrainz")
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(mb, setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY),
            ),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(mb, enabled, 0)),
            ),
        )
        val gate = ProviderPolicyGate(policy(true))
        val flight = ProviderSingleFlight<String>()
        val started = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        val waiter = async {
            try {
                flight.runGuarded(
                    gate = gate,
                    capability = ProviderCapability.CATALOG_SEARCH,
                    provider = mb,
                    key = "artist:illit",
                ) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        aborted.complete(Unit)
                    }
                }
                false
            } catch (_: ProviderRequestDeniedException) {
                true
            }
        }
        withTimeout(2_000) { started.await() }
        gate.replace(policy(false))
        withTimeout(2_000) { aborted.await() }
        assertTrue(withTimeout(2_000) { waiter.await() })
        assertEquals(0, flight.activeCount())
    }

    @Test fun failuresAreSharedOnlyWithTheirOwnKeyAndFutureRetriesAreAllowed(): Unit = runBlocking {
        val flight = ProviderSingleFlight<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        suspend fun fail(): String = flight.run("unavailable") {
            requests++
            started.complete(Unit)
            release.await()
            error("503")
        }
        val first = async { runCatching { fail() }.exceptionOrNull() }
        withTimeout(2_000) { started.await() }
        val second = async { runCatching { fail() }.exceptionOrNull() }
        withTimeout(2_000) { while (flight.activeWaiters("unavailable") != 2) yield() }
        release.complete(Unit)
        assertTrue(first.await() is IllegalStateException)
        assertTrue(second.await() is IllegalStateException)
        assertEquals(1, requests)
        assertEquals(0, flight.activeCount())
        assertEquals("ok", flight.run("unavailable") { "ok" })
    }
}
