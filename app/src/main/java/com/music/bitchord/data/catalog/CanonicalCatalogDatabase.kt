package com.music.bitchord.data.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingEntity::class,
        ReleaseEntity::class,
        ArtistEntity::class,
        ReleaseTrackEntity::class,
        RecordingArtistCreditEntity::class,
        ReleaseArtistCreditEntity::class,
        MediaAssetEntity::class,
        ExternalIdentityEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class CanonicalCatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CanonicalCatalogDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_artist` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_recording_artist_credit` (
                        `recordingId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `artistId` TEXT NOT NULL,
                        PRIMARY KEY(`recordingId`, `position`),
                        FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`artistId`) REFERENCES `catalog_artist`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_recording_artist_credit_artistId` ON `catalog_recording_artist_credit` (`artistId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_release_artist_credit` (
                        `releaseId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `artistId` TEXT NOT NULL,
                        PRIMARY KEY(`releaseId`, `position`),
                        FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`artistId`) REFERENCES `catalog_artist`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_release_artist_credit_artistId` ON `catalog_release_artist_credit` (`artistId`)",
                )
            }
        }

        @Volatile
        private var instance: CanonicalCatalogDatabase? = null

        fun get(context: Context): CanonicalCatalogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CanonicalCatalogDatabase::class.java,
                    "canonical-catalog.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
