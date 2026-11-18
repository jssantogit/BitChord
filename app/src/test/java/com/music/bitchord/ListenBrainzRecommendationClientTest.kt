package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationHttpResponse
import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import com.music.bitchord.data.providers.*

class ListenBrainzRecommendationClientTest {
    private val mbid = "526bd613-fddd-4bd6-9137-ab709ac74cab"
    private val now = 1_783_000_000_000L

    private class FakeTransport : ListenBrainzRecommendationTransport {
        var validates = 0
        var recommendations = 0
        var username = "listener"
        var responseCode = 200
        var body = """{"payload":{"entity":"recording","user_name":"listener",
             "last_updated":1782999900,"mbids":[
             {"recording_mbid":"526bd613-fddd-4bd6-9137-ab709ac74cab","score":1.8}]}}"""
        override suspend fun validateToken(token: String): ListenBrainzRecommendationHttpResponse {
            validates++
            return ListenBrainzRecommendationHttpResponse(
                200, """{"code":200,"valid":true,"user_name":"listener"}""",
            )
        }
        override suspend fun recommendations(userName: String, count: Int): ListenBrainzRecommendationHttpResponse {
            recommendations++
            username = userName
            return ListenBrainzRecommendationHttpResponse(responseCode, body)
        }
    }

    @Test fun recommendationOptOutHidesCachedResultsWithoutRevokingScrobbling() = runBlocking {
        val key = BuiltInProviderKeys.LISTENBRAINZ
        val descriptors = listOf(ProviderDescriptor(key, setOf(
            ProviderCapability.PERSONAL_RECOMMENDATIONS,
            ProviderCapability.LISTENING_STATISTICS,
        ), ProviderReadiness.READY))
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = mapOf(
                ProviderCapability.PERSONAL_RECOMMENDATIONS to listOf(
                    ProviderChoice(key, enabled, 0)),
                ProviderCapability.LISTENING_STATISTICS to listOf(
                    ProviderChoice(key, true, 0)),
            ),
        )
        val gate = ProviderPolicyGate(policy(true))
        val transport = FakeTransport()
        val client = ListenBrainzRecommendationClient(
            transport = transport,
            limiter = ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now }, retryDelay = {},
            gateProvider = { gate },
        )
        assertTrue(client.recordings("token", 10) is ListenBrainzRecommendationFetchResult.Success)
        gate.replace(policy(false))
        assertEquals(ListenBrainzRecommendationFetchResult.Unavailable,
            client.recordings("token", 10))
        assertEquals(1, transport.recommendations)
        assertTrue(gate.open(ProviderCapability.LISTENING_STATISTICS, key) != null)
        gate.replace(policy(true))
        assertTrue(client.recordings("token", 10) is ListenBrainzRecommendationFetchResult.Success)
        assertEquals(1, transport.recommendations)
    }

    @Test fun revokingRecommendationsCancelsInflightValidation() = runBlocking {
        val key = BuiltInProviderKeys.LISTENBRAINZ
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key, setOf(ProviderCapability.PERSONAL_RECOMMENDATIONS),
                ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.PERSONAL_RECOMMENDATIONS to listOf(
                ProviderChoice(key, enabled, 0))),
        )
        val gate = ProviderPolicyGate(policy(true))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val transport = object : ListenBrainzRecommendationTransport {
            override suspend fun validateToken(token: String): ListenBrainzRecommendationHttpResponse {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
            override suspend fun recommendations(userName: String, count: Int) =
                ListenBrainzRecommendationHttpResponse(204, "")
        }
        val client = ListenBrainzRecommendationClient(
            transport = transport,
            limiter = ListenBrainzRateLimiter(waitMillis = {}),
            gateProvider = { gate },
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) { client.recordings("token") }
        withTimeout(5_000) { entered.await() }
        gate.replace(policy(false))
        withTimeout(5_000) { cancelled.await() }
        assertEquals(ListenBrainzRecommendationFetchResult.Unavailable,
            withTimeout(5_000) { pending.await() })
    }

    @Test fun realAccountValidationAndMatchingRecordingMbidsAreRequired() = runBlocking {
        val transport = FakeTransport()
        val client = ListenBrainzRecommendationClient(
            transport = transport,
            limiter = ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
            retryDelay = {},
        )
        val result = client.recordings("local-secret-token", count = 10)
        assertTrue(result is ListenBrainzRecommendationFetchResult.Success)
        assertEquals("listener", transport.username)
        assertEquals(listOf(mbid), (result as ListenBrainzRecommendationFetchResult.Success).items.map { it.recordingMbid })
        assertEquals(1, transport.validates)
        assertEquals(1, transport.recommendations)
        assertTrue(client.recordings("local-secret-token", 10) is ListenBrainzRecommendationFetchResult.Success)
        assertEquals(1, transport.recommendations) // cached, no repeat network
    }

    @Test fun noRecommendationsAreNegativelyCachedForFifteenMinutesPerAccount() = runBlocking {
        var time = now
        val transport = FakeTransport().apply { responseCode = 204; body = "" }
        val client = ListenBrainzRecommendationClient(
            transport, ListenBrainzRateLimiter(nowMillis = { time }, waitMillis = {}),
            nowMillis = { time }, retryDelay = {},
        )
        assertEquals(ListenBrainzRecommendationFetchResult.NoRecommendations, client.recordings("first", 10))
        assertEquals(ListenBrainzRecommendationFetchResult.NoRecommendations, client.recordings("first", 10))
        assertEquals(1, transport.validates)
        assertEquals(1, transport.recommendations)

        // No model for one account must not suppress the other account's discovery.
        assertEquals(ListenBrainzRecommendationFetchResult.NoRecommendations, client.recordings("second", 10))
        assertEquals(2, transport.recommendations)
        time += 15L * 60L * 1_000L
        assertEquals(ListenBrainzRecommendationFetchResult.NoRecommendations, client.recordings("second", 10))
        assertEquals(3, transport.recommendations)
    }

    @Test fun ungeneratedRecommendationsAreDistinctFromMalformedContent() = runBlocking {
        val transport = FakeTransport().apply { responseCode = 204; body = "" }
        val client = ListenBrainzRecommendationClient(
            transport, ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now }, retryDelay = {},
        )
        assertEquals(ListenBrainzRecommendationFetchResult.NoRecommendations, client.recordings("token"))
        assertFalse(client.recordings("token") is ListenBrainzRecommendationFetchResult.Success)
    }
}
