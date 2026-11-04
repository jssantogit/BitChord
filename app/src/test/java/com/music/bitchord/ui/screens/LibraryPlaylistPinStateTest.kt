package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/** Playlist pin identities are separate from canonical release/artist/recording IDs. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
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
                query = com.music.bitchord.data.listening.SmartPlaylistV2Query(
                    com.music.bitchord.data.listening.SmartPlaylistRule.Group(
                        com.music.bitchord.data.listening.SmartPlaylistRuleJoin.ALL,
                        listOf(com.music.bitchord.data.listening.SmartPlaylistRule.Comparison(
                            field = com.music.bitchord.data.listening.SmartPlaylistRuleField.TOTAL_SKIPS,
                            operator = com.music.bitchord.data.listening.SmartPlaylistRuleOperator.GREATER_THAN,
                            value = 0.0,
                        )),
                    ),
                ),
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
                title = "Legacy", browseId = "local:playlist:test",
                subtitle = "", thumbnailUrl = null, videoId = null,
            ),
        )))
    }
}
