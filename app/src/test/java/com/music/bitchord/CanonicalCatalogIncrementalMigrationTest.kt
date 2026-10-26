package com.music.bitchord

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A real old SQLite file must survive two new additive catalog migrations. */
@RunWith(RobolectricTestRunner::class)
class CanonicalCatalogIncrementalMigrationTest {
    @Test fun migrationsFromSevenPreserveMusicBrainzGroupsAndCoverCache() {
        val context = RuntimeEnvironment.getApplication()
        val name = "catalog-incremental-v7-to-v9.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE catalog_release_group(
                            id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL,
                            artistDisplay TEXT, primaryType TEXT, firstReleaseDate TEXT
                        )""")
                        db.execSQL("""CREATE TABLE catalog_cover_art_cache(
                            entityType TEXT NOT NULL, mbid TEXT NOT NULL, artworkUri TEXT,
                            fetchedAtEpochMs INTEGER NOT NULL,
                            PRIMARY KEY(entityType, mbid)
                        )""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("""INSERT INTO catalog_release_group
                (id, title, artistDisplay, primaryType, firstReleaseDate)
                VALUES ('old-group','NJWMX','NewJeans','Album','2023-12-19')""")
            db.execSQL("""INSERT INTO catalog_cover_art_cache
                (entityType,mbid,artworkUri,fetchedAtEpochMs)
                VALUES ('release-group:front-v2','verified','https://archive.org/old.jpg',1000)""")
            CanonicalCatalogDatabase.MIGRATION_7_8.migrate(db)
            CanonicalCatalogDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT title,primaryType,secondaryTypes FROM catalog_release_group WHERE id = 'old-group'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("NJWMX", it.getString(0))
                assertEquals("Album", it.getString(1))
                assertNull(it.getString(2))
            }
            db.query("SELECT artworkUri,thumbnailUri FROM catalog_cover_art_cache WHERE mbid = 'verified'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("https://archive.org/old.jpg", it.getString(0))
                assertNull(it.getString(1))
            }
            db.execSQL("UPDATE catalog_release_group SET secondaryTypes='Remix' WHERE id='old-group'")
            db.execSQL("UPDATE catalog_cover_art_cache SET thumbnailUri='https://archive.org/500.jpg' WHERE mbid='verified'")
            db.query("SELECT secondaryTypes FROM catalog_release_group WHERE id='old-group'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("Remix", it.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
