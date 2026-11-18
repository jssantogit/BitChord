package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.BoundedMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.ProviderSingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression at the actual shared MusicBrainz HTTP admission boundary. */
class MusicBrainzTransportAdmissionTest {
    @Test fun editorialSearchUsesItsOwnCapabilityWithoutReenablingGeneralSearch(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(search = false, discography = true, identity = true))
        var network = 0
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String, parameters: Map<String, String>,
                    userAgent: String): String {
                    network++
                    return """{"count":0,"offset":0,"release-groups":[]}"""
                }
            },
            admission = { gate },
        )
        val general = MusicBrainzCatalogSearch(client)
        val editorial = MusicBrainzCatalogSearch(
            client, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
        assertTrue(general.searchReleaseGroups("test")
            is MusicBrainzCatalogSearchResult.Failure)
        assertTrue(editorial.searchReleaseGroups("test")
            is MusicBrainzCatalogSearchResult.Success)
        assertEquals(1, network)

        gate.replace(policy(search = true, discography = false, identity = true))
        assertTrue(editorial.searchReleaseGroups("test2")
            is MusicBrainzCatalogSearchResult.Failure)
        assertTrue(general.searchReleaseGroups("test2")
            is MusicBrainzCatalogSearchResult.Success)
        assertEquals(2, network)
    }

    private val mb = ProviderKey("musicbrainz")
    private val ready = ProviderDescriptor(mb,
        setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY),
        readiness = ProviderReadiness.READY,
    )

    private fun policy(search: Boolean, discography: Boolean, identity: Boolean) =
        ProviderCapabilityPolicy(
            listOf(ready),
            mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(mb, search, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to
                    listOf(ProviderChoice(mb, discography, 0)),
                ProviderCapability.IDENTITY to listOf(ProviderChoice(mb, identity, 0)),
            ),
        )

    @Test fun identicalMusicBrainzRequestsShareOneRealHttpDispatch(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true, true))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flight = ProviderSingleFlight<String>()
        var networkCalls = 0
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                networkCalls++
                started.complete(Unit)
                release.await()
                return """{"count":0,"artists":[]}"""
            }
        }
        val one = MusicBrainzClient(
            transport = transport,
            rateLimiter = MusicBrainzRateLimiter(),
            admission = { gate },
            singleFlight = flight,
        )
        val two = MusicBrainzClient(
            transport = transport,
            rateLimiter = MusicBrainzRateLimiter(),
            admission = { gate },
            singleFlight = flight,
        )
        val first = async { one.get("artist", capability = ProviderCapability.CATALOG_SEARCH) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            two.get("artist", capability = ProviderCapability.CATALOG_SEARCH)
        }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, networkCalls)
        assertEquals(0, flight.activeCount())
    }

    @Test fun samePathDifferentCapabilitiesMustNotShareTheResponse(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true, true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flight = ProviderSingleFlight<String>()
        var contacts = 0
        val transport = object : MusicBrainzTransport {
            override suspend fun get(path: String,
                parameters: Map<String, String>, userAgent: String): String {
                contacts++
                if (contacts == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return "response"
            }
        }
        val client = MusicBrainzClient(transport = transport,
            rateLimiter = MusicBrainzRateLimiter(minimumIntervalMs = 1_000),
            admission = { gate }, singleFlight = flight)
        val first = async {
            client.get("artist", capability = ProviderCapability.IDENTITY)
        }
        entered.await()
        // Different capability must not borrow a response even with identical path.
        // A second call is queued by the MusicBrainz budget until the first ends.
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.get("artist", capability = ProviderCapability.CATALOG_SEARCH)
        }
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, contacts)
    }

    @Test fun catalogDisabledNeverReachesTransportOrConsumesRetryBudget(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(false, true, true))
        var contacts = 0
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    contacts++
                    return if (path.startsWith("artist/")) "identity"
                    else """{"count":0,"offset":0,"artists":[]}"""
                }
            },
            admission = { gate },
        )
        val search = MusicBrainzCatalogSearch(client)
        assertTrue(search.searchArtists("ILLIT") is MusicBrainzCatalogSearchResult.Failure)
        assertEquals(0, contacts)
        // Identity traffic must remain available when catalog search alone is off.
        assertEquals("identity", client.get("artist/123", capability = ProviderCapability.IDENTITY))
    }

    @Test fun capabilityRestrictionsAreIndependentAtTheHttpBoundary(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(false, true, true))
        var contacts = 0
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String,
                    parameters: Map<String, String>, userAgent: String): String {
                    contacts++
                    return "identity"
                }
            },
            admission = { gate },
        )
        try {
            client.get("artist", capability = ProviderCapability.CATALOG_SEARCH)
            throw AssertionError("catalog HTTP must be rejected")
        } catch (_: ProviderRequestDeniedException) { /* expected */ }
        assertEquals("identity", client.get("artist/id", capability = ProviderCapability.IDENTITY))
        assertEquals("identity",
            client.get("release-group", capability = ProviderCapability.EDITORIAL_DISCOGRAPHY))
        assertEquals(2, contacts)
    }

    @Test fun responseAfterProviderSwitchOffIsNeverReturnedAndTransportIsActivelyCancelled(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true, true))
        val entered = CompletableDeferred<Unit>()
        val transportCancelled = CompletableDeferred<Unit>()
        val flight = ProviderSingleFlight<String>()
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String,
                    parameters: Map<String, String>, userAgent: String): String {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        transportCancelled.complete(Unit)
                    }
                }
            },
            admission = { gate },
            singleFlight = flight,
        )
        val pending = async {
            try {
                client.get("artist", capability = ProviderCapability.CATALOG_SEARCH)
                false
            } catch (_: ProviderRequestDeniedException) {
                true
            }
        }
        withTimeout(2_000) { entered.await() }
        gate.replace(policy(false, true, true))
        withTimeout(2_000) { transportCancelled.await() }
        assertTrue(withTimeout(2_000) { pending.await() })
        assertEquals(0, flight.activeCount())
    }

    @Test fun switchOffDuringSharedLimiterQueuePreventsTheQueuedRequest(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true, true))
        var clock = 0L
        var contacts = 0
        val limiter = MusicBrainzRateLimiter(
            nowMillis = { clock },
            delayMillis = { millis ->
                clock += millis
                gate.replace(policy(false, true, true))
            },
        )
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String,
                    parameters: Map<String, String>, userAgent: String): String {
                    contacts++
                    return "ok"
                }
            },
            rateLimiter = limiter,
            admission = { gate },
        )
        assertEquals("ok", client.get("artist", capability = ProviderCapability.CATALOG_SEARCH))
        try {
            client.get("artist", capability = ProviderCapability.CATALOG_SEARCH)
            throw AssertionError("queued request crossed the opt-out boundary")
        } catch (_: ProviderRequestDeniedException) { /* correct */ }
        assertEquals(1, contacts)
    }

    @Test fun discographyTraversalIsActuallyTaggedAndDeniedAtTheSharedTransport(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, false, true))
        var contacted = false
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String,
                    parameters: Map<String, String>, userAgent: String): String {
                    contacted = true
                    return "{}"
                }
            },
            admission = { gate },
        )
        val graph = MusicBrainzCatalogGraph(client)
        val result = graph.browseReleaseGroupsForArtist(
            "11111111-2222-4333-8444-555555555555", limit = 10,
        )
        assertTrue(result is MusicBrainzCatalogGraphResult.Failure)
        assertFalse(contacted)
    }

    @Test fun aClosedGateDoesNotContactMusicBrainzEvenWhenItIsTheLastProvider(): Unit = runBlocking {
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(listOf(ready)))
        var contacted = false
        val client = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(path: String,
                    parameters: Map<String, String>, userAgent: String): String {
                    contacted = true
                    return "impossible"
                }
            },
            admission = { gate },
        )
        try {
            client.get("recording/id", capability = ProviderCapability.IDENTITY)
            throw AssertionError("unexpected network request")
        } catch (_: ProviderRequestDeniedException) { /* expected */ }
        assertFalse(contacted)
    }
    @Test fun boundedProofCannotShareAnUnlimitedIdentityResponse(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true, true, true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val transport = object : MusicBrainzTransport, BoundedMusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                attempts++
                entered.complete(Unit)
                release.await()
                return "full-body"
            }
            override suspend fun getLimited(
                path: String, parameters: Map<String, String>, userAgent: String,
                maxResponseBytes: Long,
            ): String {
                attempts++
                assertEquals(256L, maxResponseBytes)
                return "bounded-body"
            }
        }
        val client = MusicBrainzClient(
            transport = transport, admission = { gate },
            singleFlight = ProviderSingleFlight(),
            rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
        )
        val first = async {
            client.get("recording/example", capability = ProviderCapability.IDENTITY)
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.getLimited("recording/example", mapOf("fmt" to "json"), 256,
                capability = ProviderCapability.IDENTITY)
        }
        release.complete(Unit)
        assertEquals("full-body", first.await())
        assertEquals("bounded-body", second.await())
        assertEquals(2, attempts)
    }

}
