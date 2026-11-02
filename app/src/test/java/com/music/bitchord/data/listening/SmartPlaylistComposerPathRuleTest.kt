package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Path and composer stay independent of MediaAsset source or account. */
class SmartPlaylistComposerPathRuleTest {
    private val engine = SmartPlaylistV2RuleEngine { 1_000_000L }
    private fun row(
        id: String,
        composers: List<String> = emptyList(), composerUnknown: Boolean = true,
        paths: List<String> = emptyList(), pathUnknown: Boolean = true,
        folders: List<String> = emptyList(), folderUnknown: Boolean = true,
    ) = RuleBasedRecordingCandidate(
        recordingId = id, explicitRating = null, audibleCompletions = 0,
        lastAudibleAtEpochMs = null, durationMs = 90_000, available = true,
        composers = composers, composerEvidenceUnknown = composerUnknown,
        filePaths = paths, filePathEvidenceUnknown = pathUnknown,
        folderPaths = folders, folderPathEvidenceUnknown = folderUnknown,
    )
    private fun matching(
        rows: List<RuleBasedRecordingCandidate>,
        field: SmartPlaylistRuleField,
        op: SmartPlaylistRuleOperator,
        text: String? = null,
    ) = engine.preview(rows, SmartPlaylistV2Query(
        rules = SmartPlaylistRule.Comparison(field, op, textValue = text),
        sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
    )).map { it.recordingId }

    @Test fun composerSupportsMultipleCreditsWithoutTreatingUnknownAsEmpty() {
        val rows = listOf(
            row("two", composers = listOf("Alice", "Bob"), composerUnknown = false),
            row("none", composers = emptyList(), composerUnknown = false),
            row("unknown"),
            row("partial", composers = listOf("Bob"), composerUnknown = true),
        )
        assertEquals(listOf("partial", "two"), matching(rows,
            SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.EQUAL, "bob"))
        assertEquals(listOf("none"), matching(rows,
            SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.IS_MISSING))
        assertEquals(listOf("two"), matching(rows,
            SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleOperator.NOT_EQUAL, "Sam"))
    }

    @Test fun fullFilePathAndParentFolderAreDifferentRules() {
        val rows = listOf(
            row("alpha", paths = listOf("/storage/Music/Albums/01.mp3"), pathUnknown = false,
                folders = listOf("/storage/Music/Albums"), folderUnknown = false),
            row("beta", paths = listOf("/storage/Music/Albums 2/01.mp3"), pathUnknown = false,
                folders = listOf("/storage/Music/Albums 2"), folderUnknown = false),
            row("remote"), // A provider without a path is UNKNOWN, never IS_MISSING.
        )
        assertEquals(listOf("alpha"), matching(rows,
            SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.EQUAL,
            "/storage/music/albums"))
        assertEquals(listOf("alpha"), matching(rows,
            SmartPlaylistRuleField.FILE_PATH, SmartPlaylistRuleOperator.ENDS_WITH, "/01.mp3")
                .filter { it == "alpha" })
        assertEquals(emptyList<String>(), matching(rows,
            SmartPlaylistRuleField.FOLDER_PATH, SmartPlaylistRuleOperator.IS_MISSING))
        assertEquals(listOf("alpha", "beta"), matching(rows,
            SmartPlaylistRuleField.FILE_PATH, SmartPlaylistRuleOperator.IS_PRESENT))
    }

    @Test fun pathAndComposerDoNotAcceptNumericComparisons() {
        for (field in listOf(SmartPlaylistRuleField.FOLDER_PATH,
                SmartPlaylistRuleField.FILE_PATH, SmartPlaylistRuleField.COMPOSER)) {
            assertThrows(IllegalArgumentException::class.java) {
                SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                    field, SmartPlaylistRuleOperator.GREATER_THAN, value = 10.0))
            }
        }
    }
}
