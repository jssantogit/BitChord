package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPlaylistStyleRuleTest {
    private fun row(id: String, values: List<String> = emptyList(), unknown: Boolean = true) =
        RuleBasedRecordingCandidate(id, null, 0, null, 150_000L, true,
            styles = values, styleEvidenceUnknown = unknown)

    private val engine = SmartPlaylistV2RuleEngine { 10_000L }
    private fun preview(rows: List<RuleBasedRecordingCandidate>,
                        operator: SmartPlaylistRuleOperator, text: String? = null): List<String> =
        engine.preview(rows, SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.STYLE,
                operator, textValue = text),
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )).map { it.recordingId }

    @Test fun multivaluedLocalStylesMatchAndMissingIsNotUnsupported() {
        val rows = listOf(
            row("pop", listOf("Dream Pop", "Shoegaze"), false),
            row("empty", emptyList(), false),
            row("unknown"),
            row("partial", listOf("Shoegaze"), true),
        )
        assertEquals(listOf("partial", "pop"),
            preview(rows, SmartPlaylistRuleOperator.EQUAL, "shoegaze"))
        assertEquals(listOf("empty"),
            preview(rows, SmartPlaylistRuleOperator.IS_MISSING))
        assertEquals(listOf("partial", "pop"),
            preview(rows, SmartPlaylistRuleOperator.IS_PRESENT))
        assertEquals(listOf("pop"),
            preview(rows, SmartPlaylistRuleOperator.DOES_NOT_CONTAIN, "metal"))
    }
}
