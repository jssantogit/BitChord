package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

internal fun legacyProviderChoices(
    lyricsOrder: List<LyricsSource>,
    enabledLyrics: Set<LyricsSource>,
    sources: List<SourceConfig>,
): Map<ProviderCapability, List<ProviderChoice>> {
    val result = mutableMapOf<ProviderCapability, List<ProviderChoice>>()

    val saved = lyricsOrder.filter { it in LyricsSource.offered && !it.hidden }.distinct()
    val ordered = saved + LyricsSource.offered.filterNot { it in saved || it.hidden }
    result[ProviderCapability.LYRICS_LOOKUP] = ordered.mapIndexed { index, source ->
        ProviderChoice(
            key = ProviderKey(source.name.lowercase(Locale.ROOT)),
            enabled = source in enabledLyrics,
            order = index,
        )
    }

    val extensionSources = sources.filter { config ->
        config.kind == SourceKind.ADDON || config.kind == SourceKind.CUSTOM_MODULE
    }.distinctBy(SourceConfig::id)
    if (extensionSources.isNotEmpty()) {
        result[ProviderCapability.EXTENSION_PLAYBACK] = extensionSources
            .mapIndexed { index, config ->
                ProviderChoice(
                    key = ProviderKey(
                        providerId = when (config.kind) {
                            SourceKind.ADDON -> "addon"
                            else -> "custom_module"
                        },
                        instanceId = config.id,
                    ),
                    enabled = config.enabled,
                    order = index,
                )
            }
        // Download is a separate capability, not an implicit side effect of
        // enabling an extension for playback. Import existing manifest
        // permission once, while preserving every persisted download opt-out.
        result[ProviderCapability.DOWNLOAD] = extensionSources
            .filter(SourceConfig::allowDownloads)
            .mapIndexed { index, config ->
                ProviderChoice(
                    key = ProviderKey(
                        if (config.kind == SourceKind.ADDON) "addon" else "custom_module",
                        config.id,
                    ),
                    enabled = config.enabled,
                    order = index,
                )
            }
    }
    return result
}
