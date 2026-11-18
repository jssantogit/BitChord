package com.music.bitchord.data.providers

/**
 * Distinguishes personal/local ownership and independent multi-source
 * corroboration from single-provider editorial cache entries.
 *
 * Disabling an editorial provider (e.g., MusicBrainz CATALOG_SEARCH) must
 * hide rows whose ONLY backing is that disabled editorial source, while
 * preserving local MediaAssets, user ratings, and facts corroborated by
 * another active provider even when those entities carry an MBID.
 */
sealed interface CatalogEntryProvenance {
    /** Backed by a verified local file, download, or authorized personal server asset. */
    data object LocalOrPersonalAsset : CatalogEntryProvenance

    /** Explicitly rated, pinned, or added to a playlist by the user. */
    data object UserCurated : CatalogEntryProvenance

    /** Independently verified by an active provider instance for a capability. */
    data class ProviderEditorial(
        val provider: ProviderKey,
        val capability: ProviderCapability,
    ) : CatalogEntryProvenance
}

data class ProvenanceScopedItem<T>(
    val value: T,
    val mbid: String? = null,
    val provenances: Set<CatalogEntryProvenance>,
)

internal object CatalogProvenanceFilter {
    fun <T> filterVisible(
        items: List<ProvenanceScopedItem<T>>,
        gate: ProviderPolicyGate?,
    ): List<T> = items.mapNotNull { item ->
        val visible = item.provenances.any { provenance ->
            when (provenance) {
                CatalogEntryProvenance.LocalOrPersonalAsset,
                CatalogEntryProvenance.UserCurated,
                -> true

                is CatalogEntryProvenance.ProviderEditorial ->
                    gate?.open(provenance.capability, provenance.provider) != null
            }
        }
        if (visible) item.value else null
    }
}
