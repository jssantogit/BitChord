package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playlist pin identities are separate from canonical release/artist/recording IDs. */
class LibraryPlaylistPinStateTest {
    @Test fun pinAndUnpinAreStableAcrossPlaylistTypes() {
        val normal = LibraryPlaylistPin("normal:playlist-1", "Normal")
        val smart = LibraryPlaylistPin("v2:playlist-1", "Inteligente")
        val a = toggleLibraryPlaylistPin(emptyList(), normal)
        val b = toggleLibraryPlaylistPin(a, smart)
        assertEquals(listOf(normal, smart), b)
        assertEquals(listOf(smart), toggleLibraryPlaylistPin(b, normal))
        assertEquals(b, decodeLibraryPlaylistPins(encodeLibraryPlaylistPins(b)))
    }

    @Test fun rejectsCorruptOrProviderNativePinIdsWithoutDroppingOtherPins() {
        val existing = listOf(LibraryPlaylistPin("normal:good", "Good"))
        assertEquals(existing, toggleLibraryPlaylistPin(existing,
            LibraryPlaylistPin("YTPL:xxx", "Youtube")))
        assertEquals(existing, toggleLibraryPlaylistPin(existing,
            LibraryPlaylistPin("v2:", "No id")))
        assertEquals(existing, decodeLibraryPlaylistPins(
            """[{"id":"normal:good","title":"Good"},{"id":"YTPL:bad","title":"Bad"}]"""))
    }

    @Test fun menuExposesOnlyRealCanonicalActions() {
        val smart = LibraryPlaylistCard.Smart(
            com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition(
                id = "smart", name = "Smart",
                query = com.music.bitchord.data.listening.SmartPlaylistV2Query(),
            ),
        )
        val normal = LibraryPlaylistCard.CanonicalNormal(
            com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist(
                id = "normal", name = "Normal", recordingIds = emptyList(),
            ),
        )
        assertTrue(canQueueLibraryPlaylist(smart))
        assertTrue(canQueueLibraryPlaylist(normal))
        assertTrue(canDeleteLibraryPlaylist(normal))
        assertFalse(canQueueLibraryPlaylist(LibraryPlaylistCard.Normal(
            com.music.bitchord.data.model.ShelfItem(
                title = "Legacy", browseId = "local:playlist:test", subtitle = "",
            ),
        )))
    }
}
