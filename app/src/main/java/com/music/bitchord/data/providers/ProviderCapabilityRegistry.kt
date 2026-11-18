package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

/**
 * Functional tabs for the future native Settings -> Fontes screen (conditioned
 * on PR #212 merge). Keeps backend grouping aligned with user-facing roles
 * without introducing any Compose or UI dependency here.
 */
enum class ProviderSettingsTab {
    CATALOG,
    ARTWORK,
    LYRICS,
    STATISTICS_AND_CHARTS,
    AUDIO_AND_EXTENSIONS,
    DOWNLOADS,
}

data class ProviderInventoryEntry(
    val key: ProviderKey,
    val capability: ProviderCapability,
    val tab: ProviderSettingsTab,
    val displayName: String,
    val readiness: ProviderReadiness,
    val supportsReordering: Boolean,
    val notes: String,
)

data class ProviderTabRowState(
    val entry: ProviderInventoryEntry,
    val status: ProviderCapabilityStatus,
    val order: Int,
)

/** Readiness contains no secrets and is refreshed when the configured account changes. */
internal data class OpenSubsonicProviderAccountState(
    val accountId: String,
    val credentialAvailable: Boolean,
)


/**
 * Authoritative inventory of every provider and capability in BitChord Android.
 *
 * Enforces honesty: capabilities whose adapters do not yet exist in this build
 * (such as charts, acoustic identification, public playlists, or iTunes/Discogs
 * prior to PR #216 reconciliation) remain [ProviderReadiness.NOT_IMPLEMENTED]
 * and never claim READY. Retired providers (JioSaavn, YouTube) are excluded.
 */
internal object ProviderCapabilityRegistry {
    fun inventory(
        sources: List<SourceConfig> = emptyList(),
        soulseekConfigured: Boolean = false,
        lastfmConfigured: Boolean = false,
        listenBrainzConfigured: Boolean = false,
        itunesImplemented: Boolean = false,
        discogsImplemented: Boolean = false,
        discogsTokenConfigured: Boolean = false,
        openSubsonicAccountId: String? = null,
        openSubsonicConfigured: Boolean = false,
    ): List<ProviderInventoryEntry> = buildList {
        // 1. Catalog, Identity & Discography
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.MUSICBRAINZ,
                capability = ProviderCapability.CATALOG_SEARCH,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "MusicBrainz (Busca)",
                readiness = ProviderReadiness.READY,
                supportsReordering = true,
                notes = "Gated in MusicBrainzCatalogSearch & MusicBrainzCachedSearchRepository",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.MUSICBRAINZ,
                capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "MusicBrainz (Discografia)",
                readiness = ProviderReadiness.READY,
                supportsReordering = true,
                notes = "Gated in MusicBrainzCatalogGraph & MusicBrainzCatalogIndex",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.MUSICBRAINZ,
                capability = ProviderCapability.IDENTITY,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "MusicBrainz (Identidade / ISRC)",
                readiness = ProviderReadiness.READY,
                supportsReordering = false,
                notes = "Gated in MusicBrainzClient default IDENTITY boundary",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.ITUNES,
                capability = ProviderCapability.CATALOG_SEARCH,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "Apple / iTunes (Busca)",
                readiness = if (itunesImplemented) ProviderReadiness.READY else ProviderReadiness.NOT_IMPLEMENTED,
                supportsReordering = true,
                notes = "Awaiting PR #216 reconciliation",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.ITUNES,
                capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "Apple / iTunes (Discografia)",
                readiness = if (itunesImplemented) ProviderReadiness.READY else ProviderReadiness.NOT_IMPLEMENTED,
                supportsReordering = true,
                notes = "Awaiting PR #216 reconciliation",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.DISCOGS,
                capability = ProviderCapability.EDITION_METADATA,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "Discogs (Edições)",
                readiness = when {
                    !discogsImplemented -> ProviderReadiness.NOT_IMPLEMENTED
                    !discogsTokenConfigured -> ProviderReadiness.NEEDS_CONFIGURATION
                    else -> ProviderReadiness.READY
                },
                supportsReordering = false,
                notes = "Awaiting PR #216 reconciliation",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = ProviderKey("acoustid"),
                capability = ProviderCapability.ACOUSTIC_IDENTIFICATION,
                tab = ProviderSettingsTab.CATALOG,
                displayName = "AcoustID",
                readiness = ProviderReadiness.NOT_IMPLEMENTED,
                supportsReordering = false,
                notes = "Planned capability; not implemented",
            ),
        )

        // 2. Artwork (Release & Artist separated)
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.COVER_ART_ARCHIVE,
                capability = ProviderCapability.RELEASE_ARTWORK,
                tab = ProviderSettingsTab.ARTWORK,
                displayName = "Cover Art Archive",
                readiness = ProviderReadiness.READY,
                supportsReordering = true,
                notes = "CoverArtArchive release/group sleeves",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.ITUNES,
                capability = ProviderCapability.RELEASE_ARTWORK,
                tab = ProviderSettingsTab.ARTWORK,
                displayName = "Apple / iTunes (Capas)",
                readiness = if (itunesImplemented) ProviderReadiness.READY else ProviderReadiness.NOT_IMPLEMENTED,
                supportsReordering = true,
                notes = "Awaiting PR #216 reconciliation",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.APPLE_ARTIST_ART,
                capability = ProviderCapability.ARTIST_ARTWORK,
                tab = ProviderSettingsTab.ARTWORK,
                displayName = "Apple Music (Retratos de Artista)",
                readiness = ProviderReadiness.READY,
                supportsReordering = false,
                notes = "AppleArtistArtRepository portrait & hero art",
            ),
        )

        // 3. Lyrics (offered non-hidden sources only; YouTube lyrics excluded in canonical mode)
        LyricsSource.offered
            .filterNot { it.hidden }
            .forEach { source ->
                add(
                    ProviderInventoryEntry(
                        key = BuiltInProviderKeys.lyrics(source),
                        capability = ProviderCapability.LYRICS_LOOKUP,
                        tab = ProviderSettingsTab.LYRICS,
                        displayName = source.label,
                        readiness = ProviderReadiness.READY,
                        supportsReordering = true,
                        notes = if (source.wordSynced) "Word/syllable-synced capable" else "Line/plain lyrics",
                    ),
                )
            }

        // 4. Listening Statistics, Charts & Public Playlists
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.LASTFM,
                capability = ProviderCapability.LISTENING_STATISTICS,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "Last.fm (Scrobbling)",
                readiness = if (lastfmConfigured) ProviderReadiness.READY else ProviderReadiness.NEEDS_CONFIGURATION,
                supportsReordering = false,
                notes = "Requires API credentials and session key",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.LISTENBRAINZ,
                capability = ProviderCapability.LISTENING_STATISTICS,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "ListenBrainz (Scrobbling)",
                readiness = if (listenBrainzConfigured) ProviderReadiness.READY else ProviderReadiness.NEEDS_CONFIGURATION,
                supportsReordering = false,
                notes = "Requires user token",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.LISTENBRAINZ,
                capability = ProviderCapability.PERSONAL_RECOMMENDATIONS,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "ListenBrainz (Recomendações Pessoais)",
                readiness = if (listenBrainzConfigured) ProviderReadiness.READY
                    else ProviderReadiness.NEEDS_CONFIGURATION,
                supportsReordering = false,
                notes = "Opt-in separado do scrobbling; valida a conta antes da recomendação",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.LISTENBRAINZ,
                capability = ProviderCapability.PUBLIC_PLAYLISTS,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "ListenBrainz (Playlists Públicas)",
                readiness = ProviderReadiness.READY,
                supportsReordering = false,
                notes = "Public anonymous playlists; canonical capability-gated transport and cache",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = BuiltInProviderKeys.LISTENBRAINZ,
                capability = ProviderCapability.CHART_MOST_PLAYED,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "ListenBrainz (Rankings de Reprodução)",
                readiness = ProviderReadiness.READY,
                supportsReordering = false,
                notes = "Public recording popularity, artist top and sitewide charts",
            ),
        )
        add(
            ProviderInventoryEntry(
                key = ProviderKey("store_charts"),
                capability = ProviderCapability.CHART_SALES,
                tab = ProviderSettingsTab.STATISTICS_AND_CHARTS,
                displayName = "Rankings de Vendas",
                readiness = ProviderReadiness.NOT_IMPLEMENTED,
                supportsReordering = false,
                notes = "Planned capability; not implemented",
            ),
        )

        // 5. Personal Library, Servers & Extensions (excluding retired JioSaavn and YouTube)
        add(
            ProviderInventoryEntry(
                key = ProviderKey("local", "mediastore"),
                capability = ProviderCapability.PERSONAL_LIBRARY,
                tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
                displayName = "Biblioteca Local (MediaStore)",
                readiness = ProviderReadiness.READY,
                supportsReordering = false,
                notes = "On-device audio files and acquired media",
            ),
        )
        // Remote account is a real installed adapter, but its library and
        // audio privileges are independent. Readiness is NOT authorization.
        openSubsonicAccountId?.takeIf(String::isNotBlank)?.let { accountId ->
            val key = ProviderKey("opensubsonic", accountId)
            for (capability in listOf(
                ProviderCapability.PERSONAL_LIBRARY,
                ProviderCapability.AUDIO_PLAYBACK,
            )) {
                add(ProviderInventoryEntry(
                    key = key,
                    capability = capability,
                    tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
                    displayName = if (capability == ProviderCapability.PERSONAL_LIBRARY)
                        "Navidrome / OpenSubsonic (Biblioteca)"
                    else "Navidrome / OpenSubsonic (Reprodução)",
                    readiness = if (openSubsonicConfigured) ProviderReadiness.READY
                        else ProviderReadiness.NEEDS_CONFIGURATION,
                    supportsReordering = false,
                    notes = "Account-scoped local catalog and authenticated stream; exact instance admission",
                ))
            }
        }
        sources.asSequence()
            .filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE }
            .distinctBy(SourceConfig::id)
            .forEach { config ->
                val providerId = if (config.kind == SourceKind.ADDON) "addon" else "custom_module"
                val key = ProviderKey(providerId, config.id)
                add(
                    ProviderInventoryEntry(
                        key = key,
                        capability = ProviderCapability.EXTENSION_PLAYBACK,
                        tab = ProviderSettingsTab.AUDIO_AND_EXTENSIONS,
                        displayName = config.displayName,
                        readiness = if (config.isComplete) ProviderReadiness.READY else ProviderReadiness.NEEDS_CONFIGURATION,
                        supportsReordering = true,
                        notes = "User-configured extension instance",
                    ),
                )
                if (config.allowDownloads) {
                    add(
                        ProviderInventoryEntry(
                            key = key,
                            capability = ProviderCapability.DOWNLOAD,
                            tab = ProviderSettingsTab.DOWNLOADS,
                            displayName = "${config.displayName} (Downloads)",
                            readiness = if (config.isComplete) ProviderReadiness.READY else ProviderReadiness.NEEDS_CONFIGURATION,
                            supportsReordering = true,
                            notes = "Manifest allowDownloads=true",
                        ),
                    )
                }
            }

        // 6. Downloads / Acquisition
        add(
            ProviderInventoryEntry(
                key = ProviderKey("soulseek"),
                capability = ProviderCapability.DOWNLOAD,
                tab = ProviderSettingsTab.DOWNLOADS,
                displayName = "Soulseek (P2P)",
                readiness = if (soulseekConfigured) ProviderReadiness.READY else ProviderReadiness.NEEDS_CONFIGURATION,
                supportsReordering = false,
                notes = "Explicit candidate picker in Downloads",
            ),
        )
    }

    fun descriptorsFrom(entries: List<ProviderInventoryEntry>): List<ProviderDescriptor> =
        entries.groupBy { it.key }.map { (key, group) ->
            val readiness = when {
                group.any { it.readiness == ProviderReadiness.READY } -> ProviderReadiness.READY
                group.any { it.readiness == ProviderReadiness.NEEDS_CONFIGURATION } -> ProviderReadiness.NEEDS_CONFIGURATION
                group.any { it.readiness == ProviderReadiness.BLOCKED } -> ProviderReadiness.BLOCKED
                else -> ProviderReadiness.NOT_IMPLEMENTED
            }
            ProviderDescriptor(
                key = key,
                capabilities = group.map { it.capability }.toSet(),
                readiness = ProviderReadiness.NOT_IMPLEMENTED,
                capabilityReadiness = group.associate { it.capability to it.readiness },
            )
        }

    fun projectTab(
        tab: ProviderSettingsTab,
        entries: List<ProviderInventoryEntry>,
        snapshot: ProviderSessionSnapshot,
        policy: ProviderCapabilityPolicy,
    ): List<ProviderTabRowState> {
        val tabEntries = entries.filter { it.tab == tab }
        return tabEntries.mapIndexed { defaultIdx, entry ->
            val savedChoices = snapshot.configured[entry.capability].orEmpty()
            val saved = savedChoices.firstOrNull { it.key == entry.key }
            val order = saved?.order ?: (1000 + defaultIdx)
            val quarantined = (entry.capability to entry.key) in snapshot.quarantinedProviders
            val status = policy.status(entry.capability, entry.key, quarantined = quarantined)
            ProviderTabRowState(
                entry = entry,
                status = status,
                order = order,
            )
        }.sortedWith(compareBy<ProviderTabRowState> { it.order }.thenBy { it.entry.displayName.lowercase(Locale.ROOT) })
    }
}
