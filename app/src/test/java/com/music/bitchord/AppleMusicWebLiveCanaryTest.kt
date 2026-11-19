package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebPolicyTransport
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
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Network-only canary. Not part of hermetic PR tests; intended for manual or
 * scheduled validation on the default branch after the native port is merged.
 * Never prints access tokens, raw payloads, or artist names.
 */
class AppleMusicWebLiveCanaryTest {
    @Test fun rotatingPublicSearchAndArtistDetailHonorRealAppleContracts() = runBlocking {
        assumeTrue("Enable only via dedicated live-canary workflow",
            System.getenv("APPLE_MUSIC_WEB_LIVE") == "1")
        val seed = System.getenv("APPLE_MUSIC_WEB_CANARY_SEED")
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val queries = listOf("Shakira", "Miles Davis", "Gilberto Gil", "BTS", "Daft Punk")
        val countries = listOf("br", "fr", "us", "jp", "in")
        val capabilityKeys = setOf(
            ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
        // An isolated test-only gate does not change app inventory/readiness.
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key = BuiltInProviderKeys.APPLE_MUSIC_WEB,
                capabilities = capabilityKeys,
                readiness = ProviderReadiness.READY,
            )),
            choices = capabilityKeys.associateWith { cap ->
                listOf(ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, true, 0))
            },
        )
        val gate = ProviderPolicyGate(policy)
        val catalog = AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        ))
        for (offset in 0..1) {
            val country = countries[(seed + offset) % countries.size]
            val query = queries[(seed + offset) % queries.size]
            val result = catalog.search(AppleMusicWebKind.ARTIST, query, country, limit = 5)
            assertTrue("No Apple artists returned for $country", result.items.isNotEmpty())
            val first = result.items.first()
            assertTrue(first.scopedId.startsWith("apple_music_web:$country:artist:"))
            assertFalse(first.playable)
            val overview = catalog.artist(first.id, country)
            assertEquals(first.scopedId, overview.identity.scopedId)
        }
    }
}
