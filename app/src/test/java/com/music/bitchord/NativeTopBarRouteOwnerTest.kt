package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression: an artist page opened from a playlist can leave the playlist
 * state alive in the back stack. Only the ACTIVE AnimatedContent route may
 * contribute actions to the global FrostedTopBar.
 */
class NativeTopBarRouteOwnerTest {
    @Test fun artistFromPlaylistOwnsOneSearchAndOneOverflowGroup() {
        val owner = nativeTopBarRouteOwner("unified_library", activeDetailBrowseId = null)
        assertEquals(NativeTopBarRouteOwner.UNIFIED_LIBRARY, owner)
        assertFalse(owner == NativeTopBarRouteOwner.PLAYLIST_LIBRARY)
        assertFalse(owner == NativeTopBarRouteOwner.CATALOG_DETAIL)
    }

    @Test fun playlistOpenedWhileLibraryCategoryIsRememberedStillHasOneToolbar() {
        assertEquals(
            NativeTopBarRouteOwner.PLAYLIST_LIBRARY,
            nativeTopBarRouteOwner("canonical_playlist_library", activeDetailBrowseId = null),
        )
    }

    @Test fun publicPlaylistBeatsAnyRememberedLibraryContext() {
        assertEquals(
            NativeTopBarRouteOwner.PUBLIC_PLAYLIST,
            nativeTopBarRouteOwner(
                "public_playlist:listenbrainz-mbid",
                activeDetailBrowseId = "catalog:ARTIST:previous",
            ),
        )
    }

    @Test fun nestedAlbumOrArtistRouteOutranksRememberedCategoryAndPlaylist() {
        for (route in listOf(
            "catalog:ARTIST:astro",
            "catalog:RELEASE_GROUP:circles",
            "local:album:circles",
        )) {
            assertEquals(
                NativeTopBarRouteOwner.CATALOG_DETAIL,
                nativeTopBarRouteOwner(route, activeDetailBrowseId = route),
            )
        }
    }

    @Test fun noHiddenPageCanLeakToolbarIntoHomeSearchSettingsOrBuilder() {
        for (route in listOf(
            "tab:0", "tab:4", "settings", "smart_playlist_builder",
            "history", "my_ratings", "rediscovery:recording",
        )) {
            assertEquals(
                NativeTopBarRouteOwner.OTHER,
                nativeTopBarRouteOwner(route, activeDetailBrowseId = "catalog:ARTIST:stale"),
            )
        }
    }

    @Test fun recentAdditionsDoesNotShareToolbarWithRememberedPlaylist() {
        assertEquals(
            NativeTopBarRouteOwner.RECENT_ADDITIONS,
            nativeTopBarRouteOwner("local_recent_additions", activeDetailBrowseId = null),
        )
    }
}
