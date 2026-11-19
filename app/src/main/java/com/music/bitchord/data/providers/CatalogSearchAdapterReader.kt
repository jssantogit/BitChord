package com.music.bitchord.data.providers

import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * One provider-native read behind the #219 capability policy.
 *
 * The caller uses CatalogSearchFilterRoute to decide when another source may
 * be tried and CatalogPublicationGuard to decide if a screen still owns the
 * request. This class deliberately owns neither fallback nor UI publication.
 */
internal class CatalogSearchAdapterReader(
    private val gate: ProviderPolicyGate?,
    adapters: Collection<CatalogSearchAdapter>,
) {
    private val registered = adapters.toList().also { entries ->
        require(entries.map(CatalogSearchAdapter::key).distinct().size == entries.size) {
            "Duplicate catalog adapter for provider"
        }
    }

    /** Only supported, READY, authorized, operational sources in preference order. */
    fun ordered(kind: CatalogSearchKind): List<ProviderKey> =
        orderedCatalogSearchAdapters(gate, kind, registered).map(CatalogSearchAdapter::key)

    /**
     * Never select a different provider behind the caller's back. On a policy
     * reorder or opt-out, even a completed response is stale, not publishable.
     */
    suspend fun read(
        provider: ProviderKey,
        request: CatalogSearchRequest,
    ): ProviderCallResult<CatalogSearchPage> {
        val policy = gate ?: return ProviderCallResult.Denied
        val selected = orderedCatalogSearchAdapters(policy, request.kind, registered)
        val source = selected.firstOrNull { it.key == provider }
            ?: return ProviderCallResult.Denied
        val ticket = policy.open(ProviderCapability.CATALOG_SEARCH, provider)
            ?: return ProviderCallResult.Denied
        val originalOrder = selected.map(CatalogSearchAdapter::key)

        val response: ProviderCallResult<CatalogSearchPage> = try {
            source.search(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ProviderCallResult.Failed(failure)
        }

        if (!policy.accept(ticket) || ordered(request.kind) != originalOrder) {
            return ProviderCallResult.Stale
        }
        return when (response) {
            is ProviderCallResult.Success -> {
                val page = response.value
                if (!valid(page, provider, request)) {
                    ProviderCallResult.Failed(
                        IllegalStateException("Untrusted catalog page provenance"),
                    )
                } else response
            }
            ProviderCallResult.Denied -> ProviderCallResult.Denied
            ProviderCallResult.Stale -> ProviderCallResult.Stale
            is ProviderCallResult.Failed -> response
        }
    }

    private fun valid(
        page: CatalogSearchPage,
        provider: ProviderKey,
        request: CatalogSearchRequest,
    ): Boolean {
        if (page.provider != provider ||
            page.capability != ProviderCapability.CATALOG_SEARCH ||
            page.kind != request.kind ||
            page.page != request.page ||
            page.page < 1 ||
            (page.storefront != null &&
                !page.storefront.equals(request.storefront, ignoreCase = true))
        ) return false
        val scoped = HashSet<CatalogNativeIdentity>()
        return page.items.all { item ->
            val identity = item.identity
            identity.provider == provider &&
                identity.kind == request.kind &&
                identity.storefront == page.storefront &&
                identity.nativeId.isNotBlank() &&
                scoped.add(identity)
        }
    }
}
