package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

/**
 * Single-authority cutover coordinator for legacy settings (AppSettings lyrics
 * and SourceRegistry extensions/servers).
 *
 * Eliminates dual-write and conflicting state:
 * - Reads project exclusively from [ProviderCapabilitySession] once initialized.
 * - Writes mutate exclusively [ProviderCapabilitySession] (never a second store).
 * - Retired providers (JioSaavn, YouTube) are filtered out safely without
 *   deleting historical IDs or private server URLs.
 */
internal class ProviderAuthorityCoordinator(
    private val session: ProviderCapabilitySession,
    private val openSubsonicAccount: () -> OpenSubsonicProviderAccountState? = { null },
) {
    private fun currentOpenSubsonicAccount(): OpenSubsonicProviderAccountState? =
        runCatching { openSubsonicAccount() }.getOrNull()

    private var lastfmReady = false
    private var listenBrainzReady = false
    private var soulseekReady = false
    private var itunesReady = false
    private var discogsReady = false
    private var discogsTokenReady = false
    suspend fun migrateOnce(
        lyricsOrder: List<LyricsSource>,
        enabledLyrics: Set<LyricsSource>,
        sources: List<SourceConfig>,
        includeMusicBrainzBaseline: Boolean = true,
        includeDefaultArtworkBaseline: Boolean = true,
        lastfmConfigured: Boolean = false,
        listenBrainzConfigured: Boolean = false,
        soulseekConfigured: Boolean = false,
        itunesImplemented: Boolean = false,
        discogsImplemented: Boolean = false,
        discogsTokenConfigured: Boolean = false,
        lastfmEnabled: Boolean = false,
        listenBrainzEnabled: Boolean = false,
    ): Boolean {
        val cleanedSources = sources.filterNot {
            it.kind == SourceKind.JIOSAAVN || it.kind == SourceKind.YOUTUBE
        }
        val remoteAccount = currentOpenSubsonicAccount()
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = cleanedSources,
                lastfmConfigured = lastfmConfigured,
                listenBrainzConfigured = listenBrainzConfigured,
                soulseekConfigured = soulseekConfigured,
                itunesImplemented = itunesImplemented,
                discogsImplemented = discogsImplemented,
                discogsTokenConfigured = discogsTokenConfigured,
                openSubsonicAccountId = remoteAccount?.accountId,
                openSubsonicConfigured = remoteAccount?.credentialAvailable == true,
            ),
        )
        session.refreshDescriptors(descriptors)
        lastfmReady = lastfmConfigured
        listenBrainzReady = listenBrainzConfigured
        soulseekReady = soulseekConfigured
        itunesReady = itunesImplemented
        discogsReady = discogsImplemented
        discogsTokenReady = discogsTokenConfigured

        val imported = mutableMapOf<ProviderCapability, List<ProviderChoice>>()
        if (includeMusicBrainzBaseline) {
            imported.putAll(ProviderRuntime.musicBrainzBaseline())
        }
        if (includeDefaultArtworkBaseline) {
            imported[ProviderCapability.RELEASE_ARTWORK] = listOf(
                ProviderChoice(BuiltInProviderKeys.COVER_ART_ARCHIVE, enabled = true, order = 0),
            )
            imported[ProviderCapability.ARTIST_ARTWORK] = listOf(
                ProviderChoice(BuiltInProviderKeys.APPLE_ARTIST_ART, enabled = true, order = 0),
            )
        }
        if (itunesImplemented) {
            // The iTunes API is live on the reconciled #216 baseline. Catalog,
            // discography and artwork have independent opt-outs; existing
            // persisted choices always supersede these first-install defaults.
            for (capability in listOf(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
            )) {
                imported[capability] = imported[capability].orEmpty() +
                    ProviderChoice(BuiltInProviderKeys.ITUNES, enabled = true,
                        order = imported[capability].orEmpty().size)
            }
            imported[ProviderCapability.RELEASE_ARTWORK] = listOf(
                ProviderChoice(BuiltInProviderKeys.ITUNES, enabled = true, order = 0),
                ProviderChoice(BuiltInProviderKeys.COVER_ART_ARCHIVE, enabled = false, order = 1),
            )
        }
        if (discogsImplemented) {
            // Public legal/credential gate is independent from implementation:
            // no Discogs request is implicitly enabled.
            imported[ProviderCapability.EDITION_METADATA] = listOf(
                ProviderChoice(BuiltInProviderKeys.DISCOGS, enabled = false, order = 0),
            )
        }
        // Anonymous public playlists were already available on the #216
        // product. Preserve that behavior on first import while honoring
        // every explicitly saved disabled/empty choice on subsequent boots.
        imported[ProviderCapability.CHART_MOST_PLAYED] = listOf(
            ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ, enabled = true, order = 0),
        )
        imported[ProviderCapability.PUBLIC_PLAYLISTS] = listOf(
            ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ, enabled = true, order = 0),
        )
        // Scrobbling consent does not imply consent to query account-specific
        // recommendations. The old settings had no separate recommendation
        // opt-in, so initialize this new capability disabled. Later explicit
        // preferences always supersede the import baseline.
        imported[ProviderCapability.PERSONAL_RECOMMENDATIONS] = listOf(
            ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ,
                enabled = false, order = 0),
        )
        imported[ProviderCapability.LISTENING_STATISTICS] = listOf(
            ProviderChoice(BuiltInProviderKeys.LASTFM, enabled = lastfmEnabled, order = 0),
            ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ, enabled = listenBrainzEnabled, order = 1),
        )
        imported.putAll(legacyProviderChoices(lyricsOrder, enabledLyrics, cleanedSources))
        // Existing configured accounts had a functioning Library and Playback
        // before this capability was introduced. Import once; later explicit
        // disabled/empty choices always win over these compatibility defaults.
        remoteAccount?.let { account ->
            val key = ProviderKey("opensubsonic", account.accountId)
            for (capability in listOf(
                ProviderCapability.PERSONAL_LIBRARY, ProviderCapability.AUDIO_PLAYBACK,
            )) {
                imported[capability] = listOf(ProviderChoice(key, enabled = true, order = 0))
            }
        }
        // Existing manually configured Soulseek pilot is a separate DOWNLOAD
        // instance. It is initially opt-in, but remains ineligible without a
        // credential and all future explicit opt-outs win over this baseline.
        imported[ProviderCapability.DOWNLOAD] = imported[ProviderCapability.DOWNLOAD].orEmpty() +
            ProviderChoice(ProviderKey("soulseek"), enabled = true,
                order = imported[ProviderCapability.DOWNLOAD].orEmpty().size)
        // migrateMissing() imports the Soulseek baseline only when DOWNLOAD is
        // genuinely absent from persistent preferences. In existing installs,
        // a persisted DOWNLOAD category (including []) expresses a user choice:
        // never append a newly introduced provider as enabled on restart.
        // Users may explicitly opt in to Soulseek later through the canonical
        // capability settings; discovered descriptors alone grant nothing.
        return session.initialize(imported)
    }

    /**
     * Live account readiness and explicit legacy switch changes share the
     * durable policy: a missing credential does not discard a saved opt-in.
     * When only credentials change, keep the user's existing choices intact.
     */
    suspend fun updateListeningSettings(
        lastfmConfigured: Boolean,
        listenBrainzConfigured: Boolean,
        lastfmEnabled: Boolean,
        listenBrainzEnabled: Boolean,
        updateChoices: Boolean = true,
    ): Boolean {
        lastfmReady = lastfmConfigured
        listenBrainzReady = listenBrainzConfigured
        val remoteAccount = currentOpenSubsonicAccount()
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = com.music.bitchord.data.sources.SourceRegistry.configs.value,
                lastfmConfigured = lastfmReady,
                listenBrainzConfigured = listenBrainzReady,
                soulseekConfigured = soulseekReady,
                itunesImplemented = itunesReady,
                discogsImplemented = discogsReady,
                discogsTokenConfigured = discogsTokenReady,
                openSubsonicAccountId = remoteAccount?.accountId,
                openSubsonicConfigured = remoteAccount?.credentialAvailable == true,
            ),
        )
        session.refreshDescriptors(descriptors)
        if (!updateChoices) return true
        return session.update(
            ProviderCapability.LISTENING_STATISTICS,
            listOf(
                ProviderChoice(BuiltInProviderKeys.LASTFM, enabled = lastfmEnabled, order = 0),
                ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ, enabled = listenBrainzEnabled, order = 1),
            ),
        )
    }

    /**
     * Refresh after account save/forget. Never re-enable a persisted opt-out.
     * A user explicitly adding the FIRST account can acquire its initial
     * capability slots; an all-disabled category remains all-disabled.
     */
    suspend fun onOpenSubsonicAccountChanged(): Boolean {
        val remoteAccount = currentOpenSubsonicAccount()
        session.refreshDescriptors(ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = com.music.bitchord.data.sources.SourceRegistry.configs.value,
                lastfmConfigured = lastfmReady,
                listenBrainzConfigured = listenBrainzReady,
                soulseekConfigured = soulseekReady,
                itunesImplemented = itunesReady,
                discogsImplemented = discogsReady,
                discogsTokenConfigured = discogsTokenReady,
                openSubsonicAccountId = remoteAccount?.accountId,
                openSubsonicConfigured = remoteAccount?.credentialAvailable == true,
            ),
        ))
        val account = remoteAccount ?: return true
        val key = ProviderKey("opensubsonic", account.accountId)
        for (capability in listOf(
            ProviderCapability.PERSONAL_LIBRARY, ProviderCapability.AUDIO_PLAYBACK,
        )) {
            val saved = session.state.value.configured
            val current = saved[capability].orEmpty()
            if (current.any { it.key == key }) continue
            // Empty persisted list or all-disabled source slots mean an
            // explicit opt-out, not permission to enable a newly discovered ID.
            if (capability in saved && current.none { it.enabled }) continue
            if (!session.update(capability, current + ProviderChoice(
                key, enabled = true, order = current.size,
            ))) return false
        }
        return true
    }

    /** Credential changes refresh readiness, never overwrite a saved opt-out. */
    suspend fun updateSoulseekReadiness(configured: Boolean) {
        soulseekReady = configured
        val remoteAccount = currentOpenSubsonicAccount()
        session.refreshDescriptors(ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = com.music.bitchord.data.sources.SourceRegistry.configs.value,
                lastfmConfigured = lastfmReady,
                listenBrainzConfigured = listenBrainzReady,
                soulseekConfigured = soulseekReady,
                itunesImplemented = itunesReady,
                discogsImplemented = discogsReady,
                discogsTokenConfigured = discogsTokenReady,
                openSubsonicAccountId = remoteAccount?.accountId,
                openSubsonicConfigured = remoteAccount?.credentialAvailable == true,
            ),
        ))
    }

    suspend fun onSourcesChanged(rawSources: List<SourceConfig>) {
        val cleanedSources = rawSources.filterNot {
            it.kind == SourceKind.JIOSAAVN || it.kind == SourceKind.YOUTUBE
        }
        val remoteAccount = currentOpenSubsonicAccount()
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = cleanedSources,
                lastfmConfigured = lastfmReady,
                listenBrainzConfigured = listenBrainzReady,
                soulseekConfigured = soulseekReady,
                itunesImplemented = itunesReady,
                discogsImplemented = discogsReady,
                discogsTokenConfigured = discogsTokenReady,
                openSubsonicAccountId = remoteAccount?.accountId,
                openSubsonicConfigured = remoteAccount?.credentialAvailable == true,
            ),
        )
        session.refreshDescriptors(descriptors)
        // Adding a configured extension is an explicit user action. Import
        // newly introduced instance identities once, never overwrite a saved
        // opt-out when an existing instance disappears and later returns.
        val prior = session.state.value.configured[ProviderCapability.EXTENSION_PLAYBACK].orEmpty()
        val savedKeys = prior.mapTo(HashSet()) { it.key }
        val added = cleanedSources
            .filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE }
            .distinctBy { it.id }
            .mapNotNull { source ->
                val key = ProviderKey(
                    if (source.kind == SourceKind.ADDON) "addon" else "custom_module",
                    source.id,
                )
                if (key in savedKeys) null else ProviderChoice(
                    key, enabled = source.enabled, order = prior.size + savedKeys.size,
                )
            }
        if (added.isNotEmpty()) {
            session.update(ProviderCapability.EXTENSION_PLAYBACK,
                prior + added.mapIndexed { i, choice -> choice.copy(order = prior.size + i) })
        }

        // Newly installed download-capable addons acquire a separate slot.
        // A removed/reinstalled addon retains its saved DOWNLOAD opt-out.
        val priorDownloads = session.state.value.configured[ProviderCapability.DOWNLOAD].orEmpty()
        val savedDownloadKeys = priorDownloads.mapTo(HashSet()) { it.key }
        val newDownloads = cleanedSources
            .filter { (it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE) &&
                it.allowDownloads }
            .distinctBy { it.id }
            .mapNotNull { source ->
                val key = ProviderKey(
                    if (source.kind == SourceKind.ADDON) "addon" else "custom_module",
                    source.id,
                )
                if (key in savedDownloadKeys) null else ProviderChoice(
                    key, enabled = source.enabled, order = priorDownloads.size,
                )
            }
        if (newDownloads.isNotEmpty()) {
            session.update(
                ProviderCapability.DOWNLOAD,
                priorDownloads + newDownloads.mapIndexed { i, choice ->
                    choice.copy(order = priorDownloads.size + i)
                },
            )
        }
    }

    fun currentLyricsProjection(
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): LegacyLyricsProjection = LegacyProviderCompatibility.projectLyrics(
        snapshot = session.state.value,
        fallbackOrder = fallbackOrder,
        fallbackEnabled = fallbackEnabled,
    )

    suspend fun setLyricsPreferences(
        order: List<LyricsSource>,
        enabled: Set<LyricsSource>,
    ): Boolean = LegacyProviderCompatibility.writeLyrics(
        session = session,
        order = order,
        enabled = enabled,
    )

    suspend fun setSingleLyricSourceEnabled(
        source: LyricsSource,
        enabled: Boolean,
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): Boolean {
        if (source !in LyricsSource.offered || source.hidden) return false
        val current = currentLyricsProjection(fallbackOrder, fallbackEnabled)
        val nextEnabled = if (enabled) current.enabled + source else current.enabled - source
        return setLyricsPreferences(current.order, nextEnabled)
    }

    suspend fun reorderLyricSources(
        newOrder: List<LyricsSource>,
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): Boolean {
        val current = currentLyricsProjection(fallbackOrder, fallbackEnabled)
        return setLyricsPreferences(newOrder, current.enabled)
    }

    fun currentExtensionsProjection(rawSources: List<SourceConfig>): List<SourceConfig> =
        LegacyProviderCompatibility.projectExtensions(
            snapshot = session.state.value,
            rawSources = rawSources,
        )

    suspend fun setExtensionEnabled(
        configId: String,
        enabled: Boolean,
        rawSources: List<SourceConfig>,
    ): Boolean {
        val extensions = currentExtensionsProjection(rawSources).map { config ->
            if (config.id == configId) config.copy(enabled = enabled) else config
        }
        return LegacyProviderCompatibility.writeExtensions(session, extensions)
    }

    suspend fun reorderExtensions(
        orderedIds: List<String>,
        rawSources: List<SourceConfig>,
    ): Boolean {
        val current = currentExtensionsProjection(rawSources)
        val byId = current.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] } +
            current.filterNot { it.id in orderedIds.toSet() }
        return LegacyProviderCompatibility.writeExtensions(session, reordered)
    }

    fun isLyricSourcePermitted(source: LyricsSource): Boolean {
        if (source !in LyricsSource.offered || source.hidden) return false
        val snapshot = session.state.value
        if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) return false
        val key = BuiltInProviderKeys.lyrics(source)
        if ((ProviderCapability.LYRICS_LOOKUP to key) in snapshot.quarantinedProviders) return false
        return session.gate.open(ProviderCapability.LYRICS_LOOKUP, key) != null
    }
}
