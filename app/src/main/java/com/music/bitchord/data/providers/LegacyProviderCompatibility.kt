package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

internal data class LegacyLyricsProjection(
    val order: List<LyricsSource>,
    val enabled: Set<LyricsSource>,
)

/**
 * Single-authority compatibility bridge for legacy lyrics and extension callers.
 *
 * Avoids dual-write across independent SharedPreferences: once migrated into
 * [ProviderCapabilitySession], reads project exclusively from
 * [ProviderSessionSnapshot] and writes mutate only [ProviderCapabilitySession].
 * Private URLs and credentials remain exclusively in their connection stores.
 */
internal object LegacyProviderCompatibility {
    private val offeredLyricsById: Map<String, LyricsSource> =
        LyricsSource.offered.associateBy { it.name.lowercase(Locale.ROOT) }

    /**
     * One-time idempotent migration combining MusicBrainz baseline access with
     * legacy lyric and extension choices in a single atomic initialization.
     */
    suspend fun migrateAllLegacy(
        session: ProviderCapabilitySession,
        lyricsOrder: List<LyricsSource>,
        enabledLyrics: Set<LyricsSource>,
        sources: List<SourceConfig>,
        includeMusicBrainzDefaults: Boolean = true,
    ): Boolean {
        val imported = mutableMapOf<ProviderCapability, List<ProviderChoice>>()
        if (includeMusicBrainzDefaults) {
            val mb = ProviderKey("musicbrainz")
            listOf(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                ProviderCapability.IDENTITY,
            ).forEach { capability ->
                imported[capability] = listOf(ProviderChoice(mb, enabled = true, order = 0))
            }
        }
        imported.putAll(legacyProviderChoices(lyricsOrder, enabledLyrics, sources))
        return session.initialize(imported)
    }

    fun projectLyrics(
        snapshot: ProviderSessionSnapshot,
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): LegacyLyricsProjection {
        val configured = snapshot.configured[ProviderCapability.LYRICS_LOOKUP]
        if (configured == null) {
            val safeOrder = fallbackOrder.filter { it in LyricsSource.offered }.distinct().let {
                it + LyricsSource.offered.filterNot { source -> source in it }
            }
            val safeEnabled = if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) {
                emptySet()
            } else {
                fallbackEnabled.intersect(LyricsSource.offered.toSet())
            }
            return LegacyLyricsProjection(safeOrder, safeEnabled)
        }
        val orderedFromStore = configured
            .sortedBy(ProviderChoice::order)
            .mapNotNull { offeredLyricsById[it.key.providerId] }
            .distinct()
        val fullOrder = orderedFromStore + LyricsSource.offered.filterNot { it in orderedFromStore }
        // Effective eligibility is instance-scoped. A failed write for one
        // source may not hide a sibling still admitted by the policy gate.
        val eligibleIds = snapshot.eligible[ProviderCapability.LYRICS_LOOKUP]
            .orEmpty()
            .mapTo(HashSet()) { it.providerId }
        val enabled = fullOrder.filterTo(LinkedHashSet()) {
            it.name.lowercase(Locale.ROOT) in eligibleIds
        }
        return LegacyLyricsProjection(fullOrder, enabled)
    }

    suspend fun writeLyrics(
        session: ProviderCapabilitySession,
        order: List<LyricsSource>,
        enabled: Set<LyricsSource>,
    ): Boolean {
        val choices = legacyProviderChoices(order, enabled, emptyList())[ProviderCapability.LYRICS_LOOKUP]
            .orEmpty()
        return session.update(ProviderCapability.LYRICS_LOOKUP, choices)
    }

    fun projectExtensions(
        snapshot: ProviderSessionSnapshot,
        rawSources: List<SourceConfig>,
    ): List<SourceConfig> {
        val extensions = rawSources.filter {
            it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE
        }
        val configured = snapshot.configured[ProviderCapability.EXTENSION_PLAYBACK]
            ?: return if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) {
                extensions.map { it.copy(enabled = false) }
            } else {
                extensions
            }
        // Do not globally blank an entire capability on an instance-local
        // failure: unaffected addons stay authorized.
        val eligibleKeys = snapshot.eligible[ProviderCapability.EXTENSION_PLAYBACK]
            .orEmpty().toSet()
        val savedOrder = configured.associate { it.key to it.order }
        return extensions.map { config ->
            val key = ProviderKey(
                providerId = if (config.kind == SourceKind.ADDON) "addon" else "custom_module",
                instanceId = config.id,
            )
            config.copy(enabled = key in eligibleKeys)
        }.sortedWith(compareBy { config ->
            val key = ProviderKey(
                if (config.kind == SourceKind.ADDON) "addon" else "custom_module",
                config.id,
            )
            savedOrder[key] ?: Int.MAX_VALUE
        })
    }

    suspend fun writeExtensions(
        session: ProviderCapabilitySession,
        sources: List<SourceConfig>,
    ): Boolean {
        val choices = legacyProviderChoices(emptyList(), emptySet(), sources)[ProviderCapability.EXTENSION_PLAYBACK]
            .orEmpty()
        return session.update(ProviderCapability.EXTENSION_PLAYBACK, choices)
    }
}
