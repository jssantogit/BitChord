package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCacheBridge
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCachedValue
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebCacheBridgeTest {
    private val capability = ProviderCapability.CATALOG_SEARCH
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private fun gate(enabled: Boolean): ProviderPolicyGate {
        val descriptor = ProviderDescriptor(provider, setOf(capability),
            readiness = ProviderReadiness.READY)
        val choices = if (enabled) mapOf(
            capability to listOf(ProviderChoice(provider, enabled = true, order = 0))
        ) else emptyMap()
        return ProviderPolicyGate(ProviderCapabilityPolicy(listOf(descriptor), choices))
    }

    private fun record(
        key: String, outcome: AppleMusicWebOutcome<String> = AppleMusicWebOutcome.Complete("album"),
        market: String = "br", expiresAt: Long = 5000L,
    ) = AppleMusicWebCachedValue(
        key = key, provider = provider, capability = capability,
        storefront = market, language = "pt-BR",
        expiresAtMs = expiresAt, outcome = outcome,
    )

    @Test fun authorizedCompleteHitIsReturnedButProviderDisabledNeverReadsStorage() = runBlocking {
        val args = mapOf("term" to "Jazz", "types" to "albums")
        val key = com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCacheContract
            .key(capability, "br", "pt-BR", "/v1/catalog/br/search", args)!!
        var accessed = 0
        val enabled = gate(true)
        val bridge = AppleMusicWebCacheBridge(
            access = ProviderCachedReadAccess { enabled }, clockMs = { 1_000L },
        )
        val hit = bridge.read(capability, "br", "pt-BR",
            "/v1/catalog/br/search", args) { requestedKey ->
            accessed++
            assertEquals(key, requestedKey)
            record(requestedKey)
        }
        assertTrue(hit is AppleMusicWebOutcome.Complete)
        assertEquals("album", (hit as AppleMusicWebOutcome.Complete).value)
        assertEquals(1, accessed)

        enabled.replace(ProviderCapabilityPolicy(listOf(
            ProviderDescriptor(provider, setOf(capability), readiness = ProviderReadiness.READY),
        )))
        val denied = bridge.read(capability, "br", "pt-BR",
            "/v1/catalog/br/search", args) { requestedKey ->
            accessed++
            record(requestedKey)
        }
        assertNull(denied)
        assertEquals(1, accessed)
    }

    @Test fun mismatchedExpiredOrPartialRecordsAreNeverPublished() = runBlocking {
        val gate = gate(true)
        val bridge = AppleMusicWebCacheBridge(
            access = ProviderCachedReadAccess { gate }, clockMs = { 1000L },
        )
        val params = mapOf("term" to "Jazz")
        suspend fun tryCache(transform: (String) -> AppleMusicWebCachedValue<String>) =
            bridge.read(capability, "br", "pt-BR",
                "/v1/catalog/br/search", params) { transform(it) }
        assertNull(tryCache { record(it, market = "us") })
        assertNull(tryCache { record(it, expiresAt = 999L) })
        assertNull(tryCache { record(it, outcome = AppleMusicWebOutcome.Partial(
            "incomplete", com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCompleteness.PAGE_LIMIT,
        )) })
        assertNull(tryCache { record(it, outcome = AppleMusicWebOutcome.Unavailable("denied")) })
        assertNull(tryCache { record("other-cache-key") })
        assertNull(bridge.read<String>(capability, "br", "pt-BR",
            "/us/404", params) { fail("Non-catalog path must never read a cache"); null })
    }

    @Test fun revocationWhileCacheReadIsSuspendedSuppressesTheOldResult() = runBlocking {
        val gate = gate(true)
        val bridge = AppleMusicWebCacheBridge(
            access = ProviderCachedReadAccess { gate }, clockMs = { 1_000L },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deferred = async {
            bridge.read(capability, "br", "pt-BR",
                "/v1/catalog/br/search", mapOf("term" to "Jazz")) { key ->
                entered.complete(Unit)
                release.await()
                record(key)
            }
        }
        entered.await()
        gate.replace(ProviderCapabilityPolicy(listOf(
            ProviderDescriptor(provider, setOf(capability), readiness = ProviderReadiness.READY),
        )))
        release.complete(Unit)
        assertNull(deferred.await())
    }
}
