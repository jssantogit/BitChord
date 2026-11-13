package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: player menus can navigate to a verified album even when
 * the matching row is NOT cached in MusicBrainz discovery/detail screens. */
class CanonicalPlayerAlbumRouteTest {
    private val group = "f5a295b0-01ce-46f2-83fd-c16c557e9d1e"
    private val release = "b7d86d3f-9f95-4a17-8558-625dbe309661"

    @Test fun playerAlbumOpensNativeReleaseGroupDetailWithoutDiscoveryCache() {
        val observed = mutableListOf<List<Any>>()
        val opened = openVerifiedCanonicalPlayerAlbum(
            route = "catalog:RELEASE_GROUP:$group",
            albumName = "Get Up",
            artist = "NewJeans",
            fallbackAlbumTitle = "Álbum",
        ) { id, title, artist, type ->
            observed += listOf(id, title, artist, type)
        }
        assertTrue(opened)
        assertEquals(
            listOf(listOf("catalog:RELEASE_GROUP:$group", "Get Up", "NewJeans", BrowseType.ALBUM)),
            observed,
        )
    }

    @Test fun verifiedEditionCanOpenWithoutMistakingTrackTitleForAlbum() {
        var observedTitle: String? = null
        assertTrue(openVerifiedCanonicalPlayerAlbum(
            "catalog:RELEASE:$release", "", "Artist", "Álbum",
        ) { _, title, _, _ -> observedTitle = title })
        assertEquals("Álbum", observedTitle)
    }

    @Test fun liftedTrackPreviewRequiresAResolvedCanonicalAlbumNotRawProviderId() = kotlinx.coroutines.runBlocking {
        val song = com.music.bitchord.data.model.Song(
            videoId = "audio-1",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            albumId = "provider:album-123",
            albumName = "Album",
        )
        val destinations = mutableListOf<String>()
        val opened = openCanonicalHeldSongAlbum(
            song = song,
            resolveVerifiedRoute = { null },
            fallbackAlbumTitle = "Álbum",
        ) { id, _, _, _ -> destinations += id }
        assertFalse(opened)
        assertTrue(destinations.isEmpty())
    }

    @Test fun liftedTrackPreviewNavigatesOnlyAfterCanonicalResolution() = kotlinx.coroutines.runBlocking {
        val song = com.music.bitchord.data.model.Song(
            videoId = "audio-2",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            albumId = "provider:album-123",
            albumName = "Album",
        )
        val destinations = mutableListOf<List<Any>>()
        val opened = openCanonicalHeldSongAlbum(
            song = song,
            resolveVerifiedRoute = { "catalog:RELEASE_GROUP:$group" },
            fallbackAlbumTitle = "Álbum",
        ) { id, title, artist, type -> destinations += listOf(id, title, artist, type) }
        assertTrue(opened)
        assertEquals(
            listOf(listOf("catalog:RELEASE_GROUP:$group", "Album", "Artist", BrowseType.ALBUM)),
            destinations,
        )
    }

    @Test fun liftedTrackPreviewRejectsRecordingRouteEvenWhenResolved() = kotlinx.coroutines.runBlocking {
        val song = com.music.bitchord.data.model.Song(
            videoId = "audio-3", title = "Track", artist = "Artist",
            thumbnailUrl = null, albumId = group,
        )
        var opened = false
        assertFalse(openCanonicalHeldSongAlbum(
            song = song,
            resolveVerifiedRoute = { "catalog:RECORDING:$group" },
            fallbackAlbumTitle = "Álbum",
        ) { _, _, _, _ -> opened = true })
        assertFalse(opened)
    }

    @Test fun fakeOrWrongNamespaceCannotTriggerCatalogAlbumNavigation() {
        val observed = mutableListOf<String>()
        listOf(
            "catalog:ARTIST:$group",
            "catalog:RELEASE_GROUP:some song title",
            "catalog:RELEASE:",
            "yt:RELEASE:$group",
            "catalog:RECORDING:$release",
        ).forEach { route ->
            assertFalse(openVerifiedCanonicalPlayerAlbum(
                route, "Album", "Artist", "Álbum",
            ) { id, _, _, _ -> observed += id })
        }
        assertTrue(observed.isEmpty())
    }
}
