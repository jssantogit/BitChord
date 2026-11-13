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
