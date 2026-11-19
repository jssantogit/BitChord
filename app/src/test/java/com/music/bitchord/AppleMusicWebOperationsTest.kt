package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOperationRegistry
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebPolicyTransport
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebExchange
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpResponse
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

/** Operation identifiers must enforce their capability at the HTTP boundary. */
class AppleMusicWebOperationsTest {
    @Test fun onlySearchPathsBelongToCatalogSearch() {
        val allowed = listOf(
            "/v1/catalog/br/search",
            "/v1/catalog/us/search",
        )
        val denied = listOf(
            "/v1/catalog/br/artists/42",
            "/v1/catalog/br/albums/42",
            "/v1/catalog/br/albums/42/relationships/tracks",
            "/v1/editorial/br/groupings",
            "/v1/catalog/br/playlists/pl.123456",
            "/v1/catalog/br/search/../albums/42",
            "/v1/catalog/bra/search",
            "/v1/catalog/br/search?term=secret",
        )
        allowed.forEach { assertTrue("Search route $it should be allowed",
            AppleMusicWebOperationRegistry.allows(ProviderCapability.CATALOG_SEARCH, it)) }
        denied.forEach { assertFalse("Escaping search boundary $it",
            AppleMusicWebOperationRegistry.allows(ProviderCapability.CATALOG_SEARCH, it)) }
    }

    @Test fun discographyAllowsOnlyOwnedArtistAlbumAndViewRoutes() {
        val allowed = listOf(
            "/v1/catalog/br/artists/42",
            "/v1/catalog/br/artists/42/albums",
            "/v1/catalog/br/artists/42/view/top-songs",
            "/v1/catalog/br/artists/42/view/full-albums",
            "/v1/catalog/br/albums/99",
            "/v1/catalog/br/albums/99/tracks",
            "/v1/catalog/br/albums/99/relationships/tracks",
        )
        val denied = listOf(
            "/v1/catalog/br/search",
            "/v1/catalog/br/songs/42",
            "/v1/editorial/br/groupings",
            "/v1/catalog/br/artists/42/view/../../search",
            "/v1/catalog/br/artists/42/relationships/library",
            "/v1/catalog/br/albums/99/relationships/stream",
            "/v1/catalog/br/albums/99/../100",
            "/v1/catalog/br/albums/0abc",
        )
        allowed.forEach { assertTrue("Expected valid discography path $it",
            AppleMusicWebOperationRegistry.allows(ProviderCapability.EDITORIAL_DISCOGRAPHY, it)) }
        denied.forEach { assertFalse("Unauthorized operation $it",
            AppleMusicWebOperationRegistry.allows(ProviderCapability.EDITORIAL_DISCOGRAPHY, it)) }
    }

    @Test fun permittedSearchCannotHijackOtherCapabilitiesBeforeAnyTokenOrNetwork() = runBlocking {
        val p = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val cap = ProviderCapability.CATALOG_SEARCH
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key = p, capabilities = setOf(cap), readiness = ProviderReadiness.READY,
            )),
            choices = mapOf(cap to listOf(ProviderChoice(p, true, 0))),
        ))
        var tokenCalls = 0
        var networkCalls = 0
        val adapter = AppleMusicWebPolicyTransport(
            exchange = AppleMusicWebExchange { _, _, _, _, _ ->
                networkCalls++
                AppleMusicWebHttpResponse(200, "{}")
            },
            tokens = AppleMusicWebTokenSource { _ ->
                tokenCalls++
                AppleMusicWebToken("synthetic", 9_000_000_000L)
            },
            admission = RateLimitedProviderTransport { gate },
        )
        try {
            adapter.get(cap, "/v1/catalog/br/albums/123", emptyMap())
            fail("Search authorization must not grant discography access")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, tokenCalls)
            assertEquals(0, networkCalls)
        }
    }
}
