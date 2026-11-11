package com.music.bitchord

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzEnrichmentMigrationTest {

    @Test
    fun migrationFromV2PreservesCanonicalCatalogAndStartsWithEmptyEnrichmentCache() {
        val context = RuntimeEnvironment.getApplication()
        val name = "musicbrainz-v2-v3-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        createVersionTwoDatabase(file)

        val database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .addMigrations(
                CanonicalCatalogDatabase.MIGRATION_2_3,
                CanonicalCatalogDatabase.MIGRATION_3_4,
                CanonicalCatalogDatabase.MIGRATION_4_5,
                CanonicalCatalogDatabase.MIGRATION_5_6,
                CanonicalCatalogDatabase.MIGRATION_6_7,
                CanonicalCatalogDatabase.MIGRATION_7_8,
                CanonicalCatalogDatabase.MIGRATION_8_9,
                CanonicalCatalogDatabase.MIGRATION_9_10,
                CanonicalCatalogDatabase.MIGRATION_10_11,
                CanonicalCatalogDatabase.MIGRATION_11_12,
                CanonicalCatalogDatabase.MIGRATION_12_13,
                CanonicalCatalogDatabase.MIGRATION_13_14,
                CanonicalCatalogDatabase.MIGRATION_14_15,
                CanonicalCatalogDatabase.MIGRATION_15_16,
                CanonicalCatalogDatabase.MIGRATION_16_17,
            )
            .allowMainThreadQueries()
            .build()
        val sql = database.openHelper.writableDatabase

        sql.query("SELECT title, artistDisplay, durationMs FROM catalog_recording WHERE id = 'recording-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Existing Track", cursor.getString(0))
            assertEquals("Existing Artist", cursor.getString(1))
            assertEquals(123_000L, cursor.getLong(2))
        }
        sql.query("SELECT name FROM catalog_artist WHERE id = 'artist-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Existing Artist", cursor.getString(0))
        }
        sql.query("SELECT externalId FROM catalog_external_identity WHERE entityId = 'recording-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(RECORDING_MBID, cursor.getString(0))
        }
        listOf(
            "musicbrainz_artist_enrichment",
            "musicbrainz_recording_enrichment",
            "musicbrainz_recording_artist_credit",
            "musicbrainz_release_enrichment",
            "musicbrainz_release_artist_credit",
        ).forEach { table ->
            sql.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("New cache table $table must start empty", 0, cursor.getInt(0))
            }
        }

        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationFromSixteenPreservesLegacyReleaseAndStartsWithUnknownVideoProof() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("musicbrainz-video-16-17-${System.nanoTime()}.db")
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val mbid = "11111111-1111-4111-8111-111111111111"
        try {
            raw.execSQL("""CREATE TABLE musicbrainz_release_enrichment(
                releaseId TEXT NOT NULL PRIMARY KEY, mbid TEXT NOT NULL,
                title TEXT, artistDisplay TEXT, releaseDate TEXT,
                country TEXT, status TEXT, fetchedAtEpochMs INTEGER NOT NULL
            )""")
            raw.execSQL("""INSERT INTO musicbrainz_release_enrichment(
                releaseId,mbid,title,artistDisplay,releaseDate,country,status,fetchedAtEpochMs
            ) VALUES ('legacy','$mbid','Original','Artist',
                '2023-01-01','XW','Official',12345)""")
            val db = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                check(method.name == "execSQL")
                raw.execSQL(args!![0] as String)
                null
            } as SupportSQLiteDatabase
            CanonicalCatalogDatabase.MIGRATION_16_17.migrate(db)
            raw.rawQuery("""SELECT mbid,title,status,fetchedAtEpochMs,
                isVideoOnlyMedia,isVideoOnlyContent
                FROM musicbrainz_release_enrichment WHERE releaseId='legacy'""", null)
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(mbid, cursor.getString(0))
                    assertEquals("Original", cursor.getString(1))
                    assertEquals("Official", cursor.getString(2))
                    assertEquals(12345L, cursor.getLong(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                }
        } finally {
            raw.close()
            file.delete()
        }
    }

    private fun createVersionTwoDatabase(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE `catalog_recording` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artistDisplay` TEXT NOT NULL, `durationMs` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE `catalog_release` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artistDisplay` TEXT, `artworkUri` TEXT, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE `catalog_artist` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE `catalog_release_track` (`releaseId` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `trackNumber` INTEGER, PRIMARY KEY(`releaseId`, `recordingId`), FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX `index_catalog_release_track_recordingId` ON `catalog_release_track` (`recordingId`)")
        db.execSQL("CREATE TABLE `catalog_recording_artist_credit` (`recordingId` TEXT NOT NULL, `position` INTEGER NOT NULL, `artistId` TEXT NOT NULL, PRIMARY KEY(`recordingId`, `position`), FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`artistId`) REFERENCES `catalog_artist`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX `index_catalog_recording_artist_credit_artistId` ON `catalog_recording_artist_credit` (`artistId`)")
        db.execSQL("CREATE TABLE `catalog_release_artist_credit` (`releaseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `artistId` TEXT NOT NULL, PRIMARY KEY(`releaseId`, `position`), FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`artistId`) REFERENCES `catalog_artist`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX `index_catalog_release_artist_credit_artistId` ON `catalog_release_artist_credit` (`artistId`)")
        db.execSQL("CREATE TABLE `catalog_media_asset` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `provider` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `uri` TEXT NOT NULL, `path` TEXT, `dateAddedSeconds` INTEGER, `dateModifiedSeconds` INTEGER, `artworkUri` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX `index_catalog_media_asset_recordingId` ON `catalog_media_asset` (`recordingId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_catalog_media_asset_provider_sourceKey` ON `catalog_media_asset` (`provider`, `sourceKey`)")
        db.execSQL("CREATE TABLE `catalog_external_identity` (`provider` TEXT NOT NULL, `entityType` TEXT NOT NULL, `externalId` TEXT NOT NULL, `entityId` TEXT NOT NULL, PRIMARY KEY(`provider`, `entityType`, `externalId`))")
        db.execSQL("CREATE INDEX `index_catalog_external_identity_entityId` ON `catalog_external_identity` (`entityId`)")

        db.execSQL("INSERT INTO catalog_recording(id,title,artistDisplay,durationMs) VALUES('recording-1','Existing Track','Existing Artist',123000)")
        db.execSQL("INSERT INTO catalog_release(id,title,artistDisplay,artworkUri) VALUES('release-1','Existing Album','Existing Artist',NULL)")
        db.execSQL("INSERT INTO catalog_artist(id,name) VALUES('artist-1','Existing Artist')")
        db.execSQL("INSERT INTO catalog_recording_artist_credit(recordingId,position,artistId) VALUES('recording-1',0,'artist-1')")
        db.execSQL("INSERT INTO catalog_release_artist_credit(releaseId,position,artistId) VALUES('release-1',0,'artist-1')")
        db.execSQL("INSERT INTO catalog_release_track(releaseId,recordingId,trackNumber) VALUES('release-1','recording-1',1)")
        db.execSQL("INSERT INTO catalog_media_asset(id,recordingId,provider,sourceKey,uri,path,dateAddedSeconds,dateModifiedSeconds,artworkUri) VALUES('asset-1','recording-1','local-mediastore','1','content://media/1',NULL,NULL,NULL,NULL)")
        db.execSQL("INSERT INTO catalog_external_identity(provider,entityType,externalId,entityId) VALUES('musicbrainz','recording','$RECORDING_MBID','recording-1')")
        db.version = 2
        db.close()
    }

    companion object {
        private const val RECORDING_MBID = "22222222-2222-2222-2222-222222222222"
    }
}
