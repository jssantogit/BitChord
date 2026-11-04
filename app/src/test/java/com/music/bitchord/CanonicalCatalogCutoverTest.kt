package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.ui.SearchOrigin
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.defaultDiscoverySearchSource
import com.music.bitchord.ui.effectiveSearchSource
import com.music.bitchord.ui.searchSourceForOrigin
import com.music.bitchord.ui.shouldPrefetchLegacyYouTubeAccountData
import com.music.bitchord.ui.shouldRunLegacyYoutubeVersionLookup
import com.music.bitchord.ui.shouldUseCanonicalDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #208 first cutover: discovery/search must not silently reactivate legacy
 * YouTube endpoints on upgraded installs whose old opt-in preference is false.
 * YouTube media playback and persisted aliases are deliberately out of scope.
 */
class CanonicalCatalogCutoverTest {
    @Test fun homeAndExploreAlwaysUseCanonicalCatalogEvenOnOldInstall() {
        val sourceConfigurations = listOf(
            emptyList(),
            listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
            listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false)),
            listOf(SourceConfig(kind = SourceKind.ADDON, enabled = false)),
        )
        sourceConfigurations.forEach { configs ->
            assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = false, configs = configs))
            assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = true, configs = configs))
            assertEquals(
                SearchSource.CANONICAL,
                defaultDiscoverySearchSource(explicitlyEnabled = false, configs = configs),
            )
        }
    }

    @Test fun generalSearchNeverResolvesToYoutubeEvenIfRequestedExplicitly() {
        val youtubeEnabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        assertEquals(
            SearchSource.CANONICAL,
            searchSourceForOrigin(SearchOrigin.GENERAL, canonicalMode = false, configs = youtubeEnabled),
        )
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, youtubeEnabled))
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, emptyList()))
        assertEquals(SearchSource.ADDONS, effectiveSearchSource(SearchSource.ADDONS, youtubeEnabled))
        assertEquals(SearchSource.LIBRARY, effectiveSearchSource(SearchSource.LIBRARY, youtubeEnabled))
    }

    @Test fun startupPrefetchAndHiddenYoutubeCatalogProbesStayOff() {
        val youtubeEnabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = true, canonicalDiscoveryEnabled = false, configs = youtubeEnabled,
        ))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(
            canonicalMode = false, configs = youtubeEnabled,
        ))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(
            canonicalMode = false, configs = emptyList(),
        ))
    }
}
