package com.music.bitchord

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** A real old SQLite file must survive four additive catalog migrations. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalCatalogIncrementalMigrationTest {
    @Test fun migrationsFromSevenPreserveGroupsCoverCacheAndLegacyPublicationEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val name = "catalog-incremental-v7-to-v9.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        // Use the same SQLiteDatabase-backed migration fixture as the other
        // Robolectric migration tests, not a second open-helper/native loader.
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        // AndroidX FrameworkSQLiteDatabase is library-internal. These
        // migrations only require execSQL; delegate those calls to a real
        // Android SQLiteDatabase through the public SupportSQLiteDatabase
        // contract without using implementation-private classes.
        val migrationDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            check(method.name == "execSQL") { "Unexpected migration API: ${method.name}" }
            raw.execSQL(arguments!![0] as String)
            null
        } as SupportSQLiteDatabase
        try {
            // A v14 database already contains canonical media assets; migration
            // 14→15 must alter their REAL pre-migration table, not a partial
            // v7 MusicBrainz-only fixture that never created media assets.
            raw.execSQL("""CREATE TABLE catalog_media_asset(
                id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL,
                provider TEXT NOT NULL, sourceKey TEXT NOT NULL,
                uri TEXT NOT NULL, path TEXT, dateAddedSeconds INTEGER,
                dateModifiedSeconds INTEGER, artworkUri TEXT
            )""")
            raw.execSQL("""INSERT INTO catalog_media_asset
                (id,recordingId,provider,sourceKey,uri,path,dateAddedSeconds,dateModifiedSeconds,artworkUri)
                VALUES ('legacy-asset','old-recording','local-mediastore','legacy:1',
                'content://media/external/audio/media/1','Music/Legacy/01.flac',100,101,NULL)""")
            raw.execSQL("""CREATE TABLE catalog_release_group(
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL,
                artistDisplay TEXT, primaryType TEXT, firstReleaseDate TEXT
            )""")
            raw.execSQL("""CREATE TABLE catalog_cover_art_cache(
                entityType TEXT NOT NULL, mbid TEXT NOT NULL, artworkUri TEXT,
                fetchedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(entityType, mbid)
            )""")
            raw.execSQL("""CREATE TABLE catalog_musicbrainz_artist_group(
                artistId TEXT NOT NULL, releaseGroupId TEXT NOT NULL,
                PRIMARY KEY(artistId, releaseGroupId)
            )""")
            raw.execSQL("""CREATE TABLE musicbrainz_recording_enrichment(
                recordingId TEXT NOT NULL PRIMARY KEY,
                mbid TEXT NOT NULL, title TEXT, durationMs INTEGER,
                artistDisplay TEXT, fetchedAtEpochMs INTEGER NOT NULL
            )""")
            raw.execSQL("""INSERT INTO musicbrainz_recording_enrichment
                (recordingId,mbid,title,durationMs,artistDisplay,fetchedAtEpochMs)
                VALUES ('old-recording','11111111-1111-4111-8111-111111111111',
                    'Hurt',200000,'NewJeans',1000)""")
            raw.execSQL("INSERT INTO catalog_musicbrainz_artist_group VALUES('artist-local','old-group')")
            raw.execSQL("""INSERT INTO catalog_release_group
                (id, title, artistDisplay, primaryType, firstReleaseDate)
                VALUES ('old-group','NJWMX','NewJeans','Album','2023-12-19')""")
            raw.execSQL("""INSERT INTO catalog_cover_art_cache
                (entityType,mbid,artworkUri,fetchedAtEpochMs)
                VALUES ('release-group:front-v2','verified','https://archive.org/old.jpg',1000)""")
            CanonicalCatalogDatabase.MIGRATION_7_8.migrate(migrationDb)
            CanonicalCatalogDatabase.MIGRATION_8_9.migrate(migrationDb)
            CanonicalCatalogDatabase.MIGRATION_9_10.migrate(migrationDb)
            CanonicalCatalogDatabase.MIGRATION_10_11.migrate(migrationDb)
            CanonicalCatalogDatabase.MIGRATION_11_12.migrate(migrationDb)
            // New server tags are additive, asset-scoped and nullable; legacy
            // canonical recordings and MusicBrainz evidence remain unchanged.
            raw.rawQuery("PRAGMA table_info(catalog_media_asset_tags)", null).use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(1)
                assertEquals(listOf("mediaAssetId", "genresJson", "moodsJson"), names)
            }
            CanonicalCatalogDatabase.MIGRATION_12_13.migrate(migrationDb)
            raw.rawQuery("PRAGMA table_info(catalog_media_asset_tags)", null).use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(1)
                assertEquals(listOf(
                    "mediaAssetId", "genresJson", "moodsJson", "bpmsJson",
                    "stylesJson", "localSourceFingerprint", "localParsedFingerprint",
                ), names)
            }
            CanonicalCatalogDatabase.MIGRATION_13_14.migrate(migrationDb)
            raw.rawQuery("PRAGMA table_info(catalog_media_asset_tags)", null).use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(1)
                org.junit.Assert.assertTrue("composer column must be migrated",
                    "composersJson" in names)
            }
            // Reconcile the indexed MediaStore fields from the UI PR without
            // destroying v14 composer or style evidence on existing assets.
            CanonicalCatalogDatabase.MIGRATION_14_15.migrate(migrationDb)
            raw.rawQuery("PRAGMA table_info(catalog_media_asset)", null).use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(1)
                org.junit.Assert.assertTrue("local genre must be migrated", "localGenre" in names)
                org.junit.Assert.assertTrue("bitrate must be migrated", "localBitrateBps" in names)
            }
            raw.rawQuery(
                "SELECT path,localGenre,localBitrateBps FROM catalog_media_asset WHERE id='legacy-asset'",
                null,
            ).use {
                org.junit.Assert.assertTrue("v14 asset survives migration v15", it.moveToFirst())
                assertEquals("Music/Legacy/01.flac", it.getString(0))
                assertNull(it.getString(1))
                assertNull(it.getString(2))
            }

            CanonicalCatalogDatabase.MIGRATION_15_16.migrate(migrationDb)
            raw.rawQuery("PRAGMA table_info(catalog_media_asset)", null).use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(1)
                org.junit.Assert.assertTrue("MIME must be migrated", "audioContentType" in names)
                org.junit.Assert.assertTrue("suffix must be migrated", "audioSuffix" in names)
                org.junit.Assert.assertTrue("audio bitrate must be migrated", "audioBitrateBps" in names)
                org.junit.Assert.assertTrue("bit depth must be migrated", "audioBitDepth" in names)
                org.junit.Assert.assertTrue("sample rate must be migrated", "audioSamplingRateHz" in names)
            }
            raw.rawQuery(
                "SELECT path,audioContentType,audioSuffix,audioBitrateBps,audioBitDepth," +
                    "audioSamplingRateHz FROM catalog_media_asset WHERE id='legacy-asset'",
                null,
            ).use {
                org.junit.Assert.assertTrue("old media asset survives v16", it.moveToFirst())
                assertEquals("Music/Legacy/01.flac", it.getString(0))
                for (index in 1..5) assertNull(it.getString(index))
            }

            raw.rawQuery(
                "SELECT title,hasOfficialAudioRelease,isVideo FROM musicbrainz_recording_enrichment WHERE recordingId='old-recording'",
                null,
            ).use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("Hurt", it.getString(0))
                assertNull("legacy audio approval must remain unknown", it.getString(1))
                assertNull("legacy video flag must remain unknown", it.getString(2))
            }
            raw.rawQuery(
                "SELECT publicationEvidence FROM catalog_musicbrainz_artist_group WHERE artistId='artist-local'",
                null,
            ).use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertNull("v9 links cannot be silently marked Official", it.getString(0))
            }
            raw.rawQuery("SELECT title,primaryType,secondaryTypes FROM catalog_release_group WHERE id = 'old-group'", null).use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("NJWMX", it.getString(0))
                assertEquals("Album", it.getString(1))
                assertNull(it.getString(2))
            }
            raw.rawQuery("SELECT artworkUri,thumbnailUri FROM catalog_cover_art_cache WHERE mbid = 'verified'", null).use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("https://archive.org/old.jpg", it.getString(0))
                assertNull(it.getString(1))
            }
            raw.execSQL("UPDATE catalog_release_group SET secondaryTypes='Remix' WHERE id='old-group'")
            raw.execSQL("UPDATE catalog_cover_art_cache SET thumbnailUri='https://archive.org/500.jpg' WHERE mbid='verified'")
            raw.rawQuery("SELECT secondaryTypes FROM catalog_release_group WHERE id='old-group'", null).use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("Remix", it.getString(0))
            }
        } finally {
            raw.close()
            context.deleteDatabase(name)
        }
    }
}
