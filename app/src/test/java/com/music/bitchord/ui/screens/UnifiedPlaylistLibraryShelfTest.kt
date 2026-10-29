package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
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

    @Test fun canonicalAndNormalPlaylistsHaveSeparateStableIdsInTwoColumnRows() {
        val cards = listOf(smart("a"), normal("a"), smart("b"), normal("b"), smart("c"))
        val rows = unifiedPlaylistRows(cards)
        assertEquals(listOf(2, 2, 1), rows.map { it.size })
        assertEquals(cards, rows.flatten())
        assertEquals(cards.size, cards.map { it.id }.distinct().size)
        assertEquals("v2:a", cards[0].id)
        assertEquals("local:local:playlist:a", cards[1].id)
    }

    @Test fun collapsedRowsAreVerticalAndExpandedRowsAreCompleteTwoColumnGrid() {
        val cards = listOf(smart("a"), normal("a"), smart("b"), normal("b"), smart("c"))
        val compact = playlistShelfRows(cards, expanded = false)
        assertEquals(listOf(1, 1, 1), compact.map { it.size })
        assertEquals(cards.take(3), compact.flatten())
        val grid = playlistShelfRows(cards, expanded = true)
        assertEquals(listOf(2, 2, 1), grid.map { it.size })
        assertEquals(cards, grid.flatten())
    }

    @Test fun emptyAndOneItemShelvesNeverFabricatePlaylists() {
        assertEquals(emptyList<List<LibraryPlaylistCard>>(), unifiedPlaylistRows(emptyList()))
        assertEquals(listOf(listOf(smart("one"))), unifiedPlaylistRows(listOf(smart("one"))))
        assertEquals(emptyList<List<LibraryPlaylistCard>>(), playlistShelfRows(emptyList(), false))
        assertEquals(emptyList<List<LibraryPlaylistCard>>(), playlistShelfRows(emptyList(), true))
        assertEquals(listOf(listOf(smart("one"))),
            playlistShelfRows(listOf(smart("one")), false))
    }
}
