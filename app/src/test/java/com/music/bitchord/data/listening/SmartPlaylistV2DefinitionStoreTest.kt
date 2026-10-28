package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
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
    @Test fun onDiskRoomMigrationKeepsExistingV1PlaylistAndCreatesV2Table() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "smart-playlist-v2-migration-test.db"
        context.deleteDatabase(name)
        val oldSqlite = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            oldSqlite.execSQL("""
                CREATE TABLE IF NOT EXISTS `smart_playlist_definition` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `name` TEXT NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `minimumExplicitRating` REAL,
                    `minimumCompletions` INTEGER,
                    `minimumDaysSinceAudible` INTEGER,
                    `maximumDaysSinceAudible` INTEGER,
                    `minimumDurationMs` INTEGER,
                    `limit` INTEGER NOT NULL
                )
            """.trimIndent())
            oldSqlite.execSQL(
                "INSERT INTO smart_playlist_definition " +
                    "(id, name, schemaVersion, minimumExplicitRating, `limit`) " +
                    "VALUES ('legacy', 'Saved before migration', 1, 5.0, 50)",
            )
            oldSqlite.version = 1
        } finally {
            oldSqlite.close()
        }

        val reopened = Room.databaseBuilder(
            context, SmartPlaylistDefinitionDatabase::class.java, name,
        ).addMigrations(SmartPlaylistDefinitionDatabase.MIGRATION_1_2)
            .allowMainThreadQueries().build()
        try {
            val old = SmartPlaylistDefinitionRepository(reopened.definitionDao()).list()
            assertEquals("Saved before migration", old.single().name)
            assertEquals(5.0, old.single().query.minimumExplicitRating)
            val v2 = SmartPlaylistV2DefinitionRepository(reopened.v2DefinitionDao())
            v2.save(SavedSmartPlaylistV2Definition(
                id = "new", name = "Nested",
                query = SmartPlaylistV2Query(rules = SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.EXPLICIT_RATING,
                    SmartPlaylistRuleOperator.IS_PRESENT,
                )),
            ))
            assertEquals("new", v2.list().single().id)
            assertEquals("legacy", SmartPlaylistDefinitionRepository(
                reopened.definitionDao(),
            ).list().single().id)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun textRulesRoundTripWithOldNumericRulesAndJsonDefaults() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            val query = SmartPlaylistV2Query(
                rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.ENDS_WITH,
                        textValue = "shy",
                    ),
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.EXPLICIT_RATING,
                        SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                        value = 4.0,
                    ),
                )),
                sort = SmartPlaylistV2Sort.ARTIST_ASC,
            )
            repo.save(SavedSmartPlaylistV2Definition("text-case", "Case", query))
            assertEquals(query, repo.list().single().query)
        } finally {
            db.close()
        }
    }

    @Test fun classicDefinitionCopyCreatesDistinctV2RecordWithoutDeletingOriginal() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val legacy = SmartPlaylistDefinitionRepository(db.definitionDao())
            val modern = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            val original = SavedSmartPlaylistDefinition(
                id = "classic:1",
                name = "Classics",
                query = RuleBasedSmartPlaylistQuery(
                    minimumExplicitRating = 2.0,
                    minimumCompletions = 1,
                    limit = 70,
                ),
            )
            legacy.save(original)
            val converted = original.toV2Copy("advanced:2")
            modern.save(converted)
            assertEquals("advanced:2", modern.list().single().id)
            assertEquals("Classics (V2)", modern.list().single().name)
            assertEquals(original.query.toV2(), modern.list().single().query)
            assertEquals(original, legacy.list().single())
        } finally {
            db.close()
        }
    }

    @Test fun copyingClassicDefinitionToItsSameIdIsNeverAllowed() {
        val original = SavedSmartPlaylistDefinition(
            id = "old-id", name = "Old",
            query = RuleBasedSmartPlaylistQuery(minimumCompletions = 1),
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            original.toV2Copy("old-id")
        }
    }

    @Test fun canonicalReleaseTitleRulesSurviveJsonPersistence() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val query = SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.RELEASE_TITLE,
                    SmartPlaylistRuleOperator.CONTAINS,
                    textValue = "Get Up",
                ),
                sort = SmartPlaylistV2Sort.TITLE_ASC,
            )
            val repository = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            repository.save(SavedSmartPlaylistV2Definition("v2:release", "Get Up", query))
            assertEquals(query, repository.list().single().query)
        } finally {
            db.close()
        }
    }


    @Test fun referencesPersistAndRejectCyclesMissingTargetsAndLiveDeletion() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            fun q(rule: SmartPlaylistRule) = SmartPlaylistV2Query(
                rule, sort = SmartPlaylistV2Sort.RECORDING_ID_ASC, limit = 20,
            )
            val comparison = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.IS_PRESENT,
            )
            repo.save(SavedSmartPlaylistV2Definition("base", "Base", q(comparison)))
            val dependent = SavedSmartPlaylistV2Definition(
                "dependent", "Depends", q(SmartPlaylistRule.Reference("base")),
            )
            repo.save(dependent)
            assertEquals(dependent.query, repo.list().first { it.id == "dependent" }.query)
            val deleteFailed = runCatching { repo.remove("base") }.exceptionOrNull()
            assertTrue("Deletion of a live referenced playlist must fail",
                deleteFailed is IllegalArgumentException)
            val wouldCycle = SavedSmartPlaylistV2Definition(
                "base", "Base", q(SmartPlaylistRule.Reference("dependent")),
            )
            assertTrue(runCatching { repo.save(wouldCycle) }.exceptionOrNull()
                is IllegalArgumentException)
            assertTrue(runCatching {
                repo.save(SavedSmartPlaylistV2Definition(
                    "missing", "Missing", q(SmartPlaylistRule.Reference("gone")),
                ))
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(comparison, repo.list().first { it.id == "base" }.query.rules)
            repo.remove("dependent")
            repo.remove("base")
            assertTrue(repo.list().isEmpty())
        } finally {
            db.close()
        }
    }
}
