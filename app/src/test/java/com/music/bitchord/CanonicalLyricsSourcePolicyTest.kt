package com.music.bitchord

import com.music.bitchord.data.catalog.allowedCanonicalLyricsSources
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalLyricsSourcePolicyTest {
    private val all = setOf(
        LyricsSource.YOUTUBE_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.LRCLIB,
        LyricsSource.LRC_RED,
    )
    private val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
    private val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
    private val nonYoutube = setOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED)

    @Test fun explicitOptOutExcludesBothDirectYoutubeLyricsProviders() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, false, disabled))
    }

    @Test fun canonicalDiscoveryOptInExcludesYoutubeEvenIfAudioSourceRemainsEnabled() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, true, enabled))
    }

    @Test fun defaultLegacyModeKeepsExistingLyricsChoice() {
        assertEquals(all, allowedCanonicalLyricsSources(all, false, enabled))
        assertEquals(all, allowedCanonicalLyricsSources(all, false, emptyList()))
    }

    @Test fun nonYoutubeProvidersAndExplicitEmptySetRemainUnchanged() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(nonYoutube, true, disabled))
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(emptySet(), true, disabled))
    }
}
