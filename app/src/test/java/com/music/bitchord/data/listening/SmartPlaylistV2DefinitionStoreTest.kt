package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class SmartPlaylistV2DefinitionStoreTest {
    @Test fun nestedQueryRoundTripsWithoutChangingLegacyDefinitions() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val legacy = SmartPlaylistDefinitionRepository(db.definitionDao())
            val modern = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            legacy.save(SavedSmartPlaylistDefinition(
                id = "v1", name = "Old", query = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 4.0),
            ))
            val query = SmartPlaylistV2Query(
                rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.EXPLICIT_RATING,
                        SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 4.0,
                    ),
                    SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                        SmartPlaylistRuleOperator.GREATER_THAN, 2.0,
                    )),
                )),
                sort = SmartPlaylistV2Sort.COMPLETIONS_ASC,
                limit = 200,
                randomSeed = 73,
            )
            modern.save(SavedSmartPlaylistV2Definition("v2", "New", query))
            assertEquals(query, modern.list().single().query)
            assertEquals("Old", legacy.list().single().name)
            assertEquals(4.0, legacy.list().single().query.minimumExplicitRating)
        } finally {
            db.close()
        }
    }

    @Test fun v1ThresholdsConvertToEquivalentV2Rules() {
        val old = RuleBasedSmartPlaylistQuery(
            minimumExplicitRating = 2.0,
            minimumCompletions = 1L,
            minimumDaysSinceAudible = 30L,
            maximumDaysSinceAudible = 40L,
            minimumDurationMs = 180_000L,
            limit = 80,
        )
        val v2 = old.toV2()
        val now = 90L * 86_400_000L
        val tracks = listOf(
            RuleBasedRecordingCandidate("pass", 2.0, 1, 55L * 86_400_000L, 200_000L, true),
            RuleBasedRecordingCandidate("short", 2.0, 1, 55L * 86_400_000L, 100_000L, true),
            RuleBasedRecordingCandidate("recent", 5.0, 1, 85L * 86_400_000L, 200_000L, true),
        )
        assertEquals(
            RuleBasedSmartPlaylistPlanner { now }.preview(tracks, old).map { it.recordingId },
            SmartPlaylistV2RuleEngine { now }.preview(tracks, v2).map { it.recordingId },
        )
        assertEquals(80, v2.limit)
    }

    @Test fun storedJsonIsVersionedAndBadDefinitionsFailClosed() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            db.v2DefinitionDao().upsert(SmartPlaylistV2DefinitionEntity(
                id = "broken", name = "Broken", schemaVersion = 999, queryJson = "{}",
            ))
            val failure = runCatching { repo.list() }.exceptionOrNull()
            assertTrue("Unknown schema must not load as a fabricated rule", failure is IllegalArgumentException)
        } finally {
            db.close()
        }
    }
}
