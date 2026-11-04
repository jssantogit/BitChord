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
}
