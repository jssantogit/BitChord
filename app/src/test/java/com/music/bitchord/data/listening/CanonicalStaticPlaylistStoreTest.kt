package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
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
class CanonicalStaticPlaylistStoreTest {
    @Test fun storesOrderedCanonicalIdsWithoutProviderOrVideoIdentity() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = CanonicalStaticPlaylistRepository(db.staticPlaylistDao())
            val playlist = SavedCanonicalStaticPlaylist(
                id = "normal-1",
                name = "Viagem",
                recordingIds = listOf("recording-a", "recording-b", "recording-c"),
            )
            repository.save(playlist)
            assertEquals(playlist, repository.list().single())
            assertEquals(playlist, repository.observe().first().single())
            repository.save(playlist.copy(name = "Viagem nova", recordingIds = listOf("recording-c")))
            assertEquals(listOf("recording-c"), repository.list().single().recordingIds)
            assertEquals("normal-1", repository.list().single().id)
            repository.remove("normal-1")
            assertTrue(repository.list().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun invalidNamesIdsAndDuplicateMembersAreRejected() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = CanonicalStaticPlaylistRepository(db.staticPlaylistDao())
            assertTrue(runCatching { repository.save(
                SavedCanonicalStaticPlaylist("bad", "   ", emptyList())
            ) }.isFailure)
            assertTrue(runCatching { repository.save(
                SavedCanonicalStaticPlaylist("bad", "Ok", listOf("rec", "rec"))
            ) }.isFailure)
            assertTrue(runCatching { repository.save(
                SavedCanonicalStaticPlaylist("bad", "Ok", listOf(" https://not-a-recording "))
            ) }.isFailure)
            assertEquals(emptyList<SavedCanonicalStaticPlaylist>(), repository.list())
        } finally {
            db.close()
        }
    }

    @Test fun upgradesVersionThreeWithoutErasingSavedRulesOrFilters() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "static-playlist-3-to-4-test.db"
        context.deleteDatabase(name)
        val sqlite = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            sqlite.execSQL("""
                CREATE TABLE IF NOT EXISTS smart_playlist_definition (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL, minimumExplicitRating REAL,
                    minimumCompletions INTEGER, minimumDaysSinceAudible INTEGER,
                    maximumDaysSinceAudible INTEGER, minimumDurationMs INTEGER,
                    `limit` INTEGER NOT NULL)
            """.trimIndent())
            sqlite.execSQL("""
                CREATE TABLE IF NOT EXISTS smart_playlist_v2_definition (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL, queryJson TEXT NOT NULL)
            """.trimIndent())
            sqlite.execSQL("""
                CREATE TABLE IF NOT EXISTS smart_playlist_v2_filter (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL, rulesJson TEXT NOT NULL)
            """.trimIndent())
            sqlite.execSQL("INSERT INTO smart_playlist_definition (id,name,schemaVersion,`limit`) VALUES ('old','Before',1,100)")
            sqlite.version = 3
        } finally {
            sqlite.close()
        }
        val db = Room.databaseBuilder(context, SmartPlaylistDefinitionDatabase::class.java, name)
            .addMigrations(
                SmartPlaylistDefinitionDatabase.MIGRATION_1_2,
                SmartPlaylistDefinitionDatabase.MIGRATION_2_3,
                SmartPlaylistDefinitionDatabase.MIGRATION_3_4,
            ).allowMainThreadQueries().build()
        try {
            assertEquals("Before", SmartPlaylistDefinitionRepository(db.definitionDao()).list().single().name)
            val repository = CanonicalStaticPlaylistRepository(db.staticPlaylistDao())
            repository.save(SavedCanonicalStaticPlaylist("new", "Normal", emptyList()))
            assertEquals("new", repository.list().single().id)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
