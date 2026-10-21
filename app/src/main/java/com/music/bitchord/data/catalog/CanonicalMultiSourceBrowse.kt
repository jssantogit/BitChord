package com.music.bitchord.data.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read-only canonical projection across configured sources. Provider IDs are
 * only availability facets; RecordingId alone is music identity.
 */
internal interface CanonicalMultiSourceBrowseStore {
    fun observeTrackAssets(providerKeys: List<String>): Flow<List<CanonicalBrowseAssetRow>>
}

/**
 * Reuses the already-proven, provider-neutral canonical browse grouping from
 * PR #8 so multi-source cannot accidentally redefine identity by title/name.
 */
internal class CanonicalMultiSourceBrowseRepository(
    private val store: CanonicalMultiSourceBrowseStore,
) {
    fun observe(providerKeys: Set<String>): Flow<CanonicalBrowseLibrary> {
        val keys = providerKeys
            .onEach { require(it.isNotBlank()) { "Provider key cannot be blank" } }
            .distinct()
            .sorted()
        if (keys.isEmpty()) return flowOf(CanonicalBrowseLibrary.EMPTY)
        val adapter = object : CanonicalLibraryBrowseStore {
            override fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>> =
                store.observeTrackAssets(keys)
        }
        return CanonicalLibraryBrowseRepository(adapter).observe("multi-source")
    }
}

internal class RoomCanonicalMultiSourceBrowseStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalMultiSourceBrowseStore {
    override fun observeTrackAssets(providerKeys: List<String>): Flow<List<CanonicalBrowseAssetRow>> =
        dao.observeMultiSourceBrowseAssets(providerKeys)
}
