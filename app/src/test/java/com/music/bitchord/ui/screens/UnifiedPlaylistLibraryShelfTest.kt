package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPlaylistLibraryShelfTest {
    private fun smart(id: String) = LibraryPlaylistCard.Smart(
        SavedSmartPlaylistV2Definition(
            id = id,
            name = "Smart $id",
            query = SmartPlaylistV2Query(
                rules = SmartPlaylistRule.Group(
                    SmartPlaylistRuleJoin.ALL,
                    listOf(SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE,
                        SmartPlaylistRuleOperator.IS_PRESENT,
                    )),
                ),
            ),
        ),
    )

    private fun normal(id: String) = LibraryPlaylistCard.Normal(
        ShelfItem(
            title = "Normal $id",
            subtitle = "",
            thumbnailUrl = null,
            videoId = null,
            browseId = "local:playlist:$id",
        ),
    )

    @Test fun canonicalLegacyAndSmartPlaylistsKeepDistinctKeysWithoutFakeBrowseIds() {
        val cards = listOf(
            smart("a"),
            LibraryPlaylistCard.CanonicalNormal(
                SavedCanonicalStaticPlaylist("a", "Normal a", listOf("rec-a")),
            ),
            normal("a"),
        )
        assertEquals(listOf("v2:a", "normal:a", "local:local:playlist:a"),
            cards.map { it.id })
        assertEquals(cards.size, cards.map { it.id }.distinct().size)
        assertTrue(cards[0] is LibraryPlaylistCard.Smart)
    }

    @Test fun openPlaylistResolvesUpdatedSavedDefinitionByStableId() {
        val before = LibraryPlaylistCard.CanonicalNormal(
            SavedCanonicalStaticPlaylist("playlist-a", "Old title", listOf("rec-1")),
        )
        val after = LibraryPlaylistCard.CanonicalNormal(
            SavedCanonicalStaticPlaylist("playlist-a", "Updated title", listOf("rec-2")),
        )
        val resolved = latestLibraryPlaylistCard(before, listOf(after))
        assertEquals("Updated title", resolved?.title)
        assertEquals(listOf("rec-2"),
            (resolved as LibraryPlaylistCard.CanonicalNormal).definition.recordingIds)
        assertEquals(null, latestLibraryPlaylistCard(before, emptyList()))
    }

    @Test fun persistedNormalPlaylistKeepsCanonicalMemberOrderInItsCard() {
        val saved = SavedCanonicalStaticPlaylist(
            "p1", "Viagem", listOf("rec-3", "rec-1", "rec-2"),
            sourceProviderKey = "local-mediastore",
        )
        val card = LibraryPlaylistCard.CanonicalNormal(saved)
        assertEquals("Viagem", card.title)
        assertEquals(saved.recordingIds, card.definition.recordingIds)
        assertEquals("local-mediastore", card.definition.sourceProviderKey)
    }
}
