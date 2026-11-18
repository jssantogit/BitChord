package com.music.bitchord.ui

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind

/** A source tab has one real identity, never a blended Server catch-all. */
internal data class SearchProviderTab(
    val key: String,
    val label: String,
    val source: SearchSource,
)

/**
 * The catalog is public metadata. Individual server APIs only appear after
 * they have been configured/recognized; Local requires actual readable tracks.
 * This is a pure presentation policy, not an authentication or playback gate.
 */
internal fun availableSearchProviderTabs(
    configs: List<SourceConfig>,
    remoteProviderKeys: List<String>,
    hasLocalTracks: Boolean,
    catalogLabel: String,
    localLabel: String,
): List<SearchProviderTab> {
    val remote = remoteProviderKeys.asSequence()
        .distinct()
        .mapNotNull { key ->
            val label = when {
                key.startsWith("opensubsonic:") && key.length > "opensubsonic:".length ->
                    "Navidrome"
                key.startsWith("jellyfin:") && key.length > "jellyfin:".length ->
                    "Jellyfin"
                else -> null
            } ?: return@mapNotNull null
            SearchProviderTab("remote:$key", label, SearchSource.ADDONS)
        }
        .toList()
    // Only AddonSource instances support the addon search contract. Module and
    // retired YouTube configs are not alternate tabs, even if persisted.
    val addons = configs.asSequence()
        .filter { it.kind == SourceKind.ADDON && it.enabled && it.isComplete &&
            !it.awaitsLosslessOutput }
        .distinctBy { it.id }
        .map { SearchProviderTab("addon:${it.id}", it.displayName, SearchSource.ADDONS) }
        .toList()

    val candidates = remote + addons
    val totalByLabel = candidates.groupingBy { it.label.lowercase() }.eachCount()
    val seenByLabel = mutableMapOf<String, Int>()
    val named = candidates.map { tab ->
        val normalized = tab.label.lowercase()
        val ordinal = (seenByLabel[normalized] ?: 0) + 1
        seenByLabel[normalized] = ordinal
        if (totalByLabel[normalized] == 1) tab
        else tab.copy(label = "${tab.label} $ordinal")
    }
    return buildList {
        add(SearchProviderTab("catalog", catalogLabel, SearchSource.CANONICAL))
        addAll(named)
        if (hasLocalTracks) add(SearchProviderTab("local", localLabel, SearchSource.LIBRARY))
    }
}
