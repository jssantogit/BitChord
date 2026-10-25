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
        RemoteRatingEvidenceEntity::class,
        RemoteRatingCoverageEntity::class,
        MusicBrainzArtistEnrichmentEntity::class,
        MusicBrainzRecordingEnrichmentEntity::class,
        MusicBrainzRecordingArtistCreditEntity::class,
        MusicBrainzReleaseEnrichmentEntity::class,
        MusicBrainzReleaseArtistCreditEntity::class,
        CatalogReleaseGroupEntity::class,
        CatalogReleaseGroupEditionEntity::class,
        CatalogMusicBrainzTrackEntity::class,
        MusicBrainzCoverArtCacheEntity::class,
    ],
    version = 5,
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

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `musicbrainz_artist_enrichment` (
                        `artistId` TEXT NOT NULL,
                        `mbid` TEXT NOT NULL,
                        `name` TEXT,
                        `sortName` TEXT,
                        `disambiguation` TEXT,
                        `country` TEXT,
                        `area` TEXT,
                        `fetchedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`artistId`),
                        FOREIGN KEY(`artistId`) REFERENCES `catalog_artist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_musicbrainz_artist_enrichment_mbid` ON `musicbrainz_artist_enrichment` (`mbid`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `musicbrainz_recording_enrichment` (
                        `recordingId` TEXT NOT NULL,
                        `mbid` TEXT NOT NULL,
                        `title` TEXT,
                        `durationMs` INTEGER,
                        `artistDisplay` TEXT,
                        `fetchedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`recordingId`),
                        FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_musicbrainz_recording_enrichment_mbid` ON `musicbrainz_recording_enrichment` (`mbid`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `musicbrainz_recording_artist_credit` (
                        `recordingId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `musicBrainzArtistId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `joinPhrase` TEXT,
                        PRIMARY KEY(`recordingId`, `position`),
                        FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_musicbrainz_recording_artist_credit_musicBrainzArtistId` ON `musicbrainz_recording_artist_credit` (`musicBrainzArtistId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `musicbrainz_release_enrichment` (
                        `releaseId` TEXT NOT NULL,
                        `mbid` TEXT NOT NULL,
                        `title` TEXT,
                        `artistDisplay` TEXT,
                        `releaseDate` TEXT,
                        `country` TEXT,
                        `status` TEXT,
                        `fetchedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`releaseId`),
                        FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_musicbrainz_release_enrichment_mbid` ON `musicbrainz_release_enrichment` (`mbid`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `musicbrainz_release_artist_credit` (
                        `releaseId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `musicBrainzArtistId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `joinPhrase` TEXT,
                        PRIMARY KEY(`releaseId`, `position`),
                        FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_musicbrainz_release_artist_credit_musicBrainzArtistId` ON `musicbrainz_release_artist_credit` (`musicBrainzArtistId`)",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_remote_rating_evidence` (
                        `providerKey` TEXT NOT NULL,
                        `scopeId` TEXT NOT NULL,
                        `mediaAssetId` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `numericKind` TEXT NOT NULL,
                        `numericValue` INTEGER,
                        `starredKind` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        PRIMARY KEY(`providerKey`, `scopeId`, `mediaAssetId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_remote_rating_evidence_recordingId` " +
                        "ON `catalog_remote_rating_evidence` (`recordingId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_remote_rating_coverage` (
                        `providerKey` TEXT NOT NULL,
                        `scopeId` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        `enumerated` INTEGER NOT NULL,
                        `matched` INTEGER NOT NULL,
                        `unmatched` INTEGER NOT NULL,
                        `absentNumeric` INTEGER NOT NULL,
                        `clearedNumeric` INTEGER NOT NULL,
                        `valuedNumeric` INTEGER NOT NULL,
                        `invalidNumeric` INTEGER NOT NULL,
                        `starred` INTEGER NOT NULL,
                        `invalidStarred` INTEGER NOT NULL,
                        PRIMARY KEY(`providerKey`)
                    )
                    """.trimIndent(),
                )
            }
        }


        /**
         * Additive only. Releases/Recordings/MediaAssets and their identity
         * aliases remain byte-for-byte intact on existing installations.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_release_group` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artistDisplay` TEXT,
                        `primaryType` TEXT,
                        `firstReleaseDate` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_release_group_edition` (
                        `releaseGroupId` TEXT NOT NULL,
                        `releaseId` TEXT NOT NULL,
                        PRIMARY KEY(`releaseGroupId`, `releaseId`),
                        FOREIGN KEY(`releaseGroupId`) REFERENCES `catalog_release_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_release_group_edition_releaseId` ON `catalog_release_group_edition` (`releaseId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_musicbrainz_track` (
                        `trackMbid` TEXT NOT NULL,
                        `releaseId` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `mediumNumber` INTEGER NOT NULL,
                        `trackNumber` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `durationMs` INTEGER,
                        PRIMARY KEY(`trackMbid`),
                        FOREIGN KEY(`releaseId`) REFERENCES `catalog_release`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`recordingId`) REFERENCES `catalog_recording`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_musicbrainz_track_releaseId` ON `catalog_musicbrainz_track` (`releaseId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_musicbrainz_track_recordingId` ON `catalog_musicbrainz_track` (`recordingId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_cover_art_cache` (
                        `entityType` TEXT NOT NULL,
                        `mbid` TEXT NOT NULL,
                        `artworkUri` TEXT,
                        `fetchedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`entityType`, `mbid`)
                    )
                    """.trimIndent(),
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
