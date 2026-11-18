package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsClientContractTest {
    @Test fun editionGateBlocksAllRealLookupEntryPointsBeforeHttp() = runBlocking {
        val discogs = BuiltInProviderKeys.DISCOGS
        val descriptors = listOf(BuiltInProviderDescriptors.discogs(
            tokenConfigured = true, implemented = true,
        ))
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = mapOf(ProviderCapability.EDITION_METADATA to listOf(
                ProviderChoice(discogs, enabled, 0),
            )),
        )
        val gate = ProviderPolicyGate(policy(false))
        var contacts = 0
        val client = DiscogsClient(
            enabled = true,
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    contacts++
                    return DiscogsHttpResponse(404, "")
                }
            },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0),
            admission = RateLimitedProviderTransport { gate },
        )
        assertEquals(DiscogsLookupResult.Disabled, client.fetchMaster(10))
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(10))
        assertEquals(DiscogsMasterVersionsResult.Disabled, client.fetchMasterVersions(10))
        assertEquals(0, contacts)
        gate.replace(policy(true))
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(10))
        assertEquals(1, contacts)
        gate.replace(policy(false))
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(10))
        assertEquals(1, contacts)
    }

    @Test fun productionDisabledByDefaultNeverCallsTheNetwork() = runBlocking {
        var requests = 0
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                requests++
                return DiscogsHttpResponse(200, "{}")
            }
        })
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(42))
        assertEquals(0, requests)
    }

    @Test fun exactEditionRequestIsBoundedAndNeverDownloadsImagesOrMarketplaceData() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                calls += path
                assertTrue(userAgent.startsWith("BitChord/"))
                return DiscogsHttpResponse(200, """{"id":42,"title":"Album",
                    "artists":[{"name":"Artist"}],"images":[{"uri":"https://restricted.example/art.jpg"}],
                    "marketplace":{"price":999}}""")
            }
        }
        val client = DiscogsClient(transport = transport, enabled = true)
        val result = client.fetchRelease(42)
        assertTrue(result is DiscogsLookupResult.Found)
        val record = (result as DiscogsLookupResult.Found).record
        assertEquals(listOf("Artist"), record.artistNames)
        assertFalse(record.toString().contains("restricted.example"))
        assertEquals(listOf("releases/42"), calls)
        assertEquals(DiscogsLookupResult.InvalidId, client.fetchRelease(-2))
    }

    @Test fun rateLimitAndAuthenticationFailuresAreNotConflatedWithNotFound() = runBlocking {
        val responses = ArrayDeque(listOf(
            DiscogsHttpResponse(429, "", retryAfterSeconds = 12),
            DiscogsHttpResponse(401, ""),
            DiscogsHttpResponse(403, ""),
            DiscogsHttpResponse(404, ""),
            DiscogsHttpResponse(503, ""),
            DiscogsHttpResponse(200, "<html>forbidden</html>"),
        ))
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                responses.removeFirst()
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        assertEquals(DiscogsLookupResult.Throttled(12), client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unauthorized, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unauthorized, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unavailable, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Malformed, client.fetchRelease(42))
    }

    @Test fun cancellationIsNotReportedAsAnUnavailableRemoteService() = runBlocking {
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                throw CancellationException("navigated back")
            }
        }, enabled = true)
        assertTrue(runCatching { client.fetchRelease(1) }.exceptionOrNull()
            is CancellationException)
    }

    @Test fun limiterSpacesRequestsWithoutPerEndpointBypass() = runBlocking {
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it }, minimumIntervalMs = 3_000L,
        )
        repeat(3) {
            limiter.runLimited { starts += now }
        }
        assertEquals(listOf(0L, 3_000L, 6_000L), starts)
    }

    @Test fun sourceOnlyCacheFailsClosedOnExpiredEvidenceAndClockRollback() {
        val link = DiscogsLink(DiscogsKind.RELEASE, 42)
        val record = DiscogsRecord(link, "Album", listOf("Artist"), "KR", 2024,
            emptyList(), emptyList(), emptyList(), emptyList(), false)
        val cache = DiscogsMemoryEvidenceStore(maxEntries = 2)
        val first = DiscogsObservation("541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            DiscogsKind.RELEASE, record, 1_000L)
        cache.put(first)
        assertEquals(first, cache.get(first.canonicalMbid, link, 1_001L))
        assertNull(cache.get(first.canonicalMbid, link, 999L))
        assertNull(cache.get(first.canonicalMbid, link,
            first.fetchedAtMillis + DiscogsObservation.TTL_MILLIS))
    }
}
