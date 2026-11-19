package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebEditorialGateway
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebSearchAdapter
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.CatalogSearchAdapter
import com.music.bitchord.data.providers.CatalogSearchKind
import com.music.bitchord.data.providers.CatalogSearchPage
import com.music.bitchord.data.providers.CatalogSearchRequest
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.orderedCatalogSearchAdapters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebGenericRoutingTest {
    private val apple = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val itunes = BuiltInProviderKeys.ITUNES

    private fun gate(
        order: List<ProviderKey>,
        enabled: Set<ProviderKey> = order.toSet(),
    ): ProviderPolicyGate {
        val keys = listOf(mb, itunes, apple)
        val descriptors = keys.map {
            ProviderDescriptor(it, setOf(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ), ProviderReadiness.READY)
        }
        return ProviderPolicyGate(ProviderCapabilityPolicy(descriptors, mapOf(
            ProviderCapability.CATALOG_SEARCH to order.mapIndexed { i, key ->
                ProviderChoice(key, enabled = key in enabled, order = i)
            },
            ProviderCapability.EDITORIAL_DISCOGRAPHY to keys.mapIndexed { i, key ->
                ProviderChoice(key, enabled = true, order = i)
            },
        )))
    }

    private fun fake(provider: ProviderKey): CatalogSearchAdapter =
        object : CatalogSearchAdapter {
            override val key = provider
            override val kinds = setOf(CatalogSearchKind.ARTIST, CatalogSearchKind.ALBUM)
            override suspend fun search(
                request: CatalogSearchRequest,
            ): ProviderCallResult<CatalogSearchPage> =
                ProviderCallResult.Denied
        }

    @Test fun genericCatalogOrderFollowsUserChoiceNotAnApplePriority() {
        val adapters = listOf(fake(apple), fake(itunes), fake(mb))
        assertEquals(listOf(mb, itunes, apple), orderedCatalogSearchAdapters(
            gate(listOf(mb, itunes, apple)), CatalogSearchKind.ARTIST, adapters,
        ).map { it.key })
        assertEquals(listOf(apple, mb, itunes), orderedCatalogSearchAdapters(
            gate(listOf(apple, mb, itunes)), CatalogSearchKind.ARTIST, adapters,
        ).map { it.key })
    }

    @Test fun disabledOrUnsupportedProviderCannotBeInsertedAsHiddenFallback() {
        val adapters = listOf(fake(mb), fake(apple))
        assertEquals(listOf(mb), orderedCatalogSearchAdapters(
            gate(listOf(apple, mb), enabled = setOf(mb)),
            CatalogSearchKind.ALBUM, adapters,
        ).map { it.key })
        assertTrue(orderedCatalogSearchAdapters(
            null, CatalogSearchKind.ARTIST, adapters,
        ).isEmpty())
        assertTrue(orderedCatalogSearchAdapters(
            gate(listOf(apple, mb)), CatalogSearchKind.TRACK, adapters,
        ).isEmpty())
        assertTrue(orderedCatalogSearchAdapters(
            gate(listOf(apple, mb), enabled = emptySet()),
            CatalogSearchKind.ARTIST, adapters,
        ).isEmpty())
    }

    @Test fun appleWebAdapterPublishesNativeIdentityNeverAnItunesAlias() = runBlocking {
        val calls = mutableListOf<String>()
        val source = AppleMusicWebTransport { capability, path, _ ->
            assertEquals(ProviderCapability.CATALOG_SEARCH, capability)
            calls += path
            """{"results":{"artists":{"data":[{"id":"121","type":"artists",
                "attributes":{"name":"Band","url":"https://music.apple.com/br/artist/id121"}}],
                "next":"/v1/catalog/br/search?offset=9"}}}"""
        }
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(source), gate(listOf(apple, itunes, mb)),
        ))
        val result = adapter.search(CatalogSearchRequest(
            CatalogSearchKind.ARTIST, "Band", "br",
        ))
        assertTrue(result is ProviderCallResult.Success)
        val page = (result as ProviderCallResult.Success).value
        assertEquals(apple, page.provider)
        assertEquals(ProviderCapability.CATALOG_SEARCH, page.capability)
        assertEquals("br", page.storefront)
        assertEquals(apple, page.items.single().identity.provider)
        assertEquals("121", page.items.single().identity.nativeId)
        assertEquals("br", page.items.single().identity.storefront)
        assertEquals(CatalogSearchKind.ARTIST, page.items.single().identity.kind)
        assertTrue(page.hasMore)
        assertFalse(page.partial)
        assertEquals(listOf("/v1/catalog/br/search"), calls)
    }

    @Test fun unauthorizedAppleWebAdapterCannotDispatch() = runBlocking {
        var calls = 0
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                calls++
                "{}"
            }), gate(listOf(apple, mb), enabled = setOf(mb)),
        ))
        assertEquals(ProviderCallResult.Denied, adapter.search(
            CatalogSearchRequest(CatalogSearchKind.ALBUM, "Record", "br"),
        ))
        assertEquals(0, calls)
    }
}
