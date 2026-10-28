package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistWizardModelTest {
    @Test fun catalogHasEveryAndOnlyAnImplementedV2FieldExactlyOnce() {
        val fields = smartConditionCategories.flatMap { it.fields }
        assertEquals(SmartPlaylistRuleField.entries.toSet(), fields.toSet())
        assertEquals(fields.size, fields.distinct().size)
        assertEquals(listOf(SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE),
            smartConditionCategories.last().fields)
        assertEquals("Outros · avançado", smartConditionCategories.last().title)
        assertEquals(listOf(SmartPlaylistRuleField.SOURCE_KIND), smartConditionSearch("fonte"))
        assertEquals(listOf(SmartPlaylistRuleField.DURATION_MS), smartConditionSearch("duracao"))
        assertTrue(smartConditionSearch("pasta de origem").isEmpty())
        assertTrue(smartConditionSearch("compositor").isEmpty())
        assertEquals(listOf(SmartPlaylistRuleField.EXPLICIT_RATING),
            smartConditionSearch("estrelas"))
    }

    @Test fun immutablePathEditingPreservesNotGroupReferenceAndSiblingIdentity() {
        val one = smartRuleNewComparison(SmartPlaylistRuleField.SOURCE_KIND)
        val two = SmartPlaylistRule.Not(smartRuleNewComparison(SmartPlaylistRuleField.TITLE))
        val reference = SmartPlaylistRule.Reference("saved-definition")
        val sub = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(two, reference))
        val root = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(one, sub))
        assertEquals(reference, smartRuleAtPath(root, listOf(1, 1)))
        val replacement = smartRuleNewComparison(SmartPlaylistRuleField.TRACK_NUMBER)
        val revised = smartRuleReplaceAt(root, listOf(1, 0), replacement)
        assertEquals(replacement, smartRuleAtPath(revised, listOf(1, 0)))
        assertEquals(one, smartRuleAtPath(revised, listOf(0)))
        assertEquals(two, smartRuleAtPath(root, listOf(1, 0)))
        assertEquals(3, smartRuleLeafCount(revised))
        val added = smartRuleAppendAt(revised, listOf(1), one)
        assertEquals(3, (smartRuleAtPath(added, listOf(1)) as SmartPlaylistRule.Group).children.size)
        val removed = smartRuleRemoveAt(added, listOf(1, 2))
        assertEquals(revised, removed)
    }

    @Test fun addSecondConditionToFlatLegacyNodePreservesOriginalSemanticsAndNot() {
        val negative = SmartPlaylistRule.Not(smartRuleNewComparison(SmartPlaylistRuleField.TITLE))
        val root = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(negative))
        val added = smartRuleNewComparison(SmartPlaylistRuleField.SOURCE_KIND)
        val revised = smartRuleAddConditionAt(root, listOf(0), added)
        val group = smartRuleAtPath(revised, listOf(0)) as SmartPlaylistRule.Group
        assertEquals(SmartPlaylistRuleJoin.ALL, group.join)
        assertEquals(listOf(negative, added), group.children)
        assertEquals(negative, smartRuleAtPath(root, listOf(0)))
    }

    @Test fun nestedNotGroupKeepsItsBooleanMeaningThroughEditMoveAndDelete() {
        val ref = SmartPlaylistRule.Reference("linked-playlist")
        val first = smartRuleNewComparison(SmartPlaylistRuleField.TITLE)
        val extra = smartRuleNewComparison(SmartPlaylistRuleField.SOURCE_KIND)
        val wrapped = SmartPlaylistRule.Not(
            SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(ref, first)),
        )
        val root = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(wrapped, extra))
        assertEquals(ref, smartRuleAtPath(root, listOf(0, 0)))
        val appended = smartRuleAddConditionAt(root, listOf(0), extra)
        assertEquals(listOf(ref, first, extra),
            ((smartRuleAtPath(appended, listOf(0)) as SmartPlaylistRule.Not)
                .child as SmartPlaylistRule.Group).children)
        val changed = smartRuleUpdateGroup(appended, listOf(0)) {
            it.copy(join = SmartPlaylistRuleJoin.ALL,
                children = it.children.reversed())
        }
        val deleted = smartRuleRemoveAt(changed, listOf(0, 0))
        val current = smartRuleAtPath(deleted, listOf(0)) as SmartPlaylistRule.Not
        assertEquals(SmartPlaylistRuleJoin.ALL,
            (current.child as SmartPlaylistRule.Group).join)
        assertEquals(listOf(first, ref),
            (current.child as SmartPlaylistRule.Group).children)
        assertEquals(wrapped, smartRuleAtPath(root, listOf(0)))
    }

    @Test fun sourceCardDescribesRequestedRulesWithoutClaimingProviderSelection() {
        val unrelated = smartRuleNewComparison(SmartPlaylistRuleField.TITLE)
        assertTrue(smartRuleSourceSummary(unrelated).contains("Nenhuma condição"))
        val disjunction = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND,
                SmartPlaylistRuleOperator.EQUAL,
                textValue = "local",
            ),
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.SOURCE_KIND,
                SmartPlaylistRuleOperator.EQUAL,
                textValue = "opensubsonic",
            )),
        ))
        val summary = smartRuleSourceSummary(disjunction)
        assertTrue(summary.contains("músicas locais"))
        assertTrue(summary.contains("OpenSubsonic (conta ativa)"))
        assertTrue(summary.contains("E/OU/NÃO"))
        assertTrue(summary.contains("não seleciona fontes"))
    }

    @Test fun invalidPathsAndLastChildDeletionFailClosed() {
        val root = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
            listOf(smartRuleNewComparison(SmartPlaylistRuleField.EXPLICIT_RATING)))
        assertThrows(IllegalArgumentException::class.java) {
            smartRuleAtPath(root, listOf(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            smartRuleRemoveAt(root, listOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            smartRuleAppendAt(root, listOf(0), root)
        }
    }
}
