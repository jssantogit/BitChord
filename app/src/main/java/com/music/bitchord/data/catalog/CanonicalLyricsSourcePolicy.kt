package com.music.bitchord.data.catalog

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig

/**
 * YouTube lyrics and captions make InnerTube requests even when the audio
 * is local or comes from an authorized remote provider. Honor the same
 * metadata-discovery and source opt-out boundary without silently removing
 * independent lyric providers (LRCLIB, local embedded lyrics, etc.).
 *
 * This is used by BOTH the Now Playing provider picker and the background
 * service's lyrics fetch, which otherwise have different settings paths.
 */
internal fun allowedCanonicalLyricsSources(
    requested: Set<LyricsSource>,
    @Suppress("UNUSED_PARAMETER") canonicalDiscovery: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): Set<LyricsSource> {
    // Retired choices remain decodable in existing preferences, but can never
    // start an Innertube lyrics request, even for old library rows.
    return requested - setOf(
        LyricsSource.YOUTUBE_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
    )
}
