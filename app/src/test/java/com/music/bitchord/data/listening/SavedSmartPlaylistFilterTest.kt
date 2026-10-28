package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
class SavedSmartPlaylistFilterTest {
    private val cleanRule = SmartPlaylistRule.Group(
        SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TRACK_ARTIST,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "NewJeans",
            ),
            SmartPlaylistRule.Not(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.EQUAL, value = 0.0,
            )),
        ),
    )

    @Test fun savedFilterRoundTripIsIndependentOfPlaylistSortAndLimit() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val filters = SmartPlaylistV2FilterRepository(db.v2FilterDao())
            val playlists = SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
            val saved = SavedSmartPlaylistV2Filter("filter:one", "Artistas escutados", cleanRule)
            filters.save(saved)
            assertEquals(saved, filters.list().single())
            val exported = saved.toPlaylistCopy("playlist:one", "Artistas escutados")
            assertEquals(saved.rules, exported.query.rules)
            assertEquals(SmartPlaylistV2Sort.RATING_DESC, exported.query.sort)
            playlists.save(exported)
            assertEquals(saved.rules, playlists.list().single().query.rules)
            // Modifying the target playlist must not rewrite its reusable source.
            val edited = exported.copy(query = SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.DURATION_MS,
                    SmartPlaylistRuleOperator.IS_PRESENT,
                ),
            ))
            playlists.save(edited)
            assertEquals(saved, filters.list().single())
            assertEquals(listOf(saved), filters.observe().first())
            filters.remove(saved.id)
            assertTrue(filters.list().isEmpty())
            assertEquals(edited, playlists.list().single())
        } finally {
            db.close()
        }
    }

    @Test fun templatesCanBeCombinedWithAllWithoutApplyingAnArtificial100SongLimit() {
        val template = SavedSmartPlaylistV2Filter("filter:one", "Reproduzidas", cleanRule)
        val root = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 10_000.0,
        )
        val combined = root.andSavedFilter(template)
        val query = SmartPlaylistV2Query(combined, limit = 10_000)
        val items = (1..250).map { index ->
            RuleBasedRecordingCandidate("r:$index", null, 1L, null, 20_000L, true,
                trackArtistNames = listOf("NewJeans"),
                trackArtistEvidenceUnknown = false,
            )
        }
        val results = SmartPlaylistV2RuleEngine { 10_000_000L }.preview(items, query)
        assertEquals(250, results.size)
        assertEquals(2, (combined as SmartPlaylistRule.Group).children.size)
        assertEquals(cleanRule, template.rules)
    }

    @Test fun invalidAndFutureFilterPayloadsFailClosed() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val filters = SmartPlaylistV2FilterRepository(db.v2FilterDao())
            assertThrows(IllegalArgumentException::class.java) {
                SmartPlaylistV2FilterRepository.validateFilterName("   ")
            }
            val malformed = SavedSmartPlaylistV2Filter("f:bad", "Malformed",
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.DURATION_MS,
                    SmartPlaylistRuleOperator.CONTAINS, textValue = "invalid",
                ))
            assertTrue(runCatching { filters.save(malformed) }.exceptionOrNull()
                is IllegalArgumentException)
            db.v2FilterDao().upsert(SmartPlaylistV2FilterEntity(
                "future", "Future", 99, "{}",
            ))
            assertTrue(runCatching { filters.list() }.exceptionOrNull()
                is IllegalArgumentException)
            assertEquals(null, runCatching { filters.list() }.getOrNull())
        } finally {
            db.close()
        }
    }

    @Test fun migratingVersionTwoPreservesPlaylistsAndAddsIndependentFilterTable() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "smart-playlist-v2-to-v3-filter-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            old.execSQL("""
                CREATE TABLE IF NOT EXISTS smart_playlist_definition (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL, minimumExplicitRating REAL,
                    minimumCompletions INTEGER, minimumDaysSinceAudible INTEGER,
                    maximumDaysSinceAudible INTEGER, minimumDurationMs INTEGER,
                    `limit` INTEGER NOT NULL
                )
            """.trimIndent())
            old.execSQL("""
                CREATE TABLE IF NOT EXISTS smart_playlist_v2_definition (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL, queryJson TEXT NOT NULL
                )
            """.trimIndent())
            old.execSQL("INSERT INTO smart_playlist_definition (id,name,schemaVersion,`limit`) VALUES ('classic','Old',1,30)")
            // A properly serialized V2 query is saved via Repository in other tests;
            // the migration must preserve the row itself byte-for-byte.
            old.execSQL("INSERT INTO smart_playlist_v2_definition VALUES ('v2','Advanced',2,'unchanged')")
            old.version = 2
        } finally {
            old.close()
        }
        val db = Room.databaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java, name)
            .addMigrations(
                SmartPlaylistDefinitionDatabase.MIGRATION_1_2,
                SmartPlaylistDefinitionDatabase.MIGRATION_2_3,
            ).allowMainThreadQueries().build()
        try {
            assertEquals("Old", db.definitionDao().all().single().name)
            assertEquals("unchanged", db.v2DefinitionDao().all().single().queryJson)
            val repo = SmartPlaylistV2FilterRepository(db.v2FilterDao())
            repo.save(SavedSmartPlaylistV2Filter("f:one", "Reusable", cleanRule))
            assertEquals(cleanRule, repo.list().single().rules)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
