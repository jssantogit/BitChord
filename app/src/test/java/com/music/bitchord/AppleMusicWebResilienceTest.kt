package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebExchange
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpResponse
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebPolicyTransport
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebRateLimiter
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebRetryPolicy
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebToken
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTokenSource
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebResilienceTest {
    private val capability = ProviderCapability.CATALOG_SEARCH
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB

    private fun admitted(): RateLimitedProviderTransport {
        val descriptor = ProviderDescriptor(provider, setOf(capability),
            readiness = ProviderReadiness.READY)
        val policy = ProviderCapabilityPolicy(listOf(descriptor), mapOf(
            capability to listOf(ProviderChoice(provider, enabled = true, order = 0))
        ))
        val gate = ProviderPolicyGate(policy)
        return RateLimitedProviderTransport { gate }
    }

    private val token = AppleMusicWebTokenSource { _ ->
        AppleMusicWebToken("test-token", 9_000_000_000)
    }

    @Test fun globalRequestBudgetSpacesStartsWithoutBlockingThread() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val limiter = AppleMusicWebRateLimiter(
            minimumIntervalMs = 350,
            clockMs = { now },
            sleep = { milliseconds -> waits += milliseconds; now += milliseconds },
        )
        val launches = mutableListOf<Long>()
        repeat(3) {
            limiter.run { launches += now }
        }
        assertEquals(listOf(1000L, 1350L, 1700L), launches)
        assertEquals(listOf(350L, 350L), waits)
    }

    @Test fun retryPolicyNeverRetriesBlocksOrUnlimitedServerCooldown() {
        val policy = AppleMusicWebRetryPolicy(maxAttempts = 3, baseDelayMs = 200, maxDelayMs = 1500)
        assertNull(policy.delayFor(status = 403, attempt = 1))
        assertNull(policy.delayFor(status = 429, attempt = 1))
        assertEquals(200L, policy.delayFor(status = 503, attempt = 1))
        assertEquals(400L, policy.delayFor(status = 503, attempt = 2))
        assertNull(policy.delayFor(status = 503, attempt = 3))
        assertEquals(1000L, policy.delayFor(status = 503, attempt = 1, retryAfterSeconds = 1))
        assertNull(policy.delayFor(status = 503, attempt = 1, retryAfterSeconds = 1000))
        assertNull(policy.delayFor(status = 404, attempt = 1))
    }

    @Test fun transient503MayRetryTwiceUnderSameCapabilityAdmission() = runBlocking {
        var attempts = 0
        val fake = AppleMusicWebExchange { _, _, _, headers, _ ->
            assertEquals("Bearer test-token", headers["Authorization"])
            attempts++
            when (attempts) {
                1 -> AppleMusicWebHttpResponse(503, "untrusted upstream error")
                else -> AppleMusicWebHttpResponse(200, """{"data":[]}""")
            }
        }
        val transport = AppleMusicWebPolicyTransport(
            exchange = fake,
            tokens = token,
            admission = admitted(),
            limiter = AppleMusicWebRateLimiter(minimumIntervalMs = 0),
            retryPolicy = AppleMusicWebRetryPolicy(maxAttempts = 2, baseDelayMs = 0),
        )
        assertEquals("""{"data":[]}""", transport.get(
            capability, "/v1/catalog/br/search", mapOf("term" to "Jazz"),
        ))
        assertEquals(2, attempts)
    }

    @Test fun malformedRetryAfterHeaderNeverEnablesUnexpectedRetry() = runBlocking {
        for (value in listOf("soon", "Wed, 18 Nov 2026 01:00:00 GMT")) {
            var attempts = 0
            val exchange = AppleMusicWebExchange { _, _, _, _, _ ->
                attempts++
                AppleMusicWebHttpResponse(503, "private-upstream", mapOf("Retry-After" to value))
            }
            val client = AppleMusicWebPolicyTransport(
                exchange = exchange, tokens = token, admission = admitted(),
                limiter = AppleMusicWebRateLimiter(minimumIntervalMs = 0),
                retryPolicy = AppleMusicWebRetryPolicy(
                    maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 1000,
                ),
            )
            try {
                client.get(capability, "/v1/catalog/br/search", mapOf("term" to "Jazz"))
                fail("Unparsed Retry-After must not be silently ignored")
            } catch (error: AppleMusicWebHttpException) {
                assertEquals(503, error.status)
                assertEquals(1, attempts)
                assertFalse(error.toString().contains("private-upstream"))
            }
        }
    }

    @Test fun retryAfterOutsideSafetyBudgetFailsWithoutSecondRequest() = runBlocking {
        var attempts = 0
        val fake = AppleMusicWebExchange { _, _, _, _, _ ->
            attempts++
            AppleMusicWebHttpResponse(
                503, "secret", mapOf("Retry-After" to "3600"),
            )
        }
        val transport = AppleMusicWebPolicyTransport(
            exchange = fake, tokens = token, admission = admitted(),
            limiter = AppleMusicWebRateLimiter(minimumIntervalMs = 0),
            retryPolicy = AppleMusicWebRetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 1000),
        )
        try {
            transport.get(capability, "/v1/catalog/br/search", emptyMap())
            fail("Long Retry-After must fail closed")
        } catch (exc: AppleMusicWebHttpException) {
            assertEquals(503, exc.status)
            assertFalse(exc.toString().contains("secret"))
        }
        assertEquals(1, attempts)
    }
}
