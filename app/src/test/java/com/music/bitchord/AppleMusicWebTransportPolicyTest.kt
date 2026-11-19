package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebExchange
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHost
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpResponse
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebPolicyTransport
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

class AppleMusicWebTransportPolicyTest {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val search = ProviderCapability.CATALOG_SEARCH
    private val disco = ProviderCapability.EDITORIAL_DISCOGRAPHY

    private fun policy(enabled: Set<ProviderCapability>): ProviderCapabilityPolicy =
        ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key = provider, capabilities = setOf(search, disco),
                readiness = ProviderReadiness.READY,
            )),
            choices = enabled.associateWith { capability ->
                listOf(ProviderChoice(provider, enabled = true, order = 0))
            },
        )

    private class FakeToken : AppleMusicWebTokenSource {
        val calls = mutableListOf<Boolean>()
        override suspend fun token(forceRefresh: Boolean): AppleMusicWebToken {
            calls += forceRefresh
            return AppleMusicWebToken(
                value = if (forceRefresh) "refreshed" else "old",
                expiresAtEpochSeconds = 9_000_000_000,
            )
        }
    }

    @Test fun deniedSearchNeverAttemptsTokenOrNetwork() = runBlocking {
        val gate = ProviderPolicyGate(policy(emptySet()))
        val token = FakeToken()
        var calls = 0
        val fake = AppleMusicWebExchange { _, _, _, _, _ ->
            calls++
            AppleMusicWebHttpResponse(200, "{}")
        }
        val transport = AppleMusicWebPolicyTransport(fake, token,
            RateLimitedProviderTransport { gate })
        try {
            transport.get(search, "/v1/catalog/br/search", mapOf("term" to "Jazz"))
            fail("Disabled capability must reject request")
        } catch (_: Exception) {
            assertEquals(0, calls)
            assertTrue(token.calls.isEmpty())
        }
    }

    @Test fun permittedSearchCanRunButSameProviderDiscographyRemainsDisabled() = runBlocking {
        val gate = ProviderPolicyGate(policy(setOf(search)))
        val token = FakeToken()
        val calls = mutableListOf<Pair<AppleMusicWebHost, String>>()
        val fake = AppleMusicWebExchange { host, path, _, _, _ ->
            calls += host to path
            AppleMusicWebHttpResponse(200, """{"results":{}}""")
        }
        val transport = AppleMusicWebPolicyTransport(fake, token,
            RateLimitedProviderTransport { gate })
        assertEquals("""{"results":{}}""", transport.get(
            search, "/v1/catalog/br/search", mapOf("term" to "Jazz"),
        ))
        assertEquals(AppleMusicWebHost.API, calls.single().first)
        try {
            transport.get(disco, "/v1/catalog/br/artists/31", emptyMap())
            fail("Discography is independently disabled")
        } catch (_: Exception) {
            assertEquals(1, calls.size)
        }
    }

    @Test fun unauthorizedJwtRefreshesExactlyOnceWithinAuthorizedRequest() = runBlocking {
        val gate = ProviderPolicyGate(policy(setOf(search)))
        val token = FakeToken()
        val headersSeen = mutableListOf<String?>()
        val fake = AppleMusicWebExchange { _, _, _, headers, _ ->
            headersSeen += headers["Authorization"]
            if (headersSeen.size == 1) AppleMusicWebHttpResponse(401, "")
            else AppleMusicWebHttpResponse(200, """{"data":[]}""")
        }
        val transport = AppleMusicWebPolicyTransport(fake, token,
            RateLimitedProviderTransport { gate })
        assertEquals("""{"data":[]}""", transport.get(search,
            "/v1/catalog/br/search", mapOf("term" to "Jazz")))
        assertEquals(listOf(false, true), token.calls)
        assertEquals(listOf("Bearer old", "Bearer refreshed"), headersSeen)
    }

    @Test fun forbiddenAndRateLimitedResponsesNeverRetryOrDiscloseBody() = runBlocking {
        for (status in listOf(403, 429)) {
            val gate = ProviderPolicyGate(policy(setOf(search)))
            val token = FakeToken()
            var requests = 0
            val transport = AppleMusicWebPolicyTransport(
                AppleMusicWebExchange { _, _, _, _, _ ->
                    requests++
                    AppleMusicWebHttpResponse(status, "secret upstream body")
                },
                token, RateLimitedProviderTransport { gate },
            )
            try {
                transport.get(search, "/v1/catalog/br/search", emptyMap())
                fail("HTTP $status must fail closed")
            } catch (exc: AppleMusicWebHttpException) {
                assertEquals(status, exc.status)
                assertFalse(exc.toString().contains("secret"))
                assertEquals(1, requests)
                assertEquals(listOf(false), token.calls)
            }
        }
    }

    @Test fun rejectsNonCatalogHostsAndPathTraversalBeforeTransport() = runBlocking {
        val gate = ProviderPolicyGate(policy(setOf(search)))
        val token = FakeToken()
        var requests = 0
        val transport = AppleMusicWebPolicyTransport(
            AppleMusicWebExchange { _, _, _, _, _ ->
                requests++
                AppleMusicWebHttpResponse(200, "{}")
            },
            token, RateLimitedProviderTransport { gate },
        )
        for (path in listOf(
            "https://evil.example/v1/catalog/br/search",
            "/v1/catalog/br/../../me", "/v1/catalog/br/search?token=leak",
        )) {
            try {
                transport.get(search, path, emptyMap())
                fail("Unsafe URL must not be dispatched")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, requests)
        assertTrue(token.calls.isEmpty())
    }
}
