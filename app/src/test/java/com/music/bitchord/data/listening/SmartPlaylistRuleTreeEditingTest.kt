package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class SmartPlaylistRuleTreeEditingTest {
    @Test fun movingNestedGroupPreservesTheExactOriginalNodesAndOperators() {
        val a = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
            textValue = "live",
        )
        val nested = SmartPlaylistRule.Not(SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ANY, listOf(
                SmartPlaylistRule.Reference("playlist-child"),
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.EXPLICIT_RATING,
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 4.0,
                ),
            ),
        ))
        val last = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
            textValue = "local",
        )
        val original = SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ALL, listOf(a, nested, last),
        )
        val moved = original.moveChild(1, 0)
        assertEquals(listOf(nested, a, last), moved.children)
        assertEquals(listOf(a, nested, last), original.children)
        assertEquals(SmartPlaylistRuleJoin.ALL, moved.join)
        SmartPlaylistV2Query(moved) // Remains persistable/valid after reorder.
    }

    @Test fun siblingMoveToFinalPositionAndNoOpAreDeterministic() {
        val first = SmartPlaylistRule.Reference("saved-1")
        val second = SmartPlaylistRule.Reference("saved-2")
        val last = SmartPlaylistRule.Reference("saved-3")
        val group = SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ANY, listOf(first, second, last),
        )
        assertEquals(listOf(second, last, first), group.moveChild(0, 2).children)
        assertSame(group, group.moveChild(1, 1))
        assertThrows(IllegalArgumentException::class.java) { group.moveChild(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { group.moveChild(0, 3) }
    }
}
