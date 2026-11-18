package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.providers.LegacyProviderCompatibility
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPreferenceStore
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.providers.legacyProviderChoices
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class LegacyProviderChoicesTest {
    @Test fun storageFailureInOneInstanceDoesNotHideAuthorizedLyricsOrAddonSiblings() {
        val lyric = ProviderKey(LyricsSource.LRCLIB.name.lowercase(Locale.ROOT))
        val otherLyric = ProviderKey(LyricsSource.GENIUS.name.lowercase(Locale.ROOT))
        val addonA = ProviderKey("addon", "a")
        val addonB = ProviderKey("addon", "b")
        val snapshot = ProviderSessionSnapshot(
            status = ProviderSessionStatus.STORAGE_ERROR,
            configured = mapOf(
                ProviderCapability.LYRICS_LOOKUP to listOf(
                    ProviderChoice(lyric, true, 0),
                    ProviderChoice(otherLyric, true, 1),
                ),
                ProviderCapability.EXTENSION_PLAYBACK to listOf(
                    ProviderChoice(addonA, true, 0),
                    ProviderChoice(addonB, true, 1),
                ),
            ),
            eligible = mapOf(
                ProviderCapability.LYRICS_LOOKUP to listOf(otherLyric),
                ProviderCapability.EXTENSION_PLAYBACK to listOf(addonB),
            ),
            quarantined = setOf(
                ProviderCapability.LYRICS_LOOKUP,
                ProviderCapability.EXTENSION_PLAYBACK,
            ),
        )
        val projectedLyrics = LegacyProviderCompatibility.projectLyrics(
            snapshot, listOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
            setOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
        )
        assertEquals(setOf(LyricsSource.GENIUS), projectedLyrics.enabled)
        val projected = LegacyProviderCompatibility.projectExtensions(snapshot, listOf(
            SourceConfig(id = "a", kind = SourceKind.ADDON, enabled = true),
            SourceConfig(id = "b", kind = SourceKind.ADDON, enabled = true),
        ))
        assertEquals(listOf(false, true), projected.map { it.enabled })
    }

    @Test fun lyricsSelectionsKeepExistingPriorityAndEveryExplicitOptOut() {
        val choices = legacyProviderChoices(
            lyricsOrder = listOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED, LyricsSource.GENIUS),
            enabledLyrics = setOf(LyricsSource.LRC_RED),
            sources = emptyList(),
        )
        val rows = choices.getValue(ProviderCapability.LYRICS_LOOKUP)
        assertEquals(listOf("lrclib", "lrc_red", "genius"),
            rows.take(3).map { it.key.providerId })
        assertEquals(listOf(false, true, false), rows.take(3).map { it.enabled })
        assertEquals(listOf(0, 1, 2), rows.take(3).map { it.order })
        assertTrue(rows.none { it.key.providerId == "simp_music" })
        assertFalse(choices.containsKey(ProviderCapability.AUDIO_PLAYBACK))
    }

    @Test fun allLyricsOffRemainsAllOffEvenWithTheLastProvider() {
        val choices = legacyProviderChoices(
            lyricsOrder = LyricsSource.offered,
            enabledLyrics = emptySet(),
            sources = emptyList(),
        )
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).isNotEmpty())
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).none { it.enabled })
    }

    @Test fun extensionInstancesStayDistinctAndRetiredSourcesDoNotReturn() {
        val choices = legacyProviderChoices(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = listOf(
                SourceConfig(id = "a", kind = SourceKind.ADDON, baseUrl = "https://private.example/index",
                    enabled = false, allowDownloads = true),
                SourceConfig(id = "b", kind = SourceKind.ADDON, baseUrl = "https://other.example/index",
                    enabled = true, allowDownloads = false),
                SourceConfig(id = "c", kind = SourceKind.CUSTOM_MODULE, baseUrl = "https://third.example/index",
                    enabled = true),
                SourceConfig(id = "retired", kind = SourceKind.JIOSAAVN, enabled = true),
                SourceConfig(id = "youtube", kind = SourceKind.YOUTUBE, enabled = true),
            ),
        )
        val rows = choices.getValue(ProviderCapability.EXTENSION_PLAYBACK)
        assertEquals(listOf(ProviderKey("addon", "a"), ProviderKey("addon", "b"),
            ProviderKey("custom_module", "c")), rows.map { it.key })
        assertEquals(listOf(false, true, true), rows.map { it.enabled })
        assertEquals(listOf(0, 1, 2), rows.map { it.order })
        assertFalse(choices.containsKey(ProviderCapability.AUDIO_PLAYBACK))
        val downloads = choices.getValue(ProviderCapability.DOWNLOAD)
        assertEquals(listOf(ProviderKey("addon", "a"), ProviderKey("custom_module", "c")),
            downloads.map { it.key })
        assertEquals(listOf(false, true), downloads.map { it.enabled })
        assertFalse(rows.toString().contains("private.example"))
        assertFalse(downloads.toString().contains("private.example"))
    }

    @Test fun absentUserExtensionsAreNotMisrepresentedAsExplicitAudioOptOut() {
        val choices = legacyProviderChoices(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = listOf(SourceConfig(kind = SourceKind.YOUTUBE)),
        )
        assertFalse(choices.containsKey(ProviderCapability.EXTENSION_PLAYBACK))
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).none { it.enabled })
    }

    @Test fun singleAuthorityCompatibilityProjectsAndUpdatesWithoutDualWrite(): Unit = runBlocking {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "legacy-provider-compatibility-test", Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()
        val store = ProviderPreferenceStore(prefs)
        val lyricDescriptors = LyricsSource.offered.map { source ->
            ProviderDescriptor(
                key = ProviderKey(source.name.lowercase(Locale.ROOT)),
                capabilities = setOf(ProviderCapability.LYRICS_LOOKUP),
                readiness = ProviderReadiness.READY,
            )
        }
        val addonA = SourceConfig(
            id = "addon-1",
            kind = SourceKind.ADDON,
            baseUrl = "https://secret.example/manifest.json",
            enabled = true,
            allowDownloads = true,
        )
        val extensionDescriptors = listOf(
            ProviderDescriptor(
                key = ProviderKey("addon", "addon-1"),
                capabilities = setOf(ProviderCapability.EXTENSION_PLAYBACK),
                readiness = ProviderReadiness.READY,
            ),
        )
        val session = ProviderCapabilitySession(store, lyricDescriptors + extensionDescriptors)
        val initialOrder = listOf(LyricsSource.GENIUS, LyricsSource.LRCLIB)
        val initialEnabled = setOf(LyricsSource.GENIUS)

        assertTrue(
            LegacyProviderCompatibility.migrateAllLegacy(
                session = session,
                lyricsOrder = initialOrder,
                enabledLyrics = initialEnabled,
                sources = listOf(addonA, SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
            )
        )

        // Update lyrics exclusively through the single-authority session (turn all off).
        assertTrue(
            LegacyProviderCompatibility.writeLyrics(
                session = session,
                order = listOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
                enabled = emptySet(),
            )
        )
        // Re-running migration with stale legacy inputs must NEVER resurrect Genius.
        assertTrue(
            LegacyProviderCompatibility.migrateAllLegacy(
                session = session,
                lyricsOrder = initialOrder,
                enabledLyrics = initialEnabled,
                sources = listOf(addonA),
            )
        )
        val projectedLyrics = LegacyProviderCompatibility.projectLyrics(
            snapshot = session.state.value,
            fallbackOrder = initialOrder,
            fallbackEnabled = initialEnabled,
        )
        assertEquals(LyricsSource.LRCLIB, projectedLyrics.order.first())
        assertTrue(projectedLyrics.enabled.isEmpty())

        // Disable addon-1 through single authority and verify private URL stays intact in SourceConfig
        // while never leaking into ProviderPreferenceStore.
        assertTrue(
            LegacyProviderCompatibility.writeExtensions(
                session = session,
                sources = listOf(addonA.copy(enabled = false)),
            )
        )
        val projectedExtensions = LegacyProviderCompatibility.projectExtensions(
            snapshot = session.state.value,
            rawSources = listOf(addonA, SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true)),
        )
        assertEquals(1, projectedExtensions.size)
        assertFalse(projectedExtensions.single().enabled)
        assertEquals("https://secret.example/manifest.json", projectedExtensions.single().baseUrl)
        assertFalse(prefs.all.toString().contains("secret.example"))
    }
}
