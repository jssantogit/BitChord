package com.music.bitchord

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalCatalogArtistMigrationTest {

    @Test
    fun migrationFromV1PreservesExistingCatalogAndDoesNotInferArtistsFromDisplayStrings() {
        val context = RuntimeEnvironment.getApplication()
        val name = "canonical-artist-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        createVersionOneDatabase(file)

        val database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .addMigrations(
                CanonicalCatalogDatabase.MIGRATION_1_2,
                CanonicalCatalogDatabase.MIGRATION_2_3,
            )
            .allowMainThreadQueries()
            .build()
        val sql = database.openHelper.writableDatabase

        sql.query(
            "SELECT title, artistDisplay, durationMs FROM catalog_recording WHERE id = 'recording-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Existing Track", cursor.getString(0))
            assertEquals("Artist A & Artist B", cursor.getString(1))
            assertEquals(123_000L, cursor.getLong(2))
        }
        sql.query(
            "SELECT title, artistDisplay FROM catalog_release WHERE id = 'release-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Existing Album", cursor.getString(0))
            assertEquals("Artist A & Artist B", cursor.getString(1))
        }
        sql.query("SELECT COUNT(*) FROM catalog_media_asset").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        sql.query("SELECT COUNT(*) FROM catalog_artist").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        sql.query("SELECT COUNT(*) FROM catalog_recording_artist_credit").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        sql.query("SELECT COUNT(*) FROM catalog_release_artist_credit").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun readApiReturnsOrderedCreditsAndRecordingsByCanonicalArtist() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.catalogDao()

        dao.upsertRecording(RecordingEntity("recording-1", "Song One", "A & B", 100_000L))
        dao.upsertRecording(RecordingEntity("recording-2", "Song Two", "A", 200_000L))
        dao.upsertRelease(ReleaseEntity("release-1", "Album", "B & A", null))
        dao.upsertArtist(ArtistEntity("artist-a", "Artist A"))
        dao.upsertArtist(ArtistEntity("artist-b", "Artist B"))
        dao.replaceRecordingArtistCredits(
            "recording-1",
            listOf(
                RecordingArtistCreditEntity("recording-1", 0, "artist-b"),
                RecordingArtistCreditEntity("recording-1", 1, "artist-a"),
            ),
        )
        dao.replaceRecordingArtistCredits(
            "recording-2",
            listOf(RecordingArtistCreditEntity("recording-2", 0, "artist-a")),
        )
        dao.replaceReleaseArtistCredits(
            "release-1",
            listOf(
                ReleaseArtistCreditEntity("release-1", 0, "artist-b"),
                ReleaseArtistCreditEntity("release-1", 1, "artist-a"),
            ),
        )

        assertEquals(
            listOf("artist-b", "artist-a"),
            dao.recordingArtistCredits("recording-1").map { it.artistId },
        )
        assertEquals(
            listOf("artist-b", "artist-a"),
            dao.releaseArtistCredits("release-1").map { it.artistId },
        )
        assertEquals(
            listOf("recording-1", "recording-2"),
            dao.recordingsByArtist("artist-a").map { it.id },
        )

        database.close()
    }

    private fun createVersionOneDatabase(file: File) {
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_recording` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artistDisplay` TEXT NOT NULL, `durationMs` INTEGER, PRIMARY KEY(`id`))",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_release` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artistDisplay` TEXT, `artworkUri` TEXT, PRIMARY KEY(`id`))",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_release_track` (`releaseId` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `trackNumber` INTEGER, PRIMARY KEY(`releaseId`, `recordingId`), FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_catalog_release_track_recordingId` ON `catalog_release_track` (`recordingId`)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_media_asset` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `provider` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `uri` TEXT NOT NULL, `path` TEXT, `dateAddedSeconds` INTEGER, `dateModifiedSeconds` INTEGER, `artworkUri` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_catalog_media_asset_recordingId` ON `catalog_media_asset` (`recordingId`)",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_catalog_media_asset_provider_sourceKey` ON `catalog_media_asset` (`provider`, `sourceKey`)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_external_identity` (`provider` TEXT NOT NULL, `entityType` TEXT NOT NULL, `externalId` TEXT NOT NULL, `entityId` TEXT NOT NULL, PRIMARY KEY(`provider`, `entityType`, `externalId`))",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_catalog_external_identity_entityId` ON `catalog_external_identity` (`entityId`)",
        )
        database.execSQL(
            "INSERT INTO catalog_recording(id,title,artistDisplay,durationMs) VALUES('recording-1','Existing Track','Artist A & Artist B',123000)",
        )
        database.execSQL(
            "INSERT INTO catalog_release(id,title,artistDisplay,artworkUri) VALUES('release-1','Existing Album','Artist A & Artist B',NULL)",
        )
        database.execSQL(
            "INSERT INTO catalog_release_track(releaseId,recordingId,trackNumber) VALUES('release-1','recording-1',1)",
        )
        database.execSQL(
            "INSERT INTO catalog_media_asset(id,recordingId,provider,sourceKey,uri,path,dateAddedSeconds,dateModifiedSeconds,artworkUri) VALUES('asset-1','recording-1','local-mediastore','1','content://media/1',NULL,NULL,NULL,NULL)",
        )
        database.version = 1
        database.close()
        assertTrue(file.exists())
        assertFalse(file.length() == 0L)
    }
}
