package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRetiredSourceGateTest {
    @Test fun archivedYoutubeKindIsNeverPermittedAtAnyQualityRung() {
        DesktopAudioQuality.entries.forEach { quality ->
            assertFalse(quality.permits(DesktopSourceKind.YOUTUBE))
            assertTrue(quality.permits(DesktopSourceKind.ADDON))
            assertTrue(quality.permits(DesktopSourceKind.CUSTOM_MODULE))
            assertTrue(quality.permits(DesktopSourceKind.JIOSAAVN))
        }
    }

    @Test fun anOpaqueVideoIdCannotResolveToStreamThroughTheDesktopRegistry() = runBlocking {
        val archived = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Historical song",
            artist = "Historical artist",
            thumbnailUrl = "",
        )
        assertTrue(DesktopSourceRegistry.resolve(archived, "HIGH").isFailure)
        assertFalse(DesktopSourceRegistry.hasYouTubeOriginal(archived))
    }

    @Test fun searchUsesOnlyEnabledConfiguredNonYoutubeProviders() {
        val archived = DesktopSourceConfig(id = "youtube", kind = DesktopSourceKind.YOUTUBE, enabled = true)
        val addon = DesktopSourceConfig(id = "addon", kind = DesktopSourceKind.ADDON, baseUrl = "https://example.invalid/addon")
        val incomplete = DesktopSourceConfig(id = "incomplete", kind = DesktopSourceKind.ADDON, enabled = true)
        val disabled = DesktopSourceConfig(id = "disabled", kind = DesktopSourceKind.JIOSAAVN, enabled = false)
        val jio = DesktopSourceConfig(id = "jio", kind = DesktopSourceKind.JIOSAAVN, enabled = true)
        val eligible = DesktopSourceRegistry.searchableConfigsFor(listOf(archived, addon, incomplete, disabled, jio))
        kotlin.test.assertEquals(listOf("addon", "jio"), eligible.map { it.id })
    }

    @Test fun sourceSearchDoesNotMislabelSongsAsArtistOrAlbumResults() {
        assertTrue(DesktopSourceRegistry.supportsTrackSearchFilter(com.music.bitchord.data.model.SearchFilter.ALL))
        assertTrue(DesktopSourceRegistry.supportsTrackSearchFilter(com.music.bitchord.data.model.SearchFilter.SONGS))
        assertFalse(DesktopSourceRegistry.supportsTrackSearchFilter(com.music.bitchord.data.model.SearchFilter.ARTISTS))
        assertFalse(DesktopSourceRegistry.supportsTrackSearchFilter(com.music.bitchord.data.model.SearchFilter.ALBUMS))
    }

    @Test fun retiredDesktopBrowseAndAccountOperationsCannotStartYoutubeNetwork() = runBlocking {
        assertTrue(DesktopSearchClient.home().isFailure)
        assertTrue(DesktopSearchClient.library().isFailure)
        assertTrue(DesktopSearchClient.accountMenu().isFailure)
        assertTrue(DesktopSearchClient.accountsList().isFailure)
        assertTrue(DesktopSearchClient.radio("historical-id").isFailure)
        assertTrue(DesktopSearchClient.searchTypeahead("song").getOrThrow().isEmpty())
        assertTrue(DesktopSearchClient.searchSuggestions("song").getOrThrow().isEmpty())
    }
}
