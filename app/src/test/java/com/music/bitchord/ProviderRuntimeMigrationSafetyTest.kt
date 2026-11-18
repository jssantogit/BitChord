package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRuntimeMigrationSafetyTest {
    @Test fun baselineStartupMigrationNeverOverwritesLegacyLyricsOrExtensionChoices() {
        val baseline = ProviderRuntime.musicBrainzBaseline()
        assertEquals(setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        ), baseline.keys)
        assertFalse(baseline.containsKey(ProviderCapability.LYRICS_LOOKUP))
        assertFalse(baseline.containsKey(ProviderCapability.EXTENSION_PLAYBACK))
    }

    @Test fun restoredExtensionDescriptorsAreIdentifiedByInstanceAndExcludeRetiredSources() {
        val sources = listOf(
            SourceConfig(id = "server-a", kind = SourceKind.ADDON, enabled = false,
                baseUrl = "https://private.example"),
            SourceConfig(id = "server-b", kind = SourceKind.CUSTOM_MODULE, enabled = true,
                baseUrl = "https://another.example"),
            SourceConfig(id = "retired", kind = SourceKind.JIOSAAVN, enabled = true),
            SourceConfig(id = "video", kind = SourceKind.YOUTUBE, enabled = true),
        )
        val descriptors = ProviderRuntime.extensionDescriptors(sources)
        assertEquals(listOf(
            ProviderKey("addon", "server-a"),
            ProviderKey("custom_module", "server-b"),
        ), descriptors.map { it.key })
        assertTrue(descriptors.all { ProviderCapability.EXTENSION_PLAYBACK in it.capabilities })
        assertFalse(descriptors.toString().contains("private.example"))
        // Registration does not grant user authorization; that belongs to the
        // old settings until a full, coordinated preferences cutover.
    }
}
