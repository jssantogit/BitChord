package com.music.bitchord

import com.music.bitchord.data.providers.CatalogNativeIdentity
import com.music.bitchord.data.providers.CatalogSearchAdapter
import com.music.bitchord.data.providers.CatalogSearchAdapterReader
import com.music.bitchord.data.providers.CatalogSearchItem
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Backend-only adapter selection/read boundary. The existing #219
 * CatalogSearchFilterRoute remains the sole fallback and visibility planner.
 * No native vendor ID is converted into an iTunes or MusicBrainz alias.
 */
class CatalogSearchAdapterReaderTest {
    private val apple = ProviderKey("apple_music_web")
    private val mb = ProviderKey("musicbrainz")
    private val kind = CatalogSearchKind.ARTIST
    private val request = CatalogSearchRequest(kind, "Daft Punk", storefront = "br")

    private fun gate(
        order: List<ProviderKey> = listOf(mb, apple),
        enabled: Set<ProviderKey> = order.toSet(),
        blocked: Set<ProviderKey> = emptySet(),
    ): ProviderPolicyGate = ProviderPolicyGate(ProviderCapabilityPolicy(
        descriptors = listOf(mb, apple).map { key ->
            ProviderDescriptor(
                key = key,
                capabilities = setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = if (key in blocked) ProviderReadiness.BLOCKED else ProviderReadiness.READY,
            )
        },
        choices = mapOf(ProviderCapability.CATALOG_SEARCH to order.mapIndexed { i, key ->
            ProviderChoice(key, enabled = key in enabled, order = i)
        }),
    ))

    private fun page(
        provider: ProviderKey,
        nativeId: String = "123",
        storefront: String? = "br",
    ) = CatalogSearchPage(
        provider = provider, capability = ProviderCapability.CATALOG_SEARCH,
        storefront = storefront, kind = kind,
        items = listOf(CatalogSearchItem(
            identity = CatalogNativeIdentity(provider, kind, nativeId, storefront),
            title = "Daft Punk",
        )),
        page = 1, hasMore = false, partial = false,
    )

    private fun adapter(
        key: ProviderKey,
        onSearch: suspend (CatalogSearchRequest) -> ProviderCallResult<CatalogSearchPage>,
    ): CatalogSearchAdapter = object : CatalogSearchAdapter {
        override val key = key
        override val kinds = setOf(kind)
        override suspend fun search(request: CatalogSearchRequest) = onSearch(request)
    }

    @Test fun readsOnlyRequestedEligibleAdapterAndKeepsNativeIdentity() = runBlocking {
        var mbCalls = 0
        var appleCalls = 0
        val reader = CatalogSearchAdapterReader(gate(), listOf(
            adapter(mb) { mbCalls++; ProviderCallResult.Success(page(mb, "mb-native")) },
            adapter(apple) { appleCalls++; ProviderCallResult.Success(page(apple, "apple-native")) },
        ))
        assertEquals(listOf(mb, apple), reader.ordered(kind))
        val result = reader.read(apple, request)
        assertTrue(result is ProviderCallResult.Success)
        assertEquals("apple-native", (result as ProviderCallResult.Success).value.items.single().identity.nativeId)
        assertEquals(apple, result.value.items.single().identity.provider)
        assertEquals(0, mbCalls)
        assertEquals(1, appleCalls)
    }

    @Test fun blockedOrDisabledProviderIsNotCalledEvenWhenRegistered() = runBlocking {
        var calls = 0
        val source = adapter(apple) { calls++; ProviderCallResult.Success(page(apple)) }
        val blocked = CatalogSearchAdapterReader(
            gate(order = listOf(apple), blocked = setOf(apple)), listOf(source),
        )
        assertTrue(blocked.ordered(kind).isEmpty())
        assertEquals(ProviderCallResult.Denied, blocked.read(apple, request))
        val disabled = CatalogSearchAdapterReader(
            gate(order = listOf(apple), enabled = emptySet()), listOf(source),
        )
        assertEquals(ProviderCallResult.Denied, disabled.read(apple, request))
        assertEquals(0, calls)
    }

    @Test fun readerDoesNotInventFallbackOrCallOtherAdapter() = runBlocking {
        var secondaryCalls = 0
        val reader = CatalogSearchAdapterReader(gate(), listOf(
            adapter(mb) { ProviderCallResult.Failed(IllegalStateException("offline")) },
            adapter(apple) { secondaryCalls++; ProviderCallResult.Success(page(apple)) },
        ))
        assertTrue(reader.read(mb, request) is ProviderCallResult.Failed)
        assertEquals(0, secondaryCalls)
    }

    @Test fun priorityChangeBeforePublicationMakesOldResultStale() = runBlocking {
        val gate = gate()
        val reader = CatalogSearchAdapterReader(gate, listOf(
            adapter(mb) {
                gate.replace(ProviderCapabilityPolicy(
                    descriptors = listOf(mb, apple).map {
                        ProviderDescriptor(it, setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY)
                    },
                    choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                        ProviderChoice(apple, true, 0), ProviderChoice(mb, true, 1),
                    )),
                ))
                ProviderCallResult.Success(page(mb))
            },
            adapter(apple) { ProviderCallResult.Success(page(apple)) },
        ))
        assertEquals(ProviderCallResult.Stale, reader.read(mb, request))
        assertEquals(listOf(apple, mb), reader.ordered(kind))
    }

    @Test fun foreignIdentityOrWrongPageIsNeverPublished() = runBlocking {
        val source = adapter(apple) { ProviderCallResult.Success(page(mb)) }
        val reader = CatalogSearchAdapterReader(gate(), listOf(source))
        assertTrue(reader.read(apple, request) is ProviderCallResult.Failed)

        val wrongPage = adapter(apple) {
            ProviderCallResult.Success(page(apple).copy(page = 2))
        }
        assertTrue(CatalogSearchAdapterReader(gate(), listOf(wrongPage))
            .read(apple, request) is ProviderCallResult.Failed)
    }

    @Test fun callerCancellationIsNeverConvertedIntoNormalProviderFailure() = runBlocking {
        val reader = CatalogSearchAdapterReader(gate(), listOf(
            adapter(mb) { throw CancellationException("caller cancelled") },
        ))
        try {
            reader.read(mb, request)
            fail("Cancellation must be propagated")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
