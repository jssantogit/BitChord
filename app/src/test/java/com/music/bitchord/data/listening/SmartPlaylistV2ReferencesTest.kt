package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference results are canonical Recording memberships AFTER the referenced limit/sort. */
class SmartPlaylistV2ReferencesTest {
    private fun query(
        rule: SmartPlaylistRule,
        sort: SmartPlaylistV2Sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        limit: Int = 100,
    ) = SmartPlaylistV2Query(rule, sort = sort, limit = limit)

    private fun comparison(
        value: Double,
    ) = SmartPlaylistRule.Comparison(
        SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.GREATER_THAN, value,
    )
    private fun saved(id: String, rule: SmartPlaylistRule, limit: Int = 100) =
        SavedSmartPlaylistV2Definition(id, id, query(rule, limit = limit))

    private val rows = listOf(
        RuleBasedRecordingCandidate("a", null, 0, null, 100_000L, true),
        RuleBasedRecordingCandidate("b", null, 0, null, 200_000L, true),
        RuleBasedRecordingCandidate("c", null, 0, null, 300_000L, true),
    )

    @Test fun referencesUseActualLimitedResultsNotOnlyTheReferencedPredicate() {
        val base = saved("base", comparison(150_000.0), limit = 1)
        val root = query(SmartPlaylistRule.Reference("base"))
        val graph = resolveSmartPlaylistReferences(root, listOf(base))
        assertEquals(listOf("base"), graph.map { it.id })
        assertEquals(listOf("b"), previewSmartPlaylistReferences(
            rows, root, graph, SmartPlaylistV2RuleEngine { 900_000L },
        ).map { it.recordingId })
    }

    @Test fun nestedReferenceAndNotUseOneSnapshotWithDeterministicMembership() {
        val base = saved("base", comparison(150_000.0))
        val second = saved("second", SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Reference("base"),
            comparison(250_000.0),
        )))
        val root = query(SmartPlaylistRule.Not(SmartPlaylistRule.Reference("second")))
        val graph = resolveSmartPlaylistReferences(root, listOf(second, base))
        assertEquals(listOf("base", "second"), graph.map { it.id })
        assertEquals(listOf("a", "b"), previewSmartPlaylistReferences(
            rows, root, graph, SmartPlaylistV2RuleEngine { 900_000L },
        ).map { it.recordingId })
    }

    @Test fun missingDirectIndirectAndSelfCyclesRejectWithoutReturningEmptySuccess() {
        val a = saved("a", SmartPlaylistRule.Reference("b"))
        val b = saved("b", SmartPlaylistRule.Reference("a"))
        assertThrows(IllegalArgumentException::class.java) {
            resolveSmartPlaylistReferences(query(SmartPlaylistRule.Reference("missing")), listOf(a))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveSmartPlaylistReferences(query(SmartPlaylistRule.Reference("a")), listOf(a, b))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveSmartPlaylistReferences(query(SmartPlaylistRule.Reference("a")), listOf(
                saved("a", comparison(10.0)),
            ), rootId = "a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Reference(" "))
        }
    }

    @Test fun missingRuntimeMembershipMustNeverPassNegation() {
        val rule = query(SmartPlaylistRule.Not(SmartPlaylistRule.Reference("missing")))
        assertTrue(SmartPlaylistV2RuleEngine { 900_000L }.preview(rows, rule).isEmpty())
    }

    @Test fun sharedDependencyEvaluatesOnlyOnceAndTooDeepGraphsFailClosed() {
        val base = saved("base", comparison(150_000.0))
        val left = saved("left", SmartPlaylistRule.Reference("base"))
        val right = saved("right", SmartPlaylistRule.Reference("base"))
        val root = query(SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
            SmartPlaylistRule.Reference("left"), SmartPlaylistRule.Reference("right"),
        )))
        val graph = resolveSmartPlaylistReferences(root, listOf(right, base, left))
        assertEquals(listOf("base", "left", "right"), graph.map { it.id })
        val chain = (1..12).map { index ->
            saved("n$index", if (index == 12) comparison(5.0)
                else SmartPlaylistRule.Reference("n${index + 1}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveSmartPlaylistReferences(query(SmartPlaylistRule.Reference("n1")), chain)
        }
    }

    @Test fun negatedReferenceMustNeverIncludeUnknownChildEvidence() {
        val rated = saved("rated", SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 4.0,
        ))
        val all = listOf(
            RuleBasedRecordingCandidate("high", 5.0, 0, null, 100_000L, true),
            RuleBasedRecordingCandidate("low", 2.0, 0, null, 100_000L, true),
            RuleBasedRecordingCandidate("unknown", null, 0, null, 100_000L, true,
                ratingEvidenceUnknown = true),
        )
        val root = query(SmartPlaylistRule.Not(SmartPlaylistRule.Reference("rated")))
        val graph = resolveSmartPlaylistReferences(root, listOf(rated))
        assertEquals(listOf("low"), previewSmartPlaylistReferences(
            all, root, graph, SmartPlaylistV2RuleEngine { 900_000L },
        ).map { it.recordingId })
    }
}
