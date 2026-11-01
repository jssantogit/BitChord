package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** BPM belongs to canonical rules, not the server or the local scanner. */
class SmartPlaylistBpmRuleTest {
    private val engine = SmartPlaylistV2RuleEngine { 1_000_000L }
    private fun row(id: String, values: List<Double> = emptyList(), unknown: Boolean = true) =
        RuleBasedRecordingCandidate(
            recordingId = id, explicitRating = null, audibleCompletions = 0,
            lastAudibleAtEpochMs = null, durationMs = 120_000L, available = true,
            bpms = values, bpmEvidenceUnknown = unknown,
        )

    private fun matches(
        data: List<RuleBasedRecordingCandidate>,
        operator: SmartPlaylistRuleOperator,
        threshold: Double? = null,
        negate: Boolean = false,
    ): List<String> {
        val comparison = SmartPlaylistRule.Comparison(
            field = SmartPlaylistRuleField.BPM, operator = operator, value = threshold,
        )
        return engine.preview(data, SmartPlaylistV2Query(
            rules = if (negate) SmartPlaylistRule.Not(comparison) else comparison,
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )).map { it.recordingId }
    }

    @Test fun knownEmptyIsNotZeroAndUnsupportedMetadataIsNotMissing() {
        val rows = listOf(
            row("filled", listOf(128.5), false),
            row("missing", emptyList(), false),
            row("unknown"),
            row("partial", listOf(120.0), true),
        )
        assertEquals(listOf("filled", "partial"),
            matches(rows, SmartPlaylistRuleOperator.GREATER_THAN, 110.0))
        assertEquals(listOf("missing"),
            matches(rows, SmartPlaylistRuleOperator.IS_MISSING))
        assertEquals(listOf("filled", "partial"),
            matches(rows, SmartPlaylistRuleOperator.IS_PRESENT))
        assertEquals(listOf("missing"),
            matches(rows, SmartPlaylistRuleOperator.IS_PRESENT, negate = true))
        assertEquals(emptyList<String>(),
            matches(rows, SmartPlaylistRuleOperator.LESS_THAN, 100.0))
    }

    @Test fun negativeConditionRequiresCompleteEvidenceFromEveryActiveCopy() {
        val rows = listOf(
            row("proven", listOf(128.0, 130.0), false),
            row("unreported", listOf(128.0), true),
            row("match", listOf(100.0, 128.0), false),
            row("unknown"),
        )
        assertEquals(listOf("proven"),
            matches(rows, SmartPlaylistRuleOperator.NOT_EQUAL, 100.0))
        assertEquals(listOf("match"),
            matches(rows, SmartPlaylistRuleOperator.EQUAL, 100.0))
    }

    @Test fun textOperatorsCannotBeAppliedToNumericTempo() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.BPM,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "128",
            ))
        }
    }
}
