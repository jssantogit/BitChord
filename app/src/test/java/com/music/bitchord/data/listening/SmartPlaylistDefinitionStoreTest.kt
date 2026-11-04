package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistDefinitionStoreTest {
    @Test fun definitionsPersistOnlyRulesAcrossDatabaseReopenAndSupportEditAndDelete() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "smart-playlist-test-reopen.db"
        context.deleteDatabase(dbName)
        val original = SavedSmartPlaylistDefinition(
            id = "local-1", name = " Favorites ",
            query = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 4.0, limit = 25),
        )
        var db = Room.databaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistDefinitionRepository(db.definitionDao())
            repo.save(original)
            assertEquals("Favorites", repo.list().single().name)
            assertEquals(25, repo.list().single().query.limit)
            db.close()
            db = Room.databaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java, dbName)
                .allowMainThreadQueries().build()
            val reopened = SmartPlaylistDefinitionRepository(db.definitionDao())
            assertEquals("Favorites", reopened.observe().first().single().name)
            reopened.save(original.copy(
                name = "Heard twice",
                query = RuleBasedSmartPlaylistQuery(minimumCompletions = 2, limit = 10),
            ))
            assertEquals("Heard twice", reopened.list().single().name)
            assertEquals(2L, reopened.list().single().query.minimumCompletions)
            reopened.remove("local-1")
            assertTrue(reopened.list().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test fun invalidDefinitionsNeverPersistOrOverwriteSavedCriteria() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistDefinitionRepository(db.definitionDao())
            val valid = SavedSmartPlaylistDefinition(
                "local-1", "Good", RuleBasedSmartPlaylistQuery(minimumCompletions = 2),
            )
            repo.save(valid)
            assertTrue(runCatching { repo.save(valid.copy(name = "  ")) }.isFailure)
            assertTrue(runCatching { repo.save(valid.copy(schemaVersion = 999)) }.isFailure)
            assertTrue(runCatching {
                RuleBasedSmartPlaylistQuery(minimumExplicitRating = Double.NaN)
            }.isFailure)
            assertTrue(runCatching {
                RuleBasedSmartPlaylistQuery(minimumDurationMs = 1L, limit = 101)
            }.isFailure)
            assertEquals(listOf(valid), repo.list())
        } finally {
            db.close()
        }
    }

    @Test fun editorAcceptsOnlyL6RulesWithFiniteBoundedValues() {
        val valid = com.music.bitchord.ui.screens.SmartPlaylistEditorDraft(
            name = "My rules", minimumRating = "4.0",
            minimumCompletions = "2", minimumDurationMinutes = "3", limit = "50",
        ).toQuery()
        assertEquals(4.0, valid.minimumExplicitRating)
        assertEquals(2L, valid.minimumCompletions)
        assertEquals(180_000L, valid.minimumDurationMs)
        assertTrue(runCatching {
            com.music.bitchord.ui.screens.SmartPlaylistEditorDraft(minimumRating = "NaN").toQuery()
        }.isFailure)
        assertTrue(runCatching {
            com.music.bitchord.ui.screens.SmartPlaylistEditorDraft(minimumCompletions = "2.5").toQuery()
        }.isFailure)
        assertTrue(runCatching {
            com.music.bitchord.ui.screens.SmartPlaylistEditorDraft(minimumRating = "4", limit = "0").toQuery()
        }.isFailure)
        assertTrue(runCatching {
            com.music.bitchord.ui.screens.SmartPlaylistEditorDraft(
                minimumDurationMinutes = "9223372036854775807",
            ).toQuery()
        }.isFailure)
    }

    @Test fun rulePreviewEnforcesFiltersAndSupportsMoreThanFiveThousand() {
        val rule = RuleBasedSmartPlaylistQuery(
            minimumExplicitRating = 4.0, minimumCompletions = 2, limit = 10,
        )
        val planner = RuleBasedSmartPlaylistPlanner(nowEpochMs = { 86_400_000L * 40L })
        fun candidate(id: String, rating: Double, completions: Long) = RuleBasedRecordingCandidate(
            recordingId = id, explicitRating = rating, audibleCompletions = completions,
            lastAudibleAtEpochMs = 86_400_000L, durationMs = 180_000L, available = true,
        )
        assertEquals(emptyList<RuleBasedSmartPlaylistItem>(), planner.preview(emptyList(), rule))
        val subset = planner.preview(listOf(candidate("b", 4.5, 2),
            candidate("a", 5.0, 2), candidate("c", 5.0, 1)), rule)
        assertEquals(listOf("a", "b"), subset.map { it.recordingId })
        assertTrue(subset.all { it.reasons.contains(RuleBasedInclusionReason.EXPLICIT_RATING) &&
            it.reasons.contains(RuleBasedInclusionReason.CONFIRMED_COMPLETION) })
        assertEquals(10, planner.preview((1..5_001).map {
            candidate("rec:$it", 5.0, 3)
        }, rule).size)
    }
}
